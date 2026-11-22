// Package segments is the port of src-tauri/src/segments: intro and credits segments
// for the player (ADR 0030). Emby's chapter markers come first, then IntroDB, then
// TheIntroDB; each kind comes from the first source that gives it. Only the film's
// or series' provider ids leave the app, never a token, a server address or a title.
//
// The package does not import internal/emby (emby imports it): Emby's chapters reach
// EmbyMarkers as Marker values, converted by the emby package.
package segments

import (
	"context"
	"fmt"
	"math"
	"net"
	"net/http"
	"slices"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	ticksPerSecond = 10_000_000.0
	// minSegmentSeconds: shorter than this is not worth a button.
	minSegmentSeconds = 1.0
	// maxPositionSeconds: TheIntroDB refuses longer submissions; anything beyond is malformed.
	maxPositionSeconds = 6.0 * 3600.0
	// cacheEntries: items kept in memory; the cache is emptied when full.
	cacheEntries = 256
)

// Kind is `SegmentKind`, written camelCase.
type Kind string

const (
	Intro   Kind = "intro"
	Credits Kind = "credits"
)

// Source is `SegmentSource`, written camelCase.
type Source string

const (
	SourceEmby       Source = "emby"
	SourceIntroDB    Source = "introDb"
	SourceTheIntroDB Source = "theIntroDb"
)

// Segment is a range to skip, in seconds of the file. A nil End runs to the end of
// the file (credits only). Field order and names are serde's (`rename_all =
// "camelCase"`); `end` is an Option without skip, so written null.
type Segment struct {
	Kind   Kind     `json:"kind"`
	Start  float64  `json:"start"`
	End    *float64 `json:"end"`
	Source Source   `json:"source"`
}

// New is `Segment::new`: false for a range that cannot be skipped: negative,
// reversed, too short, beyond six hours, or an intro with no end.
func New(kind Kind, start float64, end *float64, source Source) (Segment, bool) {
	valid := func(t float64) bool {
		return !math.IsNaN(t) && !math.IsInf(t, 0) && t >= 0 && t <= maxPositionSeconds
	}
	if !valid(start) {
		return Segment{}, false
	}
	switch {
	case end != nil && (!valid(*end) || *end-start < minSegmentSeconds):
		return Segment{}, false
	case end == nil && kind == Intro:
		return Segment{}, false
	}
	var e *float64
	if end != nil {
		v := *end
		e = &v
	}
	return Segment{Kind: kind, Start: start, End: e, Source: source}, true
}

// MediaIDs are the external ids of a film or series, validated before they reach a URL.
type MediaIDs struct {
	// Imdb is `tt` and 7 or 8 digits.
	Imdb *string
	Tmdb *uint64
	Tvdb *uint64
}

// MediaIDsFromProviderIDs is `MediaIds::from_provider_ids`: from Emby's
// `ProviderIds`, whatever the key case (`Imdb`, `IMDB`).
func MediaIDsFromProviderIDs(ids map[string]string) MediaIDs {
	get := func(name string) (string, bool) {
		for k, v := range ids {
			if asciiEqualFold(k, name) {
				return strings.TrimSpace(v), true
			}
		}
		return "", false
	}
	number := func(name string) *uint64 {
		v, ok := get(name)
		if !ok || v == "" || !allDigits(v) {
			return nil
		}
		n, err := strconv.ParseUint(v, 10, 64)
		if err != nil || n < 1 || n > 100_000_000 {
			return nil
		}
		return &n
	}
	var out MediaIDs
	if v, ok := get("Imdb"); ok {
		if d, found := strings.CutPrefix(v, "tt"); found && len(d) >= 7 && len(d) <= 8 && allDigits(d) {
			out.Imdb = &v
		}
	}
	out.Tmdb = number("Tmdb")
	out.Tvdb = number("Tvdb")
	return out
}

// IsEmpty is `is_empty`.
func (m MediaIDs) IsEmpty() bool { return m.Imdb == nil && m.Tmdb == nil && m.Tvdb == nil }

// allDigits: every byte an ASCII digit (true for "", callers check emptiness).
func allDigits(s string) bool {
	for i := 0; i < len(s); i++ {
		if s[i] < '0' || s[i] > '9' {
			return false
		}
	}
	return true
}

// asciiEqualFold is Rust's `eq_ignore_ascii_case`.
func asciiEqualFold(a, b string) bool {
	if len(a) != len(b) {
		return false
	}
	for i := 0; i < len(a); i++ {
		x, y := a[i], b[i]
		if 'A' <= x && x <= 'Z' {
			x += 'a' - 'A'
		}
		if 'A' <= y && y <= 'Z' {
			y += 'a' - 'A'
		}
		if x != y {
			return false
		}
	}
	return true
}

// totalCmp is Rust's `f64::total_cmp`.
func totalCmp(a, b float64) int {
	x, y := int64(math.Float64bits(a)), int64(math.Float64bits(b))
	x ^= int64(uint64(x>>63) >> 1)
	y ^= int64(uint64(y>>63) >> 1)
	switch {
	case x < y:
		return -1
	case x > y:
		return 1
	}
	return 0
}

// EpisodeNumber is the season and episode numbers of an episode.
type EpisodeNumber struct {
	Season  uint32
	Episode uint32
}

// Query is `SegmentQuery`: what the external services are asked, a film, or an
// episode of a series by the series' ids.
type Query struct {
	IDs MediaIDs
	// Episode: season and episode numbers, for an episode.
	Episode *EpisodeNumber
	// DurationMs: length of the file, which picks the release version at TheIntroDB.
	DurationMs *uint64
}

// Marker is the part of Emby's `ChapterDto` the markers read.
type Marker struct {
	StartPositionTicks int64
	MarkerType         *string
}

// EmbyMarkers is `emby_markers`: segments from Emby's chapter markers,
// `IntroStart`–`IntroEnd`, and `CreditsStart` to the end of the file.
func EmbyMarkers(chapters []Marker) []Segment {
	at := func(marker string) (float64, bool) {
		for _, c := range chapters {
			if c.MarkerType != nil && *c.MarkerType == marker {
				return float64(c.StartPositionTicks) / ticksPerSecond, true
			}
		}
		return 0, false
	}
	out := []Segment{}
	start, okStart := at("IntroStart")
	end, okEnd := at("IntroEnd")
	if okStart && okEnd {
		if s, ok := New(Intro, start, &end, SourceEmby); ok {
			out = append(out, s)
		}
	}
	if start, ok := at("CreditsStart"); ok {
		if s, ok := New(Credits, start, nil, SourceEmby); ok {
			out = append(out, s)
		}
	}
	return out
}

// Found is one source's answer: its segments, and the kinds it confirmed the item
// has none of (IntroDB's `no_intro`, `no_outro`).
type Found struct {
	Segments []Segment
	Absent   []Kind
}

// errTransient is `Transient`: a failure worth retrying at the next playback
// (network, timeout, 429, 5xx or an unreadable answer). Never shown to the user.
var errTransient = fmt.Errorf("segments : échec passager")

// Service is `SegmentService`: asks the sources in order and keeps definitive
// answers in memory. Safe for concurrent use.
type Service struct {
	http      *http.Client
	providers []Provider
	mu        sync.Mutex
	cache     map[string][]Segment
}

// NewService is `SegmentService::new`: IntroDB, then TheIntroDB.
func NewService(version string) *Service {
	return WithProviders(version, []Provider{IntroDB(), TheIntroDB()})
}

// userAgent sets the app's own User-Agent on every request.
type userAgent struct {
	value string
	next  http.RoundTripper
}

func (u userAgent) RoundTrip(r *http.Request) (*http.Response, error) {
	r = r.Clone(r.Context())
	r.Header.Set("User-Agent", u.value)
	return u.next.RoundTrip(r)
}

// WithProviders is `with_providers`. The client is separate from Emby's: no Emby
// header, no redirect, TLS validated, 3 s to connect, 4 s per request. As reqwest
// without its `gzip` feature, no compression is asked.
func WithProviders(version string, providers []Provider) *Service {
	dialer := &net.Dialer{Timeout: 3 * time.Second}
	transport := &http.Transport{
		Proxy:               http.ProxyFromEnvironment,
		DialContext:         dialer.DialContext,
		TLSHandshakeTimeout: 3 * time.Second,
		ForceAttemptHTTP2:   true,
		DisableCompression:  true,
		MaxIdleConns:        4,
		IdleConnTimeout:     90 * time.Second,
	}
	return &Service{
		http: &http.Client{
			Transport:     userAgent{value: "Viewer/" + version, next: transport},
			Timeout:       4 * time.Second,
			CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
		},
		providers: providers,
		cache:     map[string][]Segment{},
	}
}

// Cached is `cached`: the segments kept for key.
func (s *Service) Cached(key string) ([]Segment, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	found, ok := s.cache[key]
	if !ok {
		return nil, false
	}
	return slices.Clone(found), true
}

// Lookup is `lookup`: Emby's markers, completed by the external sources when
// external allows them and the item has ids. A source is skipped once every kind is
// settled. Only an answer without a passing failure is kept. Never nil.
func (s *Service) Lookup(ctx context.Context, key string, markers []Segment, query *Query, external bool) []Segment {
	segments := append([]Segment{}, markers...)
	settled := map[Kind]bool{}
	for _, m := range segments {
		settled[m.Kind] = true
	}
	complete := true
	if query != nil && external {
		for _, p := range s.providers {
			if len(settled) == 2 {
				break
			}
			found, err := p.Fetch(ctx, s.http, query)
			if err != nil {
				complete = false
				continue
			}
			var fresh []Segment
			for _, seg := range found.Segments {
				if !settled[seg.Kind] {
					fresh = append(fresh, seg)
				}
			}
			for _, seg := range fresh {
				settled[seg.Kind] = true
			}
			for _, k := range found.Absent {
				settled[k] = true
			}
			segments = append(segments, fresh...)
		}
	}
	// `sort_by` is stable, and `total_cmp` puts -0.0 before 0.0.
	slices.SortStableFunc(segments, func(a, b Segment) int { return totalCmp(a.Start, b.Start) })
	if complete {
		s.mu.Lock()
		if len(s.cache) >= cacheEntries {
			clear(s.cache)
		}
		s.cache[key] = slices.Clone(segments)
		s.mu.Unlock()
	}
	return segments
}
