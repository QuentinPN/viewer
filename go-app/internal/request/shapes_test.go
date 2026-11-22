package request

// Go-only tests (Test_go_*): the branches the Rust tests do not reach, and the JSON
// shapes sent to the UI. This unit has no golden file: the expected texts below are
// read by hand from the serde attributes of models.rs, service.rs and mod.rs.

import (
	"encoding/json"
	"errors"
	"net/http"
	"os"
	"reflect"
	"slices"
	"strings"
	"testing"

	"viewer/internal/emby"
)

func marshalText(t *testing.T, v any) string {
	t.Helper()
	b, err := json.Marshal(v)
	mustOK(t, err)
	return string(b)
}

// The `impl Serialize for RequestError`: {kind, message}, message = Display.
func Test_go_error_shape_of_every_kind(t *testing.T) {
	for _, c := range []struct {
		err  RequestError
		want string
	}{
		{ErrNotConfigured, `{"kind":"notConfigured","message":"aucune adresse Request n'est enregistrée"}`},
		{ErrInvalidAddress, `{"kind":"invalidAddress","message":"adresse Request invalide : utilise https:// (http:// seulement sur le réseau local)"}`},
		{ErrUnreachable, `{"kind":"unreachable","message":"Request est injoignable"}`},
		{NewTooOld("0.9.0", []string{"auth.bearer"}), `{"kind":"tooOld","message":"Request est trop ancien (API 0.9.0) pour cette version d'Viewer"}`},
		{NewRejected("Session expirée"), `{"kind":"rejected","message":"Session expirée"}`},
		{NewRateLimited(0), `{"kind":"rateLimited","message":"trop de tentatives de connexion : réessaie dans 1 min"}`},
		{NewRateLimited(61), `{"kind":"rateLimited","message":"trop de tentatives de connexion : réessaie dans 2 min"}`},
		{NewRateLimited(120), `{"kind":"rateLimited","message":"trop de tentatives de connexion : réessaie dans 2 min"}`},
		{NewForbidden("Quota"), `{"kind":"forbidden","message":"Quota"}`},
		{NewNotFound("Titre introuvable"), `{"kind":"notFound","message":"Titre introuvable"}`},
		{NewServer(502, "TMDB indisponible"), `{"kind":"server","message":"TMDB indisponible"}`},
		{ErrInvalidResponse, `{"kind":"invalidResponse","message":"réponse inattendue d'Request"}`},
		{ErrEmbySignedOut, `{"kind":"embySignedOut","message":"aucune session Emby ouverte"}`},
		{ErrSignedOut, `{"kind":"signedOut","message":"déconnecté d'Request"}`},
		{NewCredentialStore("refus"), `{"kind":"credentialStore","message":"coffre d'identification Windows indisponible : refus"}`},
		{NewSettings("disque plein"), `{"kind":"settings","message":"réglage non enregistré : disque plein"}`},
	} {
		if got := marshalText(t, c.err); got != c.want {
			t.Errorf("%s : %s", c.err.Kind, got)
		}
		// Through an `error`, as the bridge sees it.
		var e error = c.err
		if got := marshalText(t, e); got != c.want {
			t.Errorf("%s (error) : %s", c.err.Kind, got)
		}
	}
	if !errors.Is(NewRejected("x"), NewRejected("y")) || errors.Is(ErrSignedOut, ErrNotConfigured) {
		t.Fatal("Is compare le kind")
	}
}

func Test_go_emby_errors_map_like_from(t *testing.T) {
	assertErr(t, fromEmby(emby.NewCredentialStore("refus")), NewCredentialStore("refus"))
	assertErr(t, fromEmby(emby.ErrNotSignedIn), ErrEmbySignedOut)
	assertErr(t, fromEmby(emby.ErrTimeout), NewCredentialStore(emby.ErrTimeout.Error()))
	assertErr(t, fromEmby(ErrSignedOut), ErrSignedOut)
}

// `skip_deserializing`: a server-supplied poster, backdrop or profile never reaches
// an `<img src>`.
func Test_go_server_images_are_never_read(t *testing.T) {
	evil := `{"src":"javascript:alert(1)","srcset":""}`
	var m MediaSummary
	mustOK(t, decodeStrict([]byte(`{"tmdbId":1,"type":"movie","title":"T","year":null,"posterPath":null,"backdropPath":null,"overview":"","poster":`+evil+`,"backdrop":`+evil+`}`), &m))
	if m.Poster != nil || m.Backdrop != nil {
		t.Fatal("image du serveur gardée")
	}
	var p SearchPerson
	mustOK(t, decodeStrict([]byte(`{"id":1,"name":"N","profilePath":null,"knownFor":[],"profile":`+evil+`}`), &p))
	if p.Profile != nil {
		t.Fatal("profil du serveur gardé")
	}
	var c CastMember
	mustOK(t, decodeStrict([]byte(`{"id":1,"name":"N","character":"C","profilePath":"/a.jpg","profile":`+evil+`}`), &c))
	if c.Profile != nil {
		t.Fatal("profil du serveur gardé")
	}
	c.decorate()
	if c.Profile == nil || c.Profile.Src != "http://requestimg.localhost/w185/a.jpg" {
		t.Fatal(c.Profile)
	}
}

func Test_go_ui_json_follows_serde(t *testing.T) {
	var m MediaSummary
	mustOK(t, decodeStrict([]byte(`{"tmdbId":7,"type":"tv","title":"T","year":2020,"posterPath":"/p.jpg","backdropPath":null,"overview":"o","blocked":{"by":"keyword","keyword":"gore"}}`), &m))
	m.decorate()
	want := `{"tmdbId":7,"type":"tv","title":"T","year":2020,"posterPath":"/p.jpg","backdropPath":null,"overview":"o",` +
		`"blocked":{"by":"keyword","keyword":"gore"},"status":null,"progress":null,"embyItemId":null,` +
		`"poster":{"src":"http://requestimg.localhost/w185/p.jpg","srcset":"http://requestimg.localhost/w185/p.jpg 185w, http://requestimg.localhost/w342/p.jpg 342w, http://requestimg.localhost/w500/p.jpg 500w"},"backdrop":null}`
	if got := marshalText(t, m); got != want {
		t.Fatalf("%s\n%s", got, want)
	}
	if got := marshalText(t, Blocked{By: "title"}); got != `{"by":"title"}` {
		t.Fatal(got)
	}
	var b Blocked
	if decodeStrict([]byte(`{"by":"other"}`), &b) == nil || decodeStrict([]byte(`{"by":"keyword"}`), &b) == nil {
		t.Fatal("variante inconnue ou mot-clé absent accepté")
	}

	// `#[serde(flatten)]`: the RequestView keys, then `events`.
	detail := parse[RequestDetail](t, "request-detail")
	var keys []string
	dec := json.NewDecoder(strings.NewReader(marshalText(t, detail)))
	_, _ = dec.Token()
	for dec.More() {
		k, _ := dec.Token()
		keys = append(keys, k.(string))
		var skip json.RawMessage
		mustOK(t, dec.Decode(&skip))
	}
	wantKeys := []string{"id", "type", "tmdbId", "status", "seasons", "requester", "createdBy", "quotaExempt", "decidedBy",
		"declineReason", "qualityProfileId", "rootFolder", "seriesType", "is4k", "server", "createdAt", "updatedAt",
		"media", "failureReason", "events"}
	if !reflect.DeepEqual(keys, wantKeys) {
		t.Fatal(keys)
	}

	// `Option<Vec>`: null and [] stay apart.
	movie := parse[MediaDetails](t, "media-movie")
	movie.Crew, movie.Keywords = nil, &[]Genre{}
	got := marshalText(t, movie)
	if !strings.Contains(got, `"crew":null`) || !strings.Contains(got, `"keywords":[]`) {
		t.Fatal(got)
	}
	caps := marshalText(t, Capabilities{})
	for _, k := range []string{`"canRequest4k"`, `"autoApprove4k"`, `"canAutoRequestWatchlist"`, `"isOwner"`} {
		if !strings.Contains(caps, k) {
			t.Fatal(k, caps)
		}
	}
}

func Test_go_answers_are_decoded_with_serde_rules(t *testing.T) {
	for _, bad := range []string{
		`{"apiVersion":"1.0.0"}`,                            // missing required
		`{"apiVersion":null,"features":[]}`,                 // null for a String
		`{"ApiVersion":"1.0.0","features":[]}`,              // key case
		`{"apiVersion":"1","apiVersion":"1","features":[]}`, // duplicate
		`{"apiVersion":"1.0.0","features":[]} x`,            // trailing data
	} {
		var v ApiVersion
		if decodeStrict([]byte(bad), &v) == nil {
			t.Errorf("%s accepté", bad)
		}
	}
	var v ApiVersion
	mustOK(t, decodeStrict([]byte(`{"apiVersion":"1.0.0","features":[],"extra":1}`), &v))
	var viewer Viewer
	if decodeStrict([]byte(`{"id":"u","name":"n","role":"admin","capabilities":{},"avatarUrl":null}`), &viewer) == nil {
		t.Fatal("rôle inconnu accepté")
	}
	var session EmbyTokenSession
	if decodeStrict([]byte(strings.Replace(fixture(t, "emby-token"), `"`+fixtureToken+`"`, "42", 1)), &session) == nil {
		t.Fatal("jeton non textuel accepté")
	}
	// Through the client: a malformed answer is invalidResponse.
	server := startFake(t, func(recorded) reply { return jsonReply(200, `{"apiVersion":"1.0.0"}`) })
	_, err := server.client().Version(bg)
	assertErr(t, err, ErrInvalidResponse)
}

func Test_go_transport_edges(t *testing.T) {
	server := startFake(t, func(r recorded) reply {
		switch r.Path {
		case "/api/moved":
			return emptyReply(302).header("Location", "http://elsewhere.example/")
		case "/api/empty":
			return emptyReply(204)
		case "/api/plain":
			return emptyReply(429).header("Retry-After", "abc")
		case "/api/plus":
			return emptyReply(429).header("Retry-After", "+30")
		case "/api/huge":
			return emptyReply(429).header("Retry-After", "999999999")
		case "/api/blank":
			return jsonReply(403, `{"error":"  "}`)
		}
		return reply{status: 200, contentType: "image/png; charset=binary", body: []byte{0x89, 'P', 'N', 'G'}}
	})
	c := server.client()
	tok := token("t")
	_, err := Get[ApiVersion](bg, c, "/api/moved", nil, tok)
	assertErr(t, err, NewServer(302, "erreur d'Request (HTTP 302)"))
	_, err = Send[RequestDetail](bg, c, http.MethodPost, "/api/empty", nil, tok)
	assertErr(t, err, ErrInvalidResponse)
	mustOK(t, c.SendEmpty(bg, http.MethodPost, "/api/empty", tok))
	for path, want := range map[string]uint64{"/api/plain": 60, "/api/plus": 30, "/api/huge": 24 * 60 * 60} {
		_, err = Get[ApiVersion](bg, c, path, nil, tok)
		assertErr(t, err, NewRateLimited(want))
	}
	_, err = Get[ApiVersion](bg, c, "/api/blank", nil, tok)
	assertErr(t, err, NewForbidden("accès refusé par Request"))
	data, ct, err := c.Image(bg, "w185", "a.png", tok)
	mustOK(t, err)
	if ct != "image/png" || len(data) != 4 {
		t.Fatal(ct, data)
	}
	reqs := server.requests()
	img := reqs[len(reqs)-1]
	// reqwest's `.header` appends: both Accept values go out.
	if got := img.Header.Values("Accept"); !reflect.DeepEqual(got, []string{"application/json", "image/*"}) {
		t.Fatal(got)
	}
	if img.Header.Get("User-Agent") != "Viewer/9.9.9" || strings.Contains(img.Path, "t?") {
		t.Fatal(img.Header, img.Path)
	}
	// Only the redirect target's own answer: it was never followed.
	if countPath(reqs, "/api/moved") != 1 {
		t.Fatal(reqs)
	}
}

func Test_go_normalize_address_follows_the_url_crate(t *testing.T) {
	for in, want := range map[string]string{
		"HTTPS://Request.Exemple.FR:443/": "https://request.exemple.fr",
		"https://h/a/./b/../c":            "https://h/a/c",
		"https://h/web":                   "https://h/web",
		"https://h/emby/":                 "https://h/emby",
		"https://h\\a":                    "https://h/a",
		"http://[::1]:3000":               "http://[::1]:3000",
		"http://LOCALHOST":                "http://localhost",
		"https://h/a b":                   "",
		"http://169.254.1.1":              "",
		"http://x.lan":                    "",
		"http://[fd00::1]":                "",
		"http://[::ffff:127.0.0.1]":       "",
		"https://h#x":                     "",
		"https://@h":                      "https://h",
		// As Rust: "https://" loses its slashes, then gets https:// again.
		"https://":        "https://https",
		"https://h:99999": "",
	} {
		got, err := NormalizeAddress(in)
		if want == "" {
			if err == nil {
				t.Errorf("%q accepté : %q", in, got)
			}
			continue
		}
		if err != nil || got != want {
			t.Errorf("%q : %q %v, attendu %q", in, got, err, want)
		}
	}
}

func Test_go_settings_file_matches_serde_pretty(t *testing.T) {
	store := &emby.MemoryCredentialStore{}
	path := tempSettings(t)
	ur := NewService(store, path, "PC-TEST", "9.9.9")
	mustOK(t, ur.SetAddress("http://127.0.0.1:3000"))
	data, err := os.ReadFile(path)
	mustOK(t, err)
	if string(data) != "{\n  \"address\": \"http://127.0.0.1:3000\",\n  \"signedOut\": []\n}" {
		t.Fatalf("%q", data)
	}
	mustOK(t, ur.SignIn(identity(embyUser)))
	// Sign-out with an unreachable address: the flag is written all the same.
	mustOK(t, ur.SignOut(bg, identity(embyUser)))
	data, err = os.ReadFile(path)
	mustOK(t, err)
	want := "{\n  \"address\": \"http://127.0.0.1:3000\",\n  \"signedOut\": [\n    \"request/" + embyServer + "/" + embyUser + "\"\n  ]\n}"
	if string(data) != want {
		t.Fatalf("%q", data)
	}
	if _, err := os.Stat(path + ".tmp"); !os.IsNotExist(err) {
		t.Fatal("fichier temporaire laissé")
	}
	// A file Rust wrote, hand-edited: a public http address is dropped, an
	// unreadable file is the default.
	mustOK(t, os.WriteFile(path, []byte(`{"address":"http://8.8.8.8","signedOut":["a"]}`), 0o644))
	again := NewService(store, path, "PC-TEST", "9.9.9")
	if again.address() != nil || !again.isSignedOut("a") {
		t.Fatal(again.address())
	}
	mustOK(t, os.WriteFile(path, []byte(`{"address":"https://h","signedOut":null}`), 0o644))
	if NewService(store, path, "PC-TEST", "9.9.9").address() != nil {
		t.Fatal("fichier illisible lu")
	}
}

func Test_go_commands_take_tauri_arguments(t *testing.T) {
	genres := `{"genres":[{"id":16,"name":"Animation"}]}`
	sessions := fixture(t, "sessions")
	paged := fixture(t, "paged-media")
	store := &emby.MemoryCredentialStore{}
	version, tokenFixture := fixture(t, "version-ok"), fixture(t, "emby-token")
	full := startFake(t, func(r recorded) reply {
		path, _, _ := strings.Cut(r.Path, "?")
		switch {
		case path == "/api/version":
			return jsonReply(200, version)
		case path == "/api/auth/emby-token":
			return jsonReply(200, tokenFixture)
		case path == "/api/discover/movie":
			return jsonReply(200, paged)
		case path == "/api/genres/tv":
			return jsonReply(200, genres)
		case path == "/api/auth/sessions":
			return jsonReply(200, sessions)
		case strings.HasPrefix(path, "/api/auth/sessions/"):
			return emptyReply(204)
		}
		return emptyReply(404)
	})
	ur := newTestService(t, store, full.URL)
	calls := 0
	h := Handlers(ur, func() (Identity, error) { calls++; return identity(embyUser), nil })
	names := make([]string, 0, len(h))
	for n := range h {
		names = append(names, n)
	}
	slices.Sort(names)
	if !reflect.DeepEqual(names, []string{"ur_browse", "ur_cancel", "ur_discover", "ur_genres", "ur_media", "ur_my_requests",
		"ur_quota", "ur_request", "ur_request_detail", "ur_revoke_session", "ur_search", "ur_sessions", "ur_set_address",
		"ur_sign_in", "ur_sign_out", "ur_status"}) {
		t.Fatal(names)
	}
	run := func(name, args string) (any, error) { return h[name](bg, json.RawMessage(args)) }

	// A blank search answers at once, without the Emby session.
	res, err := run("ur_search", `{"query":"   "}`)
	mustOK(t, err)
	if got := marshalText(t, res); got != `{"page":1,"totalPages":0,"results":[],"people":[]}` || calls != 0 {
		t.Fatal(got, calls)
	}
	// Bad arguments are a bare string, as Tauri's, before the body runs.
	for name, args := range map[string]string{
		"ur_my_requests": `{"page":null}`,
		"ur_media":       `{"mediaType":"music","tmdbId":1}`,
		"ur_request":     `{"request":{"type":"tv","tmdbId":1,"seasons":[]}}`,
		"ur_cancel":      `{"id":-1}`,
		"ur_browse":      `{"mediaType":"movie"}`,
		"ur_search":      `{"query":"x","page":"2"}`,
		"ur_status":      `[]`,
	} {
		_, err := run(name, args)
		var m json.Marshaler
		if err == nil || errors.As(err, &m) {
			t.Errorf("%s %s : %v", name, args, err)
		}
	}
	if calls != 0 {
		t.Fatal("identité lue avant les arguments")
	}

	res, err = run("ur_browse", `{"mediaType":"movie","filters":{"page":2,"genres":[16],"sort":"rating","unknown":1}}`)
	mustOK(t, err)
	if !strings.Contains(marshalText(t, res), "requestimg.localhost") {
		t.Fatal("affiches absentes")
	}
	res, err = run("ur_genres", `{"mediaType":"tv"}`)
	mustOK(t, err)
	if got := marshalText(t, res); got != `[{"id":16,"name":"Animation"}]` {
		t.Fatal(got)
	}
	res, err = run("ur_sessions", `{}`)
	mustOK(t, err)
	if list, ok := res.([]AuthSession); !ok || len(list) != 3 {
		t.Fatal(res)
	}
	res, err = run("ur_revoke_session", `{"id":"../x"}`)
	if err != nil || res != nil {
		t.Fatal(res, err)
	}
	var browse, revoke string
	for _, r := range full.requests() {
		if strings.HasPrefix(r.Path, "/api/discover/movie") {
			browse = r.Path
		}
		if strings.HasPrefix(r.Path, "/api/auth/sessions/") {
			revoke = r.Method + " " + r.Path
		}
	}
	if browse != "/api/discover/movie?page=2&genres=16&sort=rating" || revoke != "DELETE /api/auth/sessions/%2E%2E%2Fx" {
		t.Fatal(browse, revoke)
	}

	// No Emby session: embySignedOut.
	signedOut := Handlers(ur, func() (Identity, error) { return Identity{}, emby.ErrNotSignedIn })
	_, err = signedOut["ur_status"](bg, json.RawMessage(`{}`))
	if got := marshalText(t, err); got != `{"kind":"embySignedOut","message":"aucune session Emby ouverte"}` {
		t.Fatal(got)
	}
}

// Every ur_* command once against the fake server: arguments read, method, target
// and body sent, answer returned.
func Test_go_every_command_sends_the_rust_request(t *testing.T) {
	answers := map[string]string{
		"GET /api/auth/me":              `{"viewer":` + string(mustViewer(t)) + `}`,
		"GET /api/discover":             fixture(t, "discover"),
		"GET /api/search":               fixture(t, "search"),
		"GET /api/media/movie/438631":   fixture(t, "media-movie"),
		"GET /api/requests/quota":       fixture(t, "quota"),
		"POST /api/requests":            fixture(t, "request-detail"),
		"GET /api/requests":             fixture(t, "request-page"),
		"GET /api/requests/102":         fixture(t, "request-detail"),
		"POST /api/requests/102/cancel": fixture(t, "request-detail"),
		"POST /api/auth/logout":         "",
		"GET /api/version":              fixture(t, "version-ok"),
		"POST /api/auth/emby-token":     strings.ReplaceAll(fixture(t, "emby-token"), fixtureToken, "tok-1"),
	}
	server := startFake(t, func(r recorded) reply {
		path, _, _ := strings.Cut(r.Path, "?")
		key := r.Method + " " + path
		if path != "/api/version" && path != "/api/auth/emby-token" && !bearerIs(r, "tok-1") {
			return jsonReply(401, `{"error":"Session invalide"}`)
		}
		body, ok := answers[key]
		switch {
		case !ok:
			return emptyReply(404)
		case body == "":
			return emptyReply(204)
		}
		return jsonReply(200, body)
	})
	store := &emby.MemoryCredentialStore{}
	ur := NewService(store, tempSettings(t), "PC-TEST", "9.9.9")
	h := Handlers(ur, func() (Identity, error) { return identity(embyUser), nil })
	run := func(name, args string) any {
		t.Helper()
		res, err := h[name](bg, json.RawMessage(args))
		if err != nil {
			t.Fatalf("%s : %v", name, err)
		}
		return res
	}
	state := func(v any) UrState {
		t.Helper()
		st, ok := v.(UrStatus)
		if !ok {
			t.Fatalf("%T", v)
		}
		return st.State
	}
	if s := state(run("ur_set_address", `{"address":"`+server.URL+`/"}`)); s != StateReady {
		t.Fatal(s)
	}
	if s := state(run("ur_status", `{}`)); s != StateReady {
		t.Fatal(s)
	}
	run("ur_discover", `{}`)
	run("ur_search", `{"query":"  dune  ","page":0}`)
	run("ur_media", `{"mediaType":"movie","tmdbId":438631}`)
	if q, ok := run("ur_quota", `{}`).(RequestQuota); !ok {
		t.Fatal(q)
	}
	run("ur_request", `{"request":{"type":"tv","tmdbId":1399,"seasons":"all"}}`)
	run("ur_my_requests", `{"status":["approved","sent"],"page":2}`)
	run("ur_my_requests", `{"status":null,"page":0}`)
	run("ur_request_detail", `{"id":102}`)
	if d, ok := run("ur_cancel", `{"id":102}`).(RequestDetail); !ok || len(d.Events) != 3 {
		t.Fatal(d)
	}
	if res := run("ur_sign_out", `{}`); res != nil {
		t.Fatal(res)
	}
	if s := state(run("ur_status", `{}`)); s != StateSignedOut {
		t.Fatal(s)
	}
	if s := state(run("ur_sign_in", `{}`)); s != StateReady {
		t.Fatal(s)
	}
	var sent []string
	for _, r := range server.requests() {
		if r.Path == "/api/version" || r.Path == "/api/auth/emby-token" {
			continue
		}
		line := r.Method + " " + r.Path
		if r.Body != "" {
			line += " " + r.Body
		}
		sent = append(sent, line)
	}
	want := []string{
		"GET /api/auth/me",
		"GET /api/auth/me",
		"GET /api/discover",
		"GET /api/search?q=dune&page=1",
		"GET /api/media/movie/438631",
		"GET /api/requests/quota",
		`POST /api/requests {"type":"tv","tmdbId":1399,"seasons":"all"}`,
		"GET /api/requests?mine=true&page=2&status=approved%2Csent",
		"GET /api/requests?mine=true&page=1",
		"GET /api/requests/102",
		"POST /api/requests/102/cancel",
		"POST /api/auth/logout",
		"GET /api/auth/me",
	}
	if !reflect.DeepEqual(sent, want) {
		t.Fatalf("%q", sent)
	}
}

func mustViewer(t *testing.T) json.RawMessage {
	var session struct {
		Viewer json.RawMessage `json:"viewer"`
	}
	mustOK(t, json.Unmarshal([]byte(fixture(t, "emby-token")), &session))
	return session.Viewer
}
