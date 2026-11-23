package emby

// What a service outside Emby is asked about an item: the intro services
// (SegmentQuery) and the Stremio addons (ExternalRef).

import (
	"context"
	"errors"

	segs "viewer/internal/segments"
)

// ExternalRef is what a catalogue outside Emby is asked about an item: a film by
// its own ids, an episode by its series' ids and its numbers, with the title the
// player shows for it (the one emby_play returns).
type ExternalRef struct {
	// ItemType is the item's `Type`: "Movie", "Episode" or any other.
	ItemType string
	// IDs are the film's, or the episode's series'; empty when unknown.
	IDs segs.MediaIDs
	// Episode is the season and episode of an episode, nil otherwise.
	Episode *segs.EpisodeNumber
	// Title is nil when the item has no name.
	Title *PlaybackTitle
}

// ExternalRef reads the item, and the series of an episode. A series that is not
// found, or another item in its place, leaves the ids empty; any other failure is
// returned, a refused token ending the session as any read.
func (s *EmbyService) ExternalRef(ctx context.Context, itemID string) (ExternalRef, error) {
	client, userID, err := s.session()
	if err != nil {
		return ExternalRef{}, err
	}
	dto, err := client.Item(ctx, userID, itemID)
	err = s.expireOn401(client, err)
	if err != nil {
		return ExternalRef{}, err
	}
	if dto.ID != itemID {
		return ExternalRef{}, errOtherItem
	}
	ref := ExternalRef{ItemType: dto.ItemType, Title: playbackTitle(&dto)}
	ids, episode, asked, err := externalIDs(ctx, client, userID, &dto)
	switch {
	case errors.Is(err, ErrNotFound):
	case err != nil:
		return ExternalRef{}, s.expireOn401(client, err)
	case asked:
		ref.IDs, ref.Episode = ids, episode
	}
	return ref, nil
}

// externalIDs are the ids an outside service is asked about dto: a film's own, an
// episode's series' with its numbers (the series is read). asked is false for any
// other item, an episode without its series or numbers, or a series answered by
// another item; err is the failure to read the series.
func externalIDs(ctx context.Context, client *Client, userID string, dto *BaseItemDto) (ids segs.MediaIDs, episode *segs.EpisodeNumber, asked bool, err error) {
	switch dto.ItemType {
	case "Movie":
		return segs.MediaIDsFromProviderIDs(dto.ProviderIDs), nil, true, nil
	case "Episode":
		sid := dto.SeriesID
		if sid == nil || !isID(*sid) || dto.ParentIndexNumber == nil || dto.IndexNumber == nil {
			return segs.MediaIDs{}, nil, false, nil
		}
		series, err := client.Item(ctx, userID, *sid)
		if err != nil {
			return segs.MediaIDs{}, nil, false, err
		}
		if series.ID != *sid {
			return segs.MediaIDs{}, nil, false, nil
		}
		return segs.MediaIDsFromProviderIDs(series.ProviderIDs),
			&segs.EpisodeNumber{Season: *dto.ParentIndexNumber, Episode: *dto.IndexNumber}, true, nil
	}
	return segs.MediaIDs{}, nil, false, nil
}
