package segments

// IntroDB and TheIntroDB read endpoints, both anonymous. Port of
// segments/providers.rs. Contracts checked against their docs and real answers
// captured on 2026-11-07.

import (
	"context"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"

	"viewer/internal/formenc"
)

// maxBodyBytes: larger answers are refused; a real one is under 1 KiB.
const maxBodyBytes = 64 * 1024

type api int

const (
	apiIntroDB api = iota
	apiTheIntroDB
)

// Provider is `SegmentProvider`: one external source and where it answers.
type Provider struct {
	api  api
	base string
}

// IntroDB is `SegmentProvider::introdb`.
func IntroDB() Provider { return IntroDBAt("https://api.introdb.app") }

// TheIntroDB is `SegmentProvider::theintrodb`.
func TheIntroDB() Provider { return TheIntroDBAt("https://api.theintrodb.org/v4") }

// IntroDBAt is `introdb_at`.
func IntroDBAt(base string) Provider {
	return Provider{api: apiIntroDB, base: strings.TrimRight(base, "/")}
}

// TheIntroDBAt is `theintrodb_at`.
func TheIntroDBAt(base string) Provider {
	return Provider{api: apiTheIntroDB, base: strings.TrimRight(base, "/")}
}

type param struct{ name, value string }

// URL is `url`: the request URL, or false when the item lacks an id this source
// takes. Only one id is sent.
func (p Provider) URL(q *Query) (string, bool) {
	var params []param
	var path string
	switch p.api {
	case apiIntroDB:
		// IntroDB takes the IMDb id only.
		if q.IDs.Imdb == nil {
			return "", false
		}
		params = append(params, param{"imdb_id", *q.IDs.Imdb})
		if q.Episode == nil {
			params = append(params, param{"is_movie", "true"})
		}
		path = "/segments"
	default:
		// TheIntroDB resolves TVDB first, then TMDB, then IMDb.
		switch ids := q.IDs; {
		case ids.Tvdb != nil:
			params = append(params, param{"tvdb_id", strconv.FormatUint(*ids.Tvdb, 10)})
		case ids.Tmdb != nil:
			params = append(params, param{"tmdb_id", strconv.FormatUint(*ids.Tmdb, 10)})
		case ids.Imdb != nil:
			params = append(params, param{"imdb_id", *ids.Imdb})
		default:
			return "", false
		}
		if q.DurationMs != nil {
			params = append(params, param{"duration_ms", strconv.FormatUint(*q.DurationMs, 10)})
		}
		path = "/media"
	}
	if q.Episode != nil {
		params = append(params,
			param{"season", strconv.FormatUint(uint64(q.Episode.Season), 10)},
			param{"episode", strconv.FormatUint(uint64(q.Episode.Episode), 10)})
	}
	u, err := url.Parse(p.base + path)
	if err != nil || u.Scheme == "" || u.Host == "" {
		return "", false
	}
	pairs := make([][2]string, len(params))
	for i, kv := range params {
		pairs[i] = [2]string{kv.name, kv.value}
	}
	u.RawQuery = formenc.Serialize(pairs)
	u.Fragment = ""
	return u.String(), true
}

// Fetch is `fetch`. An item without an id this source takes, a missing item or a
// refused query (4xx but 429) are definitive answers; anything else that fails is
// transient (errTransient).
func (p Provider) Fetch(ctx context.Context, client *http.Client, q *Query) (Found, error) {
	target, ok := p.URL(q)
	if !ok {
		return Found{}, nil
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, target, nil)
	if err != nil {
		return Found{}, errTransient
	}
	req.Header.Set("Accept", "application/json")
	res, err := client.Do(req)
	if err != nil {
		return Found{}, errTransient
	}
	defer func() { _ = res.Body.Close() }()
	switch s := res.StatusCode; {
	case s == http.StatusOK:
	case s == http.StatusTooManyRequests:
		return Found{}, errTransient
	case s >= 400 && s <= 499:
		// A missing item, or a query it refuses: asking again will not help.
		return Found{}, nil
	default:
		return Found{}, errTransient
	}
	body, err := io.ReadAll(io.LimitReader(res.Body, maxBodyBytes+1))
	if err != nil || len(body) > maxBodyBytes {
		return Found{}, errTransient
	}
	var found Found
	if p.api == apiIntroDB {
		found, ok = ParseIntroDB(body)
	} else {
		found, ok = ParseTheIntroDB(body)
	}
	if !ok {
		return Found{}, errTransient
	}
	return found, nil
}

// ParseIntroDB is `parse_introdb`, `GET /segments`: `null` is "not timed yet",
// unless `no_*` confirms there is none.
func ParseIntroDB(body []byte) (Found, bool) {
	fields, ok := object(body, "intro", "outro", "no_intro", "no_outro")
	if !ok {
		return Found{}, false
	}
	type span struct{ start, end float64 }
	segment := func(name string) (*span, bool) {
		raw, present := fields[name]
		if !present || isNull(raw) {
			return nil, true
		}
		f, ok := object(raw, "start_sec", "end_sec")
		if !ok {
			return nil, false
		}
		start, ok1 := f64(f["start_sec"])
		end, ok2 := f64(f["end_sec"])
		if !ok1 || !ok2 {
			return nil, false
		}
		return &span{start, end}, true
	}
	intro, ok1 := segment("intro")
	outro, ok2 := segment("outro")
	noIntro, ok3 := boolDefault(fields, "no_intro")
	noOutro, ok4 := boolDefault(fields, "no_outro")
	if !ok1 || !ok2 || !ok3 || !ok4 {
		return Found{}, false
	}
	var found Found
	for _, c := range []struct {
		kind Kind
		s    *span
	}{{Intro, intro}, {Credits, outro}} {
		if c.s == nil {
			continue
		}
		end := c.s.end
		if seg, ok := New(c.kind, c.s.start, &end, SourceIntroDB); ok {
			found.Segments = append(found.Segments, seg)
		}
	}
	if noIntro {
		found.Absent = append(found.Absent, Intro)
	}
	if noOutro {
		found.Absent = append(found.Absent, Credits)
	}
	return found, true
}

// ParseTheIntroDB is `parse_theintrodb`, `GET /v4/media`: a null start is the
// beginning of the file, a null end its end; an intro with no end, or a 0 end ("no
// segment"), is dropped.
func ParseTheIntroDB(body []byte) (Found, bool) {
	fields, ok := object(body, "intro", "credits")
	if !ok {
		return Found{}, false
	}
	var found Found
	for _, c := range []struct {
		name string
		kind Kind
	}{{"intro", Intro}, {"credits", Credits}} {
		raw, present := fields[c.name]
		if !present {
			continue
		}
		items, ok := array(raw)
		if !ok {
			return Found{}, false
		}
		for _, item := range items {
			f, ok := object(item, "start_ms", "end_ms")
			if !ok {
				return Found{}, false
			}
			start, ok1 := optI64(f, "start_ms")
			end, ok2 := optI64(f, "end_ms")
			if !ok1 || !ok2 {
				return Found{}, false
			}
			if end != nil && *end == 0 {
				continue
			}
			s := 0.0
			if start != nil {
				s = float64(*start) / 1000
			}
			var e *float64
			if end != nil {
				v := float64(*end) / 1000
				e = &v
			}
			if seg, ok := New(c.kind, s, e, SourceTheIntroDB); ok {
				found.Segments = append(found.Segments, seg)
			}
		}
	}
	return found, true
}
