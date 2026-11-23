package stremio

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"slices"
	"strings"
	"testing"
	"time"
)

func wantKind(t testing.TB, err error, kind ErrorKind) Error {
	t.Helper()
	var e Error
	if !errors.As(err, &e) || e.Kind != kind {
		t.Fatalf("erreur %v, attendu %s", err, kind)
	}
	return e
}

func TestLoginOK(t *testing.T) {
	f := newFakeServer(t)
	logs := captureLogs(t)
	key, err := NewClient(f.URL, "1.2.3").Login(context.Background(), "owner@example.com", "hunter2")
	if err != nil {
		t.Fatal(err)
	}
	if key.value != fixtureKey {
		t.Fatalf("clé %q", key.value)
	}
	reqs := f.log()
	if len(reqs) != 1 || reqs[0].Method != http.MethodPost || reqs[0].URI != "/api/login" {
		t.Fatalf("requêtes %+v", reqs)
	}
	var body map[string]any
	if err := json.Unmarshal([]byte(reqs[0].Body), &body); err != nil {
		t.Fatal(err)
	}
	want := map[string]any{"type": "Login", "email": "owner@example.com", "password": "hunter2", "facebook": false}
	if len(body) != len(want) {
		t.Fatalf("corps %v", body)
	}
	for k, v := range want {
		if body[k] != v {
			t.Fatalf("corps %v", body)
		}
	}
	assertNoSecret(t, "journal", logs.String(), f.URL)
}

func TestLoginWrongPassword(t *testing.T) {
	f := newFakeServer(t)
	f.login = string(fixture(t, "login_wrong_password"))
	_, err := NewClient(f.URL, "1").Login(context.Background(), "owner@example.com", "hunter2")
	e := wantKind(t, err, KindRejected)
	if err.Error() != "Adresse e-mail ou mot de passe incorrect." || e.Addon != "" {
		t.Fatalf("%q", err)
	}
}

func TestLoginBadAnswers(t *testing.T) {
	cases := map[string]struct {
		status int
		body   string
		kind   ErrorKind
	}{
		"500":          {500, `{"error":{"code":0}}`, KindServer},
		"not json":     {200, `<html>`, KindServer},
		"no key":       {200, `{"result":{"authKey":""}}`, KindServer},
		"null result":  {200, `{"result":null}`, KindServer},
		"error string": {200, `{"error":"nope"}`, KindRejected},
	}
	for name, c := range cases {
		t.Run(name, func(t *testing.T) {
			f := newFakeServer(t)
			f.handler = func(w http.ResponseWriter, _ *http.Request) { writeJSON(w, c.status, c.body) }
			_, err := NewClient(f.URL, "1").Login(context.Background(), "a@b.c", "hunter2")
			wantKind(t, err, c.kind)
			assertNoSecret(t, "erreur", err.Error(), f.URL)
		})
	}
}

func TestLoginUnreachable(t *testing.T) {
	f := newFakeServer(t)
	f.handler = func(w http.ResponseWriter, _ *http.Request) { closeConnection(w) }
	_, err := NewClient(f.URL, "1").Login(context.Background(), "a@b.c", "hunter2")
	wantKind(t, err, KindUnreachable)
	if err.Error() != "Stremio ne répond pas. Réessaie plus tard." {
		t.Fatal(err)
	}
}

func TestAddonsOrderAndSkip(t *testing.T) {
	f := newFakeServer(t)
	f.collection = f.rewrittenCollection(map[int]map[string]any{
		1: {"transportUrl": "http://example.com/secret-path/manifest.json", "manifest": map[string]any{"id": "p", "version": "1", "name": "Public HTTP"}},
		5: {"transportUrl": f.URL + "/broken/manifest.json", "manifest": "not a manifest"},
	})
	logs := captureLogs(t)
	addons, err := NewClient(f.URL, "1").Addons(context.Background(), NewAuthKey(fixtureKey))
	if err != nil {
		t.Fatal(err)
	}
	var names []string
	for _, a := range addons {
		names = append(names, a.Name)
	}
	want := []string{
		"The Movie Database Addon", "TMDB Collections", "Local Files (without catalog support)", "Cinemeta",
		"Lumio · Profil", "AIOSources", "StreamFusionReborn", "Wastream | AD", "Comet | AD", "TorrentsDB AD",
	}
	if !slices.Equal(names, want) {
		t.Fatalf("addons %q", names)
	}
	reqs := f.log()
	if len(reqs) != 1 || reqs[0].URI != "/api/addonCollectionGet" {
		t.Fatalf("requêtes %+v", reqs)
	}
	var body map[string]any
	if err := json.Unmarshal([]byte(reqs[0].Body), &body); err != nil {
		t.Fatal(err)
	}
	if len(body) != 3 || body["type"] != "AddonCollectionGet" || body["authKey"] != fixtureKey || body["update"] != true {
		t.Fatalf("corps %v", body)
	}
	out := logs.String()
	assertNoSecret(t, "journal", out, f.URL)
	for _, s := range []string{"example.com", "secret-path", "broken"} {
		if strings.Contains(out, s) {
			t.Errorf("journal contient %q : %s", s, out)
		}
	}
	if !strings.Contains(out, "Public HTTP") {
		t.Errorf("l'addon écarté n'est pas nommé : %s", out)
	}
}

func TestAddonsSessionExpired(t *testing.T) {
	f := newFakeServer(t)
	f.collection = string(fixture(t, "addon_collection_session_expired"))
	_, err := NewClient(f.URL, "1").Addons(context.Background(), NewAuthKey(fixtureKey))
	wantKind(t, err, KindRejected)
	if err.Error() != "Session Stremio expirée. Reconnecte-toi." {
		t.Fatal(err)
	}
}

// streamCounts is what each addon of a fixture gives: playable, unsupported.
func streamCounts(t testing.TB, f *fakeServer, ct ContentType, id string) map[string][2]int {
	t.Helper()
	c := NewClient(f.URL, "1")
	out := map[string][2]int{}
	for _, a := range f.addons(t) {
		if !a.Serves(ct, id) {
			continue
		}
		r, err := c.Streams(context.Background(), a, ct, id)
		if err != nil {
			e := wantKind(t, err, KindUnreachable)
			if e.Addon != a.Name {
				t.Fatalf("addon de l'erreur %q", e.Addon)
			}
			assertNoSecret(t, "erreur", err.Error(), f.URL)
			out[a.Name] = [2]int{-1, -1}
			continue
		}
		out[a.Name] = [2]int{len(r.Streams), r.Unsupported}
	}
	return out
}

func TestStreamsMovie(t *testing.T) {
	f := newFakeServer(t)
	got := streamCounts(t, f, Movie, "tt0111161")
	want := map[string][2]int{
		"TMDB Collections": {0, 0}, "Local Files (without catalog support)": {-1, -1},
		"Lumio · Profil": {6, 0}, "AIOSources": {6, 0}, "StreamFusionReborn": {6, 0},
		"Wastream | AD": {6, 0}, "Comet | AD": {6, 0}, "TorrentsDB AD": {6, 0},
	}
	if len(got) != len(want) {
		t.Fatalf("%v", got)
	}
	for k, v := range want {
		if got[k] != v {
			t.Errorf("%s : %v, attendu %v", k, got[k], v)
		}
	}
	if !slices.ContainsFunc(f.log(), func(r request) bool { return r.URI == "/addon-4/stream/movie/tt0111161.json" }) {
		t.Fatalf("chemin : %+v", f.log())
	}
	// Lumio's first stream, as given.
	r, err := NewClient(f.URL, "1").Streams(context.Background(), addonNamed(t, f.addons(t), "Lumio · Profil"), Movie, "tt0111161")
	if err != nil {
		t.Fatal(err)
	}
	s := r.Streams[0]
	if s.Name != "[AD⚡️] Lumio" || s.Filename != "The Shawshank Redemption (Les Evadés) - (1994).mkv" ||
		s.Size != 31682067376 || s.URL() != "https://debrid.example/REDACTED/file.mkv" || s.Headers() != nil ||
		!strings.Contains(s.Title, "1080p • BluRay REMUX") {
		t.Fatalf("%q %q %d %q %q", s.Name, s.Filename, s.Size, s.URL(), s.Title)
	}
	// TorrentsDB gives `title` instead of `description`.
	r, err = NewClient(f.URL, "1").Streams(context.Background(), addonNamed(t, f.addons(t), "TorrentsDB AD"), Movie, "tt0111161")
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(r.Streams[0].Title, "YIFY") {
		t.Fatalf("titre %q", r.Streams[0].Title)
	}
}

func TestStreamsSeries(t *testing.T) {
	f := newFakeServer(t)
	id, err := StreamID("tt0944947", 1, 2)
	if err != nil {
		t.Fatal(err)
	}
	got := streamCounts(t, f, Series, id)
	want := map[string][2]int{
		"Local Files (without catalog support)": {-1, -1},
		"Lumio · Profil":                        {6, 0}, "AIOSources": {6, 0}, "StreamFusionReborn": {6, 0},
		"Wastream | AD": {6, 0}, "Comet | AD": {6, 0}, "TorrentsDB AD": {6, 0},
	}
	if len(got) != len(want) {
		t.Fatalf("%v", got)
	}
	for k, v := range want {
		if got[k] != v {
			t.Errorf("%s : %v, attendu %v", k, got[k], v)
		}
	}
	// The `:` of the id stays unescaped: every captured addon answers that form.
	if !slices.ContainsFunc(f.log(), func(r request) bool { return r.URI == "/addon-4/stream/series/tt0944947:1:2.json" }) {
		t.Fatalf("chemin : %+v", f.log())
	}
}

func TestStreamsUnsupportedAndHeaders(t *testing.T) {
	f := newFakeServer(t)
	f.handler = func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, 200, `{"streams":[
			{"name":"torrent","infoHash":"0123456789abcdef0123456789abcdef01234567","fileIdx":0},
			{"name":"yt","ytId":"abc"},
			{"name":"ext","externalUrl":"https://example.com/x"},
			{"name":"magnet","url":"magnet:?xt=urn:btih:0123"},
			{"name":"bad","url":5},
			{"name":"both","url":"https://debrid.example/REDACTED/a.mkv","infoHash":"0123456789abcdef0123456789abcdef01234567",
			 "description":"desc","behaviorHints":{"filename":"a.mkv","proxyHeaders":{"request":{"User-Agent":"UA","X-Bad":"a\r\nb","":"x"}}},"sizeBytes":1234},
			{"name":"float","title":"t","url":"http://192.168.1.20/b.mkv","behaviorHints":{"videoSize":1.5e9,"proxyHeaders":"weird"}}
		]}`)
	}
	addon, err := ParseAddon(f.URL+"/addon-8/manifest.json", json.RawMessage(`{"id":"c","version":"1","name":"Comet | AD","resources":["stream"],"types":["movie"]}`))
	if err != nil {
		t.Fatal(err)
	}
	r, err := NewClient(f.URL, "1").Streams(context.Background(), addon, Movie, "tt0111161")
	if err != nil {
		t.Fatal(err)
	}
	if len(r.Streams) != 2 || r.Unsupported != 5 {
		t.Fatalf("%d jouables, %d non pris en charge", len(r.Streams), r.Unsupported)
	}
	both, float := r.Streams[0], r.Streams[1]
	if both.Name != "both" || both.Title != "desc" || both.Filename != "a.mkv" || both.Size != 1234 ||
		len(both.Headers()) != 1 || both.Headers()["User-Agent"] != "UA" {
		t.Fatalf("%q %q %q %d %v", both.Name, both.Title, both.Filename, both.Size, both.Headers())
	}
	if float.Title != "t" || float.Size != 1500000000 || float.Headers() != nil || float.URL() != "http://192.168.1.20/b.mkv" {
		t.Fatalf("%q %d %v", float.Title, float.Size, float.Headers())
	}
}

func TestStreamsErrors(t *testing.T) {
	cases := map[string]struct {
		handler http.HandlerFunc
		kind    ErrorKind
	}{
		"404": {func(w http.ResponseWriter, _ *http.Request) {
			writeJSON(w, 404, `{"error":"Type not supported","streams":[]}`)
		}, KindServer},
		"not json": {func(w http.ResponseWriter, _ *http.Request) { writeJSON(w, 200, `<html>`) }, KindServer},
		"streams?": {func(w http.ResponseWriter, _ *http.Request) { writeJSON(w, 200, `{"streams":"x"}`) }, KindServer},
		"closed":   {func(w http.ResponseWriter, _ *http.Request) { closeConnection(w) }, KindUnreachable},
		"redirect": {func(w http.ResponseWriter, r *http.Request) {
			http.Redirect(w, r, "https://evil.example/", http.StatusFound)
		}, KindServer},
	}
	for name, c := range cases {
		t.Run(name, func(t *testing.T) {
			f := newFakeServer(t)
			f.handler = c.handler
			addon := addonNamed(t, f.addons(t), "Comet | AD")
			_, err := NewClient(f.URL, "1").Streams(context.Background(), addon, Movie, "tt0111161")
			e := wantKind(t, err, c.kind)
			if e.Addon != "Comet | AD" {
				t.Fatalf("addon %q", e.Addon)
			}
			assertNoSecret(t, "erreur", err.Error(), f.URL)
		})
	}
	f := newFakeServer(t)
	addon := addonNamed(t, f.addons(t), "Comet | AD")
	for _, ct := range []ContentType{"channel", ""} {
		if _, err := NewClient(f.URL, "1").Streams(context.Background(), addon, ct, "tt0111161"); !errors.Is(err, ErrInvalidID) {
			t.Fatalf("type %q : %v", ct, err)
		}
	}
	if _, err := NewClient(f.URL, "1").Streams(context.Background(), addon, Movie, ""); !errors.Is(err, ErrInvalidID) {
		t.Fatal(err)
	}
	if len(f.log()) != 0 {
		t.Fatalf("requêtes envoyées : %+v", f.log())
	}
}

func TestStreamsTimeout(t *testing.T) {
	f := newFakeServer(t)
	f.handler = func(w http.ResponseWriter, r *http.Request) {
		select {
		case <-r.Context().Done():
		case <-time.After(2 * time.Second):
		}
		writeJSON(w, 200, `{"streams":[]}`)
	}
	c := NewClient(f.URL, "1")
	c.streamTimeout = 50 * time.Millisecond
	addon := addonNamed(t, f.addons(t), "Comet | AD")
	start := time.Now()
	_, err := c.Streams(context.Background(), addon, Movie, "tt0111161")
	e := wantKind(t, err, KindUnreachable)
	if e.Addon != "Comet | AD" || err.Error() != "Comet | AD ne répond pas." {
		t.Fatalf("%q", err)
	}
	if time.Since(start) > time.Second {
		t.Fatalf("délai non respecté : %v", time.Since(start))
	}
	assertNoSecret(t, "erreur", err.Error(), f.URL)
	if StreamTimeout != 10*time.Second || NewClient(f.URL, "1").streamTimeout != StreamTimeout {
		t.Fatal("délai par addon")
	}
}
