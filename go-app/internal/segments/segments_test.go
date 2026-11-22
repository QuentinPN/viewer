package segments

// Port of src-tauri/src/segments/tests.rs, same names (`Test_<name>`).

import (
	"context"
	"fmt"
	"math"
	"net"
	"net/http"
	"net/http/httptest"
	"reflect"
	"slices"
	"strings"
	"sync"
	"testing"
)

// Real answers of 2026-11-07 (docs/evidence/intros-generiques-2026-11-07.md).
const introdbTV = `{"imdb_id":"tt0903747","media_type":"tv","is_movie":false,"season":1,"episode":1,
  "intro":null,"recap":null,
  "outro":{"start_sec":3431,"end_sec":3500,"start_ms":3431000,"end_ms":3500000,"confidence":1,"submission_count":1,"updated_at":"2026-04-14T12:21:01.935Z"},
  "post_credits":null,"no_intro":true,"no_recap":true,"no_outro":false}`
const introdbEmpty = `{"imdb_id":"tt9999998","media_type":"movie","is_movie":true,"season":0,"episode":0,
  "intro":null,"recap":null,"outro":null,"post_credits":null,"no_intro":false,"no_recap":false,"no_outro":false}`

// Shape of IntroDB's documentation example (https://introdb.app).
const introdbIntro = `{"imdb_id":"tt0903747","media_type":"tv","is_movie":false,"season":1,"episode":1,
  "intro":{"start_sec":2.5,"end_sec":58,"start_ms":2500,"end_ms":58000,"confidence":0.92,"submission_count":14,"updated_at":"2026-09-12T21:03:17.409Z"},
  "recap":null,"outro":null,"post_credits":null,"no_intro":false,"no_recap":true,"no_outro":false}`
const theintrodbTV = `{"tvdb_id":71663,"type":"tv","season":1,"episode":1,
  "intro":[{"start_ms":null,"end_ms":24724}],
  "credits":[{"start_ms":1332112,"end_ms":1441100}]}`

// TheIntroDB's specification example for a film (theintrodb.org/openapi.yaml).
const theintrodbMovie = `{"tvdb_id":169,"type":"movie",
  "intro":[{"start_ms":null,"end_ms":23000}],
  "recap":[{"start_ms":25000,"end_ms":134000}],
  "credits":[{"start_ms":5801777,"end_ms":6371111},{"start_ms":6408000,"end_ms":null}],
  "preview":[{"start_ms":1680000,"end_ms":1740000}]}`
const theintrodb404 = `{"error":"no submissions for this media","code":"no_submissions"}`

func fp(v float64) *float64 { return &v }

func seg(t *testing.T, kind Kind, start float64, end *float64, source Source) Segment {
	t.Helper()
	s, ok := New(kind, start, end, source)
	if !ok {
		t.Fatalf("segment %v %v %v refused", kind, start, end)
	}
	return s
}

func episodeQuery() *Query {
	return &Query{
		IDs:        MediaIDsFromProviderIDs(map[string]string{"Tvdb": "71663", "IMDB": "tt0903747", "Tmdb": "2406"}),
		Episode:    &EpisodeNumber{Season: 1, Episode: 1},
		DurationMs: u64(1_441_100),
	}
}

func u64(v uint64) *uint64 { return &v }
func str(v string) *string { return &v }

func equal(t *testing.T, got, want any, msg ...any) {
	t.Helper()
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("%s\ngot:  %+v\nwant: %+v", fmt.Sprint(msg...), got, want)
	}
}

func Test_introdb_answers(t *testing.T) {
	found, ok := ParseIntroDB([]byte(introdbTV))
	if !ok {
		t.Fatal("unreadable")
	}
	equal(t, found.Segments, []Segment{seg(t, Credits, 3431, fp(3500), SourceIntroDB)})
	equal(t, found.Absent, []Kind{Intro})
	found, ok = ParseIntroDB([]byte(introdbIntro))
	if !ok {
		t.Fatal("unreadable")
	}
	equal(t, found.Segments, []Segment{seg(t, Intro, 2.5, fp(58), SourceIntroDB)})
	if len(found.Absent) != 0 {
		t.Fatal(found.Absent)
	}
	found, ok = ParseIntroDB([]byte(introdbEmpty))
	if !ok || len(found.Segments) != 0 || len(found.Absent) != 0 {
		t.Fatal(found, ok)
	}
	if _, ok := ParseIntroDB([]byte("<html>")); ok {
		t.Fatal("html read")
	}
}

func Test_theintrodb_answers_with_open_ends(t *testing.T) {
	found, ok := ParseTheIntroDB([]byte(theintrodbTV))
	if !ok {
		t.Fatal("unreadable")
	}
	equal(t, found.Segments, []Segment{
		seg(t, Intro, 0, fp(24.724), SourceTheIntroDB),
		seg(t, Credits, 1332.112, fp(1441.1), SourceTheIntroDB),
	})
	// Recap and preview are not offered; a null credits end runs to the end.
	found, ok = ParseTheIntroDB([]byte(theintrodbMovie))
	if !ok {
		t.Fatal("unreadable")
	}
	type ksE struct {
		k Kind
		s float64
		e *float64
	}
	var kinds []ksE
	for _, s := range found.Segments {
		kinds = append(kinds, ksE{s.Kind, s.Start, s.End})
	}
	equal(t, kinds, []ksE{{Intro, 0, fp(23)}, {Credits, 5801.777, fp(6371.111)}, {Credits, 6408, nil}})
	// "0 may be returned for no segment"; an intro without end is meaningless.
	none := `{"intro":[{"start_ms":0,"end_ms":0},{"start_ms":5000,"end_ms":null}],"credits":[{"start_ms":0,"end_ms":0}]}`
	found, ok = ParseTheIntroDB([]byte(none))
	if !ok || len(found.Segments) != 0 {
		t.Fatal(found, ok)
	}
}

func Test_invalid_ranges_are_dropped(t *testing.T) {
	s := SourceEmby
	refused := []struct {
		k     Kind
		start float64
		end   *float64
	}{
		{Intro, -1, fp(10)},
		{Intro, 10, fp(10.5)},
		{Intro, 20, fp(10)},
		{Intro, math.NaN(), fp(10)},
		{Credits, 10, fp(math.Inf(1))},
		{Credits, 30_000, nil},
		{Intro, 0, nil},
	}
	for _, c := range refused {
		if _, ok := New(c.k, c.start, c.end, s); ok {
			t.Fatalf("%+v accepted", c)
		}
	}
	if _, ok := New(Credits, 100, nil, s); !ok {
		t.Fatal("credits to the end refused")
	}
}

func Test_provider_ids_are_validated_whatever_the_case(t *testing.T) {
	parsed := MediaIDsFromProviderIDs(map[string]string{
		"IMDB": " tt0284718 ", "tmdb": "2406", "Tvdb": "74922", "Zap2It": "EP00446167",
	})
	equal(t, parsed, MediaIDs{Imdb: str("tt0284718"), Tmdb: u64(2406), Tvdb: u64(74922)})
	for _, bad := range [][2]string{
		{"Imdb", "tt12"},
		{"Imdb", "tt0284718&x=1"},
		{"Imdb", "nm0000001"},
		{"Tmdb", "-3"},
		{"Tmdb", "0"},
		{"Tvdb", "12a"},
		{"Tvdb", "999999999999"},
	} {
		if !MediaIDsFromProviderIDs(map[string]string{bad[0]: bad[1]}).IsEmpty() {
			t.Fatalf("%v accepted", bad)
		}
	}
}

func mustURL(t *testing.T, p Provider, q *Query) string {
	t.Helper()
	u, ok := p.URL(q)
	if !ok {
		t.Fatal("no URL")
	}
	return u
}

func Test_urls_carry_one_id_and_the_episode(t *testing.T) {
	q := episodeQuery()
	equal(t, mustURL(t, IntroDB(), q), "https://api.introdb.app/segments?imdb_id=tt0903747&season=1&episode=1")
	equal(t, mustURL(t, TheIntroDB(), q), "https://api.theintrodb.org/v4/media?tvdb_id=71663&duration_ms=1441100&season=1&episode=1")
	movie := &Query{IDs: MediaIDs{Imdb: str("tt23173980"), Tmdb: u64(1049574)}}
	equal(t, mustURL(t, IntroDB(), movie), "https://api.introdb.app/segments?imdb_id=tt23173980&is_movie=true")
	equal(t, mustURL(t, TheIntroDB(), movie), "https://api.theintrodb.org/v4/media?tmdb_id=1049574")
	noImdb := *movie
	noImdb.IDs.Imdb = nil
	if _, ok := IntroDB().URL(&noImdb); ok {
		t.Fatal("IntroDB without IMDb id")
	}
}

func Test_emby_markers_from_chapters(t *testing.T) {
	chapter := func(ticks int64, marker string) Marker {
		return Marker{StartPositionTicks: ticks, MarkerType: str(marker)}
	}
	chapters := []Marker{
		chapter(0, "Chapter"),
		chapter(300_000_000, "IntroStart"),
		chapter(900_000_000, "IntroEnd"),
		chapter(24_000_000_000, "CreditsStart"),
	}
	equal(t, EmbyMarkers(chapters), []Segment{
		seg(t, Intro, 30, fp(90), SourceEmby),
		seg(t, Credits, 2400, nil, SourceEmby),
	})
	// What the test server has: regular chapters only.
	var plain []Marker
	for i := range int64(9) {
		plain = append(plain, chapter(i*3_000_000_000, "Chapter"))
	}
	if len(EmbyMarkers(plain)) != 0 {
		t.Fatal("plain chapters gave segments")
	}
	if len(EmbyMarkers([]Marker{chapter(300_000_000, "IntroStart")})) != 0 {
		t.Fatal("intro without end")
	}
}

// fakeRequest is a request path and its lowercased header lines.
type fakeRequest struct {
	path    string
	headers []string
}

type route struct {
	prefix string
	status int
	body   string
}

// fakeService is `FakeService`: serves (status, body) by path prefix and records
// request lines and headers.
type fakeService struct {
	url  string
	mu   sync.Mutex
	reqs []fakeRequest
}

func startService(t *testing.T, routes ...route) *fakeService {
	t.Helper()
	f := &fakeService{}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var headers []string
		for name, values := range r.Header {
			for _, v := range values {
				headers = append(headers, strings.ToLower(name+": "+v))
			}
		}
		status, body := 404, "{}"
		for _, rt := range routes {
			if strings.HasPrefix(r.RequestURI, rt.prefix) {
				status, body = rt.status, rt.body
				break
			}
		}
		f.mu.Lock()
		f.reqs = append(f.reqs, fakeRequest{r.RequestURI, headers})
		f.mu.Unlock()
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Connection", "close")
		w.WriteHeader(status)
		_, _ = w.Write([]byte(body))
	}))
	t.Cleanup(srv.Close)
	f.url = srv.URL
	return f
}

func (f *fakeService) paths() []string {
	f.mu.Lock()
	defer f.mu.Unlock()
	out := []string{}
	for _, r := range f.reqs {
		out = append(out, r.path)
	}
	return out
}

func service(a, b *fakeService) *Service {
	return WithProviders("0.1.0", []Provider{IntroDBAt(a.url), TheIntroDBAt(b.url + "/v4")})
}

var bg = context.Background()

func Test_each_kind_comes_from_the_first_source_that_has_it(t *testing.T) {
	// IntroDB: credits, and confirmed no intro: TheIntroDB is not asked.
	a := startService(t, route{"/segments", 200, introdbTV})
	b := startService(t, route{"/v4/media", 200, theintrodbTV})
	svc := service(a, b)
	q := episodeQuery()
	found := svc.Lookup(bg, "k1", nil, q, true)
	equal(t, found, []Segment{seg(t, Credits, 3431, fp(3500), SourceIntroDB)})
	equal(t, a.paths(), []string{"/segments?imdb_id=tt0903747&season=1&episode=1"})
	equal(t, b.paths(), []string{})
	// No Emby header and the app's own user agent.
	headers := a.reqs[0].headers
	for _, h := range headers {
		if strings.HasPrefix(h, "x-emby") {
			t.Fatal(h)
		}
	}
	if !slices.Contains(headers, "user-agent: viewer/0.1.0") {
		t.Fatal(headers)
	}

	// IntroDB knows nothing: TheIntroDB gives both.
	a = startService(t, route{"/segments", 200, introdbEmpty})
	b = startService(t, route{"/v4/media", 200, theintrodbTV})
	found = service(a, b).Lookup(bg, "k2", nil, q, true)
	if len(found) != 2 {
		t.Fatal(found)
	}
	for _, s := range found {
		if s.Source != SourceTheIntroDB {
			t.Fatal(found)
		}
	}
	equal(t, b.paths(), []string{"/v4/media?tvdb_id=71663&duration_ms=1441100&season=1&episode=1"})

	// Emby's intro wins; only the credits come from outside.
	a = startService(t, route{"/segments", 200, introdbIntro})
	b = startService(t, route{"/v4/media", 200, theintrodbTV})
	emby := []Segment{seg(t, Intro, 30, fp(90), SourceEmby)}
	found = service(a, b).Lookup(bg, "k3", emby, q, true)
	type ks struct {
		k Kind
		s Source
	}
	var sources []ks
	for _, s := range found {
		sources = append(sources, ks{s.Kind, s.Source})
	}
	equal(t, sources, []ks{{Intro, SourceEmby}, {Credits, SourceTheIntroDB}})
}

func Test_nothing_leaves_the_app_when_external_is_off_or_emby_has_both(t *testing.T) {
	a := startService(t, route{"/segments", 200, introdbTV})
	b := startService(t, route{"/v4/media", 200, theintrodbTV})
	svc := service(a, b)
	q := episodeQuery()
	if got := svc.Lookup(bg, "off", nil, q, false); len(got) != 0 {
		t.Fatal(got)
	}
	both := []Segment{seg(t, Intro, 30, fp(90), SourceEmby), seg(t, Credits, 2400, nil, SourceEmby)}
	equal(t, svc.Lookup(bg, "both", slices.Clone(both), q, true), both)
	if got := svc.Lookup(bg, "noids", nil, nil, true); len(got) != 0 {
		t.Fatal(got)
	}
	if len(a.paths()) != 0 || len(b.paths()) != 0 {
		t.Fatal(a.paths(), b.paths())
	}
}

func Test_only_definitive_answers_are_cached(t *testing.T) {
	q := episodeQuery()
	// 404 at TheIntroDB is definitive: cached, nothing asked again.
	a := startService(t, route{"/segments", 200, introdbEmpty})
	b := startService(t, route{"/v4/media", 404, theintrodb404})
	svc := service(a, b)
	if got := svc.Lookup(bg, "item", nil, q, true); len(got) != 0 {
		t.Fatal(got)
	}
	if got, ok := svc.Cached("item"); !ok || len(got) != 0 {
		t.Fatal(got, ok)
	}

	// 429 and 5xx are retried at the next playback; the other source still counts.
	a = startService(t, route{"/segments", 429, `{"error":"rate"}`})
	b = startService(t, route{"/v4/media", 200, theintrodbTV})
	svc = service(a, b)
	if got := svc.Lookup(bg, "item", nil, q, true); len(got) != 2 {
		t.Fatal(got)
	}
	if _, ok := svc.Cached("item"); ok {
		t.Fatal("cached after a 429")
	}
	a = startService(t, route{"/segments", 503, ""})
	svc = service(a, b)
	svc.Lookup(bg, "item", nil, q, true)
	if _, ok := svc.Cached("item"); ok {
		t.Fatal("cached after a 503")
	}
}

func Test_unreachable_or_hostile_services_give_nothing(t *testing.T) {
	q := episodeQuery()
	// Port closed.
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	closed := "http://" + l.Addr().String()
	_ = l.Close()
	svc := WithProviders("0.1.0", []Provider{IntroDBAt(closed), TheIntroDBAt(closed)})
	if got := svc.Lookup(bg, "k", nil, q, true); len(got) != 0 {
		t.Fatal(got)
	}
	if _, ok := svc.Cached("k"); ok {
		t.Fatal("cached while unreachable")
	}

	// A redirect is not followed, an oversized or malformed body is refused.
	big := `{"intro":[` + strings.Repeat("{}", 40_000) + `]}`
	a := startService(t, route{"/segments", 302, ""})
	b := startService(t, route{"/v4/media", 200, big})
	svc = service(a, b)
	if got := svc.Lookup(bg, "k", nil, q, true); len(got) != 0 {
		t.Fatal(got)
	}
	a = startService(t, route{"/segments", 200, "not json"})
	svc = service(a, b)
	if got := svc.Lookup(bg, "k", nil, q, true); len(got) != 0 {
		t.Fatal(got)
	}
}

// Go only: the answers are read with serde's rules, so an answer serde refuses
// stays transient (never cached as definitive).
func Test_go_answers_are_read_with_serde_rules(t *testing.T) {
	for _, body := range []string{
		`{"no_intro":null}`,
		`{"intro":{"start_sec":1}}`,
		`{"intro":{"start_sec":"1","end_sec":5}}`,
		`{"intro":{"start_sec":1,"end_sec":5},"intro":null}`,
		`{"Intro":null,"no_intro":1}`,
		`{"intro":{"start_sec":1e999,"end_sec":5}}`,
		`[]`,
		`{} x`,
	} {
		if _, ok := ParseIntroDB([]byte(body)); ok {
			t.Errorf("IntroDB read %s", body)
		}
	}
	for _, body := range []string{
		`{"intro":null}`,
		`{"intro":[{"start_ms":1.0,"end_ms":5000}]}`,
		`{"credits":[{"start_ms":1,"end_ms":5e3}]}`,
		`{"credits":[{"end_ms":"5000"}]}`,
		`{"credits":[{"end_ms":9223372036854775808}]}`,
		`{"credits":[],"credits":[]}`,
		`{"credits":{}}`,
	} {
		if _, ok := ParseTheIntroDB([]byte(body)); ok {
			t.Errorf("TheIntroDB read %s", body)
		}
	}
	// Unknown keys, other cases and absent fields are fine.
	if f, ok := ParseIntroDB([]byte(`{"INTRO":5,"x":[1,1],"x":2}`)); !ok || len(f.Segments)+len(f.Absent) != 0 {
		t.Error(f, ok)
	}
	if f, ok := ParseTheIntroDB([]byte(`{"credits":[{"start_ms":-0,"end_ms":null}]}`)); !ok || len(f.Segments) != 1 {
		t.Error(f, ok)
	}
	// A cached value is a copy, and Lookup never returns nil.
	svc := WithProviders("0", nil)
	got := svc.Lookup(bg, "k", nil, nil, true)
	if got == nil {
		t.Fatal("nil segments")
	}
	if c, ok := svc.Cached("k"); !ok || c == nil {
		t.Fatal(c, ok)
	}
	// -0.0 sorts before 0.0, as with total_cmp, and equal starts keep their order.
	a := Segment{Kind: Credits, Start: 0, Source: SourceEmby}
	b := Segment{Kind: Intro, Start: math.Copysign(0, -1), End: fp(5), Source: SourceTheIntroDB}
	got = svc.Lookup(bg, "z", []Segment{a, b}, nil, false)
	if got[0].Source != SourceTheIntroDB {
		t.Fatal(got)
	}
}
