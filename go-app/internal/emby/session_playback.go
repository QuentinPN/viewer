package emby

// Playback, reports and segments of the open session (session.rs).

import (
	"context"
	"errors"
	"slices"
	"strconv"

	"viewer/internal/player"
	segs "viewer/internal/segments"
)

// SegmentsKey is `segments_key`: cache key of an item's segments, one per server,
// user and privacy setting.
func (s *EmbyService) SegmentsKey(itemID string, external bool) (string, error) {
	client, userID, err := s.session()
	if err != nil {
		return "", err
	}
	return client.Server().String() + "|" + userID + "|" + itemID + "|" + strconv.FormatBool(external), nil
}

// EmbyMarkers is `segments::emby_markers` on Emby's chapters.
func EmbyMarkers(chapters []ChapterDto) []segs.Segment {
	markers := make([]segs.Marker, len(chapters))
	for i, c := range chapters {
		markers[i] = segs.Marker{StartPositionTicks: c.StartPositionTicks, MarkerType: c.MarkerType}
	}
	return segs.EmbyMarkers(markers)
}

// SegmentQuery is `segment_query`: Emby's markers of an item and what the external
// sources are asked about it: a film by its ids, an episode by its
// series' ids and numbers. The query is nil when the item has no usable id.
func (s *EmbyService) SegmentQuery(ctx context.Context, itemID string) ([]segs.Segment, *segs.Query, error) {
	client, userID, err := s.session()
	if err != nil {
		return nil, nil, err
	}
	dto, err := client.Item(ctx, userID, itemID)
	err = s.expireOn401(client, err)
	if err != nil {
		return nil, nil, err
	}
	if dto.ID != itemID {
		return nil, nil, errOtherItem
	}
	markers := EmbyMarkers(dto.Chapters)
	var durationMs *uint64
	if t := dto.RunTimeTicks; t != nil && *t > 0 {
		ms := uint64(*t / 10_000)
		durationMs = &ms
	}
	var ids segs.MediaIDs
	var episode *segs.EpisodeNumber
	asked := false
	switch dto.ItemType {
	case "Movie":
		ids, asked = segs.MediaIDsFromProviderIDs(dto.ProviderIDs), true
	case "Episode":
		if sid := dto.SeriesID; sid != nil && isID(*sid) && dto.ParentIndexNumber != nil && dto.IndexNumber != nil {
			series, err := client.Item(ctx, userID, *sid)
			switch {
			case err == nil && series.ID == *sid:
				ids, asked = segs.MediaIDsFromProviderIDs(series.ProviderIDs), true
				episode = &segs.EpisodeNumber{Season: *dto.ParentIndexNumber, Episode: *dto.IndexNumber}
			case errors.Is(err, ErrSessionExpired):
				return nil, nil, s.expireOn401(client, ErrSessionExpired)
			}
			// Otherwise the series is only needed for its ids: no query, no error.
		}
	}
	if !asked || ids.IsEmpty() {
		return markers, nil, nil
	}
	return markers, &segs.Query{IDs: ids, Episode: episode, DurationMs: durationMs}, nil
}

// PrepareResult is what a negotiation gives the player: the plan, and the headers
// it must send, authentication included; the URL holds no token.
type PrepareResult struct {
	Plan    PlaybackPlan
	Headers []player.Header
}

// PreparePlayback is `prepare_playback`: negotiates playback of a film or episode.
// With resume, the plan starts at the position the server holds now.
// The item is read alongside PlaybackInfo for the player's title;
// without resume, failing to read it only leaves the title out.
func (s *EmbyService) PreparePlayback(ctx context.Context, itemID string, resume bool) (PrepareResult, error) {
	return s.PrepareSource(ctx, itemID, nil, resume, false, TrackPreferences{})
}

// PrepareTranscode is `prepare_transcode`: PreparePlayback for when the player could
// not open the direct source: Direct Play and Direct Stream are refused to the server
// so it transcodes. A server that still offers the file as is gives
// NoCompatibleStream, so the fallback never loops on the same source.
func (s *EmbyService) PrepareTranscode(ctx context.Context, itemID string, resume bool) (PrepareResult, error) {
	return s.PrepareSource(ctx, itemID, nil, resume, true, TrackPreferences{})
}

// PrepareVersion is `prepare_version`: for one version of an item with several;
// nil lets the server pick.
func (s *EmbyService) PrepareVersion(ctx context.Context, itemID string, mediaSourceID *string, resume, forceTranscode bool) (PrepareResult, error) {
	return s.PrepareSource(ctx, itemID, mediaSourceID, resume, forceTranscode, TrackPreferences{})
}

// Prepare is `prepare`: with the audio and subtitles picked from the user's
// preferences.
func (s *EmbyService) Prepare(ctx context.Context, itemID string, resume, forceTranscode bool, prefs TrackPreferences) (PrepareResult, error) {
	return s.PrepareSource(ctx, itemID, nil, resume, forceTranscode, prefs)
}

// PrepareSource is `prepare_source`: Prepare for one version of the item, or the
// server's pick with nil.
func (s *EmbyService) PrepareSource(ctx context.Context, itemID string, mediaSourceID *string, resume, forceTranscode bool, prefs TrackPreferences) (PrepareResult, error) {
	return s.prepareWith(ctx, itemID, mediaSourceID, resume, forceTranscode, prefs, false, nil)
}

// PrepareAtCap is `prepare_at_cap`: a new negotiation of the version playing at
// another bitrate cap, for this playback only: Réglages is left as it is.
// nil is « Maximale ». The plan starts at startTicks, the position reached.
func (s *EmbyService) PrepareAtCap(ctx context.Context, itemID, mediaSourceID string, cap *uint64, startTicks int64, prefs TrackPreferences) (PrepareResult, error) {
	if cap != nil && !slices.Contains(BitrateChoices, *cap) {
		return PrepareResult{}, NewSettings("débit de lecture non proposé")
	}
	id := mediaSourceID
	res, err := s.prepareWith(ctx, itemID, &id, false, false, prefs, true, cap)
	if err != nil {
		return PrepareResult{}, err
	}
	res.Plan.StartTicks = max(startTicks, 0)
	return res, nil
}

// StopEncoding is `stop_encoding`: stops the transcode of a replaced play session
// (`DELETE /Videos/ActiveEncodings`); the token stays in the header.
func (s *EmbyService) StopEncoding(ctx context.Context, playSessionID string) error {
	client, err := s.Client()
	if err != nil {
		return err
	}
	return s.expireOn401(client, client.StopActiveEncodings(ctx, playSessionID))
}

// prepareWith is `prepare_with`: PrepareSource, with cap in place of the cap of
// Réglages when capSet.
func (s *EmbyService) prepareWith(ctx context.Context, itemID string, mediaSourceID *string, resume, forceTranscode bool,
	prefs TrackPreferences, capSet bool, cap *uint64) (PrepareResult, error) {
	if mediaSourceID != nil && !isSourceID(*mediaSourceID) {
		return PrepareResult{}, NewInvalidResponse("version invalide")
	}
	client, userID, err := s.session()
	if err != nil {
		return PrepareResult{}, err
	}
	limit := s.streamingLimit(client)
	if capSet {
		limit.Cap = cloneU64(cap)
		limit.Bitrate = MaxStreamingBitrate
		if cap != nil {
			limit.Bitrate = *cap
		}
	}
	profile := DeviceProfileFor(limit.Bitrate)
	infoCh := async(func() (PlaybackInfoResponse, error) {
		return client.PlaybackInfo(ctx, userID, itemID, &profile, forceTranscode, mediaSourceID, nil)
	})
	itemCh := async(func() (BaseItemDto, error) { return client.Item(ctx, userID, itemID) })
	info, itemRes := <-infoCh, <-itemCh
	response, err := expire(s, client, info.v, info.err)
	if err != nil {
		return PrepareResult{}, err
	}
	plan, err := Plan(client.Server(), response, mediaSourceID)
	if err != nil {
		return PrepareResult{}, err
	}
	if forceTranscode && plan.Method != MethodTranscode {
		return PrepareResult{}, ErrNoCompatibleStream
	}
	choice := Apply(&plan, &prefs)
	otherAudio := choice.Audio != nil && !sameIndex(choice.Audio, plan.DefaultAudioStreamIndex)
	if otherAudio && plan.Method == MethodTranscode {
		// A transcode carries the audio the server picked; it takes another one only
		// with the source named.
		source := plan.MediaSourceID
		audio := *choice.Audio
		again, err := client.PlaybackInfo(ctx, userID, itemID, &profile, forceTranscode, &source, &audio)
		err = s.expireOn401(client, err)
		if err != nil {
			return PrepareResult{}, err
		}
		next, err := Plan(client.Server(), again, &source)
		if err != nil {
			return PrepareResult{}, err
		}
		if next.Method == MethodTranscode && next.MediaSourceID == plan.MediaSourceID {
			Apply(&next, &prefs)
			plan = next
		}
	}
	if forceTranscode {
		// The server only knows we refused; the real reason is the player's.
		reason := "échec de la lecture directe"
		plan.TranscodeReasons = &reason
	}
	var item *BaseItemDto
	dto, ierr := expire(s, client, itemRes.v, itemRes.err)
	switch {
	case ierr == nil && dto.ID == itemID:
		item = &dto
	case ierr == nil && resume:
		return PrepareResult{}, errOtherItem
	case ierr != nil && (resume || errors.Is(ierr, ErrSessionExpired)):
		return PrepareResult{}, ierr
	}
	if item != nil {
		plan.Title = playbackTitle(item)
	}
	plan.BitrateLimit = &limit
	if item != nil && resume {
		runtime := plan.RunTimeTicks
		if runtime == nil {
			runtime = item.RunTimeTicks
		}
		plan.StartTicks = 0
		if u := item.UserData; u != nil && !u.Played {
			if t := u.PlaybackPositionTicks; t > 0 && (runtime == nil || t < *runtime) {
				plan.StartTicks = t
			}
		}
	}
	headers := slices.Clone(plan.Headers)
	auth, err := client.StreamAuthHeaders()
	if err != nil {
		return PrepareResult{}, err
	}
	headers = append(headers, auth...)
	return PrepareResult{Plan: plan, Headers: headers}, nil
}

// ReportPlayback is `report_playback`: sends one playback report.
func (s *EmbyService) ReportPlayback(ctx context.Context, report Report) error {
	client, err := s.Client()
	if err != nil {
		return err
	}
	return s.expireOn401(client, client.ReportPlayback(ctx, report.Kind.Path(), report.Body))
}

// ResendStop is `resend_stop`: sends a lost stop report again, only while owner is
// still the open session: it must never reach another account or server.
func (s *EmbyService) ResendStop(ctx context.Context, owner *Client, report Report) error {
	client, err := s.Client()
	if err != nil {
		return err
	}
	if !client.SameToken(owner) {
		return ErrCancelled
	}
	// The server ignores the position of a stop once it closed the playback itself,
	// or with `Failed`; a progress report carries it and reopens the playback.
	if err := s.ReportPlayback(ctx, report.LateProgress()); err != nil {
		return err
	}
	return s.ReportPlayback(ctx, report)
}
