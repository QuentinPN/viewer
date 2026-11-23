package stremio

// « Autres sources » of an Emby film or episode: a query lists the addons that
// serve it, each addon is then asked for its streams, and a stream is played by
// its id. The table of queries keeps, in memory only, the addons (their address)
// and the streams (URL and headers) behind those ids; the UI gets opaque random
// ids, names, descriptions and sizes.

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"log/slog"

	"viewer/internal/emby"
	"viewer/internal/redact"
)

// maxQueries is how many queries the table keeps; a new one drops the oldest.
const maxQueries = 4

// ItemResolver reads what the addons are asked about an Emby item
// (emby.EmbyService.ExternalRef).
type ItemResolver func(ctx context.Context, itemID string) (emby.ExternalRef, error)

// SourceAddon is an addon of a query, as the UI sees it.
type SourceAddon struct {
	ID   string `json:"id"`
	Name string `json:"name"`
}

// Sources is the answer of stremio_sources: the query and its addons, in
// collection order.
type Sources struct {
	Query  string        `json:"query"`
	Addons []SourceAddon `json:"addons"`
}

// StreamDescriptor is a stream as the UI sees it: never its URL or headers.
type StreamDescriptor struct {
	ID          string `json:"id"`
	Name        string `json:"name"`
	Description string `json:"description"`
	// Size in bytes, nil when unknown.
	Size *int64 `json:"size"`
}

// AddonStreams is the answer of stremio_addon_streams.
type AddonStreams struct {
	Streams     []StreamDescriptor `json:"streams"`
	Unsupported int                `json:"unsupported"`
}

// Selected is a stream picked to play, with the Emby item of its query and that
// item's player title. It prints masked and never serializes.
type Selected struct {
	ItemID string
	Title  *emby.PlaybackTitle
	Stream Stream
}

func (s Selected) String() string               { return redact.Masked("StremioSelection") }
func (s Selected) GoString() string             { return s.String() }
func (s Selected) Format(f fmt.State, _ rune)   { redact.WriteMasked(f, "StremioSelection") }
func (s Selected) MarshalJSON() ([]byte, error) { return redact.RefuseMarshal("StremioSelection") }

// Target is what the addons are asked for ref: a film by its IMDb id, an episode
// by its series' IMDb id and its numbers. ErrNoImdb without an IMDb id (or an
// episode without its numbers); ErrInvalidID for any other item, or an episode
// the addons cannot name (season 0).
func Target(ref emby.ExternalRef) (ContentType, string, error) {
	var t ContentType
	switch ref.ItemType {
	case "Movie":
		t = Movie
	case "Episode":
		t = Series
	default:
		return "", "", ErrInvalidID
	}
	if ref.IDs.Imdb == nil || (t == Series && ref.Episode == nil) {
		return "", "", ErrNoImdb
	}
	var season, episode int
	if t == Series {
		season, episode = int(ref.Episode.Season), int(ref.Episode.Episode)
	}
	id, err := StreamID(*ref.IDs.Imdb, season, episode)
	if err != nil {
		return "", "", err
	}
	return t, id, nil
}

// queryAddon is an addon of a query and its opaque id.
type queryAddon struct {
	id    string
	addon Addon
}

// queryStream is a stream of a query and the addon that gave it.
type queryStream struct {
	addonID string
	stream  Stream
}

// query is one « Autres sources » list.
type query struct {
	itemID  string
	title   *emby.PlaybackTitle
	ctype   ContentType
	id      string
	addons  []queryAddon
	streams map[string]queryStream
}

func (q *query) addon(id string) (Addon, bool) {
	for _, a := range q.addons {
		if a.id == id {
			return a.addon, true
		}
	}
	return Addon{}, false
}

// queryTable is the last maxQueries queries, oldest first. Guarded by Service.mu.
type queryTable struct {
	order []string
	byID  map[string]*query
}

func (t *queryTable) add(id string, q *query) {
	if t.byID == nil {
		t.byID = map[string]*query{}
	}
	if len(t.order) >= maxQueries {
		delete(t.byID, t.order[0])
		t.order = t.order[1:]
	}
	t.order = append(t.order, id)
	t.byID[id] = q
}

// newID is prefix and 128 random bits in hex: never derived from a URL.
func newID(prefix string) string {
	var b [16]byte
	_, _ = rand.Read(b[:]) // crypto/rand.Read never fails (Go 1.24+)
	return prefix + hex.EncodeToString(b[:])
}

// Sources opens a query on the Emby item: the account's addons that serve it, in
// collection order. The account is checked before Emby is read.
func (s *Service) Sources(ctx context.Context, itemID string, resolve ItemResolver) (Sources, error) {
	s.mu.Lock()
	gen := s.st.gen
	s.mu.Unlock()
	addons, err := s.collection(ctx)
	if err != nil {
		if kindOf(err) == "" {
			return Sources{}, vaultError(err)
		}
		return Sources{}, onPage(err)
	}
	ref, err := resolve(ctx, itemID)
	if err != nil {
		return Sources{}, err
	}
	ctype, id, err := Target(ref)
	if err != nil {
		return Sources{}, onPage(err)
	}
	q := &query{itemID: itemID, title: ref.Title, ctype: ctype, id: id, streams: map[string]queryStream{}}
	out := Sources{Query: newID("query_"), Addons: []SourceAddon{}}
	for _, a := range addons {
		if a.Serves(ctype, id) {
			qa := queryAddon{id: newID("addon_"), addon: a}
			q.addons = append(q.addons, qa)
			out.Addons = append(out.Addons, SourceAddon{ID: qa.id, Name: a.Name})
		}
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.st.gen != gen {
		// Signed in or out meanwhile: these addons belong to another session.
		return Sources{}, ErrExpired
	}
	s.st.queries.add(out.Query, q)
	return out, nil
}

// AddonStreams asks one addon of a query for its streams; they replace those it
// gave before. ErrExpired once the query is gone; an addon that fails is
// unavailable (`<Addon> : indisponible.`).
func (s *Service) AddonStreams(ctx context.Context, queryID, addonID string) (AddonStreams, error) {
	s.mu.Lock()
	gen, q := s.st.gen, s.st.queries.byID[queryID]
	var addon Addon
	ok := q != nil
	if ok {
		addon, ok = q.addon(addonID)
	}
	s.mu.Unlock()
	if !ok {
		return AddonStreams{}, ErrExpired
	}
	res, err := s.client.Streams(ctx, addon, q.ctype, q.id)
	if err != nil {
		slog.Info("Stremio addon streams unavailable", "addon", addon.Name, "kind", string(kindOf(err)))
		return AddonStreams{}, onPage(err)
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.st.gen != gen || s.st.queries.byID[queryID] != q {
		return AddonStreams{}, ErrExpired
	}
	for id, st := range q.streams {
		if st.addonID == addonID {
			delete(q.streams, id)
		}
	}
	out := AddonStreams{Streams: make([]StreamDescriptor, 0, len(res.Streams)), Unsupported: res.Unsupported}
	for _, st := range res.Streams {
		d := StreamDescriptor{ID: newID("stream_"), Name: st.Name, Description: st.Description}
		if st.Size > 0 {
			size := st.Size
			d.Size = &size
		}
		q.streams[d.ID] = queryStream{addonID: addonID, stream: st}
		out.Streams = append(out.Streams, d)
	}
	return out, nil
}

// Selected is the stream behind streamID, ErrExpired once its query is gone.
func (s *Service) Selected(streamID string) (Selected, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, q := range s.st.queries.byID {
		if st, ok := q.streams[streamID]; ok {
			return Selected{ItemID: q.itemID, Title: q.title, Stream: st.stream}, nil
		}
	}
	return Selected{}, ErrExpired
}
