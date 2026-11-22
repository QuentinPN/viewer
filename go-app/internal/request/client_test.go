package request

// Client tests of src-tauri/src/request/tests.rs (first part), same names.

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"reflect"
	"strings"
	"testing"

	"viewer/internal/emby"
)

var bg = context.Background()

// assertErr checks err is exactly want (kind and payload), as Rust's assert_eq!.
func assertErr(t *testing.T, err error, want RequestError) {
	t.Helper()
	var got RequestError
	if !errors.As(err, &got) {
		t.Fatalf("erreur %v (%T), attendu %#v", err, err, want)
	}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("erreur %#v, attendu %#v", got, want)
	}
}

func assertKind(t *testing.T, err error, want ErrorKind) RequestError {
	t.Helper()
	var got RequestError
	if !errors.As(err, &got) || got.Kind != want {
		t.Fatalf("erreur %v, attendu le kind %s", err, want)
	}
	return got
}

func parse[T any](t *testing.T, name string) T {
	t.Helper()
	var v T
	if err := decodeStrict([]byte(fixture(t, name)), &v); err != nil {
		t.Fatalf("%s: %v", name, err)
	}
	return v
}

func Test_parses_every_fixture(t *testing.T) {
	ok := parse[ApiVersion](t, "version-ok")
	if ok.APIVersion != "1.0.0" {
		t.Fatal(ok.APIVersion)
	}
	parse[ApiVersion](t, "version-old")
	session := parse[EmbyTokenSession](t, "emby-token")
	if session.Viewer.Role != RoleMember || !session.Viewer.Capabilities.CanRequestMovies {
		t.Fatalf("%+v", session.Viewer)
	}
	sessions := parse[SessionList](t, "sessions")
	if len(sessions.Sessions) != 3 || !sessions.Sessions[0].Current {
		t.Fatalf("%+v", sessions)
	}
	discover := parse[DiscoverResponse](t, "discover")
	if len(discover.Rows) == 0 {
		t.Fatal("aucune rangée")
	}
	parse[PagedMedia](t, "paged-media")
	parse[MediaSearchResults](t, "search")
	movie := parse[MediaDetails](t, "media-movie")
	if movie.MediaType != MediaMovie || movie.EmbyItemID != nil || movie.Status != nil {
		t.Fatalf("%+v", movie)
	}
	tv := parse[MediaDetails](t, "media-tv")
	if tv.Status == nil || *tv.Status != "partial" {
		t.Fatalf("status %v", tv.Status)
	}
	if len(tv.Seasons) != 6 || tv.Seasons[1].Status == nil || *tv.Seasons[1].Status != "available" || tv.Seasons[5].AirDate != nil {
		t.Fatalf("%+v", tv.Seasons)
	}
	parse[RequestQuota](t, "quota")
	parse[RequestView](t, "request-view")
	page := parse[RequestPage](t, "request-page")
	if len(page.Results) == 0 {
		t.Fatal("aucune demande")
	}
	detail := parse[RequestDetail](t, "request-detail")
	if detail.Status != "approved" || len(detail.Events) != 3 {
		t.Fatalf("%+v", detail)
	}
	var e map[string]any
	if err := json.Unmarshal([]byte(fixture(t, "error")), &e); err != nil || e["error"] != "Quota de films atteint" {
		t.Fatal(e, err)
	}
}

func Test_normalizes_address(t *testing.T) {
	for in, want := range map[string]string{
		" request.exemple.fr/ ":            "https://request.exemple.fr",
		"https://request.exemple.fr:8445/": "https://request.exemple.fr:8445",
		"request.exemple.fr:8445":          "https://request.exemple.fr:8445",
	} {
		if got, err := NormalizeAddress(in); err != nil || got != want {
			t.Errorf("%q : %q %v, attendu %q", in, got, err, want)
		}
	}
	for _, local := range []string{
		"http://127.0.0.1:3000",
		"http://10.0.0.5",
		"http://172.20.1.1",
		"http://192.168.1.14:8445",
		"http://nas.local",
		"http://localhost:5173",
	} {
		if got, err := NormalizeAddress(local); err != nil || got != local {
			t.Errorf("%q : %q %v", local, got, err)
		}
	}
	for _, bad := range []string{
		"ftp://x",
		"http://request.exemple.fr",
		"http://8.8.8.8",
		"http://172.32.0.1",
		"https://user:pw@request.exemple.fr",
		"https://request.exemple.fr/?a=b",
		"",
		"   ",
		"request exemple.fr",
	} {
		_, err := NormalizeAddress(bad)
		if !reflect.DeepEqual(err, error(ErrInvalidAddress)) {
			t.Errorf("%q : %v, attendu invalidAddress", bad, err)
		}
	}
}

func Test_rejects_old_server(t *testing.T) {
	old := parse[ApiVersion](t, "version-old")
	// `version-old` only has `api.version`: all four required features miss.
	assertErr(t, ensureCompatible(old), NewTooOld("1.0.0", []string{
		"auth.emby-token", "auth.bearer", "auth.sessions", "media.item-id",
	}))
	ok := parse[ApiVersion](t, "version-ok")
	if err := ensureCompatible(ok); err != nil {
		t.Fatal(err)
	}
	next := ok
	next.APIVersion = "2.0.0"
	if e := assertKind(t, ensureCompatible(next), KindTooOld); len(e.Missing) != 0 {
		t.Fatal(e.Missing)
	}
}

func Test_exchange_sends_no_authorization(t *testing.T) {
	tokenFixture := fixture(t, "emby-token")
	server := startFake(t, func(recorded) reply { return jsonReply(200, tokenFixture) })
	longName := strings.Repeat("P", 80) + " (Windows)"
	session, err := server.client().Exchange(bg, embyUser, emby.NewAccessToken(embyToken), longName)
	if err != nil {
		t.Fatal(err)
	}
	if session.Viewer.Name != "Camille" {
		t.Fatal(session.Viewer.Name)
	}
	reqs := server.requests()
	if len(reqs) != 1 || reqs[0].Method != "POST" || reqs[0].Path != "/api/auth/emby-token" || reqs[0].Authorization != nil {
		t.Fatalf("%+v", reqs)
	}
	var body map[string]any
	if err := json.Unmarshal([]byte(reqs[0].Body), &body); err != nil {
		t.Fatal(err)
	}
	if body["userId"] != embyUser || body["accessToken"] != embyToken || body["deviceName"] != strings.Repeat("P", 64) || len(body) != 3 {
		t.Fatalf("%v", body)
	}
}

func Test_bearer_on_other_calls(t *testing.T) {
	version, discover := fixture(t, "version-ok"), fixture(t, "discover")
	detail, page := fixture(t, "request-detail"), fixture(t, "request-page")
	server := startFake(t, func(r recorded) reply {
		path, _, _ := strings.Cut(r.Path, "?")
		switch {
		case path == "/api/version":
			return jsonReply(200, version)
		case path == "/api/discover":
			return jsonReply(200, discover)
		case path == "/api/requests" && r.Method == "POST":
			return jsonReply(201, detail)
		case path == "/api/requests":
			return jsonReply(200, page)
		case path == "/api/auth/logout":
			return emptyReply(204)
		case path == "/api/images/w342/abc.jpg":
			return reply{status: 200, contentType: "image/jpeg", body: []byte{0xFF, 0xD8, 0xFF, 1, 2}}
		}
		return emptyReply(404)
	})
	client := server.client()
	tok := token("ur-session-token")
	v, err := client.Version(bg)
	if err != nil || v.APIVersion != "1.0.0" {
		t.Fatal(v, err)
	}
	if _, err := Get[DiscoverResponse](bg, client, "/api/discover", nil, tok); err != nil {
		t.Fatal(err)
	}
	if _, err := Get[RequestPage](bg, client, "/api/requests", []Param{{"status", "approved,sent"}, {"mine", "true"}}, tok); err != nil {
		t.Fatal(err)
	}
	seasons := SeasonsList(3)
	newReq := NewRequest{MediaType: MediaTv, TmdbID: 94605, Seasons: &seasons}
	if _, err := Send[RequestDetail](bg, client, http.MethodPost, "/api/requests", newReq, tok); err != nil {
		t.Fatal(err)
	}
	if err := client.SendEmpty(bg, http.MethodPost, "/api/auth/logout", tok); err != nil {
		t.Fatal(err)
	}
	data, ct, err := client.Image(bg, "w342", "abc.jpg", tok)
	if err != nil || ct != "image/jpeg" || !reflect.DeepEqual(data, []byte{0xFF, 0xD8, 0xFF, 1, 2}) {
		t.Fatal(data, ct, err)
	}

	reqs := server.requests()
	if reqs[0].Authorization != nil {
		t.Fatal("version is public")
	}
	for _, r := range reqs[1:] {
		if !bearerIs(r, "ur-session-token") {
			t.Fatalf("%s : %v", r.Path, r.Authorization)
		}
		if strings.Contains(r.Path, "ur-session-token") {
			t.Fatal(r.Path)
		}
	}
	if reqs[2].Path != "/api/requests?status=approved%2Csent&mine=true" {
		t.Fatal(reqs[2].Path)
	}
	if reqs[3].Body != `{"type":"tv","tmdbId":94605,"seasons":[3]}` {
		t.Fatal(reqs[3].Body)
	}
}

func Test_new_request_body_follows_the_contract(t *testing.T) {
	marshal := func(v any) string {
		b, err := json.Marshal(v)
		if err != nil {
			t.Fatal(err)
		}
		return string(b)
	}
	if got := marshal(NewRequest{MediaType: MediaMovie, TmdbID: 438631}); got != `{"type":"movie","tmdbId":438631}` {
		t.Fatal(got)
	}
	all := SeasonsAll
	if got := marshal(NewRequest{MediaType: MediaTv, TmdbID: 1, Seasons: &all}); got != `{"type":"tv","tmdbId":1,"seasons":"all"}` {
		t.Fatal(got)
	}
	var fromUI NewRequest
	if err := decodeStrict([]byte(`{"type":"tv","tmdbId":1,"seasons":[1,2]}`), &fromUI); err != nil {
		t.Fatal(err)
	}
	if fromUI.Seasons == nil || !reflect.DeepEqual(*fromUI.Seasons, SeasonsList(1, 2)) {
		t.Fatalf("%+v", fromUI.Seasons)
	}
	var empty NewRequest
	if decodeStrict([]byte(`{"type":"tv","tmdbId":1,"seasons":[]}`), &empty) == nil {
		t.Fatal("liste vide acceptée")
	}
}

func Test_maps_error_body(t *testing.T) {
	errFixture := fixture(t, "error")
	server := startFake(t, func(r recorded) reply {
		switch r.Path {
		case "/api/requests":
			return jsonReply(403, errFixture)
		case "/api/media/movie/1":
			return jsonReply(404, `{"error":"Titre introuvable"}`)
		case "/api/discover":
			return jsonReply(502, `{"error":"TMDB indisponible"}`)
		case "/api/search":
			return emptyReply(500)
		}
		return jsonReply(401, `{"error":"Session expirée"}`)
	})
	client := server.client()
	tok := token("t")
	_, err := Send[RequestDetail](bg, client, http.MethodPost, "/api/requests", NewRequest{MediaType: MediaMovie, TmdbID: 1}, tok)
	assertErr(t, err, NewForbidden("Quota de films atteint"))
	if err.Error() != "Quota de films atteint" {
		t.Fatal(err.Error())
	}
	b, _ := json.Marshal(err)
	if string(b) != `{"kind":"forbidden","message":"Quota de films atteint"}` {
		t.Fatal(string(b))
	}
	_, err = Get[MediaDetails](bg, client, "/api/media/movie/1", nil, tok)
	assertErr(t, err, NewNotFound("Titre introuvable"))
	_, err = Get[DiscoverResponse](bg, client, "/api/discover", nil, tok)
	assertErr(t, err, NewServer(502, "TMDB indisponible"))
	_, err = Get[PagedMedia](bg, client, "/api/search", nil, tok)
	if e := assertKind(t, err, KindServer); e.Status != 500 {
		t.Fatal(e)
	}
	_, err = Get[SessionList](bg, client, "/api/auth/sessions", nil, tok)
	assertErr(t, err, NewRejected("Session expirée"))
}

func Test_rate_limited_exposes_retry_after(t *testing.T) {
	server := startFake(t, func(recorded) reply {
		return jsonReply(429, `{"error":"Trop de tentatives"}`).header("Retry-After", "120")
	})
	_, err := server.client().Exchange(bg, embyUser, emby.NewAccessToken(embyToken), "PC (Windows)")
	assertErr(t, err, NewRateLimited(120))
	if err.Error() != "trop de tentatives de connexion : réessaie dans 2 min" {
		t.Fatal(err.Error())
	}
	// No automatic retry.
	if n := len(server.requests()); n != 1 {
		t.Fatal(n)
	}
}

func Test_unreachable_server_is_typed(t *testing.T) {
	_, err := NewClient(deadAddress(t), "9.9.9").Version(bg)
	assertErr(t, err, ErrUnreachable)
}

func Test_image_refuses_svg_and_html(t *testing.T) {
	server := startFake(t, func(r recorded) reply {
		ct := "text/html"
		if strings.HasSuffix(r.Path, ".svg") {
			ct = "image/svg+xml"
		}
		return reply{status: 200, contentType: ct, body: []byte("<svg/>")}
	})
	client := server.client()
	for _, file := range []string{"a.svg", "a.jpg"} {
		_, _, err := client.Image(bg, "w185", file, token("t"))
		assertErr(t, err, ErrInvalidResponse)
	}
}

func Test_token_never_debug_printed(t *testing.T) {
	tok := token("ur-secret-session-value")
	for _, verb := range []string{"%v", "%+v", "%#v", "%s"} {
		if got := fmt.Sprintf(verb, tok); got != "SessionToken(***)" {
			t.Fatalf("%s : %s", verb, got)
		}
	}
	session := parse[EmbyTokenSession](t, "emby-token")
	for _, verb := range []string{"%v", "%+v", "%#v"} {
		for _, v := range []any{session.Token, session} {
			if got := fmt.Sprintf(verb, v); strings.Contains(got, "EXEMPLE") {
				t.Fatalf("%s : %s", verb, got)
			}
		}
	}
	if _, err := json.Marshal(session); err == nil {
		t.Fatal("la session ne doit pas se sérialiser")
	}
}
