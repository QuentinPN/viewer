package request

// Service, images and command tests of src-tauri/src/request/tests.rs (second
// and third parts), same names.

import (
	"encoding/json"
	"fmt"
	"net/http"
	"path/filepath"
	"reflect"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"viewer/internal/artworkcache"
	"viewer/internal/emby"
	"viewer/internal/redact"
)

func discoverCall(s *Service, id Identity) (DiscoverResponse, error) {
	return ServiceGet[DiscoverResponse](bg, s, id, "/api/discover", nil)
}

func mustOK(t *testing.T, err error) {
	t.Helper()
	if err != nil {
		t.Fatal(err)
	}
}

func ptrIs(p *string, want string) bool { return p != nil && *p == want }

func countPath(reqs []recorded, path string) int {
	n := 0
	for _, r := range reqs {
		if r.Path == path {
			n++
		}
	}
	return n
}

func concurrentDiscover(s *Service, id Identity, n int) []error {
	errs := make([]error, n)
	var wg sync.WaitGroup
	for i := range n {
		wg.Go(func() { _, errs[i] = discoverCall(s, id) })
	}
	wg.Wait()
	return errs
}

func Test_concurrent_calls_exchange_once(t *testing.T) {
	server, exchanges := requestLike(t, func(string) bool { return true }, nil)
	store := &emby.MemoryCredentialStore{}
	ur := newTestService(t, store, server.URL)
	for _, err := range concurrentDiscover(ur, identity(embyUser), 8) {
		mustOK(t, err)
	}
	if exchanges.Load() != 1 || !ptrIs(stored(t, store, embyUser), "tok-1") {
		t.Fatal(exchanges.Load(), stored(t, store, embyUser))
	}
	for _, r := range server.requests() {
		if r.Path == "/api/auth/emby-token" {
			var body map[string]any
			mustOK(t, json.Unmarshal([]byte(r.Body), &body))
			if body["deviceName"] != "PC-TEST (Windows)" {
				t.Fatal(body["deviceName"])
			}
		}
	}
}

func Test_concurrent_401s_exchange_once(t *testing.T) {
	// Barrier: the first refused call waits until a second one is sent with the same
	// stale token, so several calls really are refused at once.
	var stale atomic.Int64
	second := make(chan struct{})
	var once sync.Once
	onBearer := func(r recorded) {
		if !bearerIs(r, "stale") {
			return
		}
		if stale.Add(1) >= 2 {
			once.Do(func() { close(second) })
			return
		}
		select {
		case <-second:
		case <-time.After(5 * time.Second):
		}
	}
	server, exchanges := requestLike(t, func(tok string) bool { return tok != "stale" }, onBearer)
	store := &emby.MemoryCredentialStore{}
	seed(t, store, embyUser, server.URL, "stale")
	ur := newTestService(t, store, server.URL)
	for _, err := range concurrentDiscover(ur, identity(embyUser), 8) {
		mustOK(t, err)
	}
	if exchanges.Load() != 1 {
		t.Fatal(exchanges.Load())
	}
	n := 0
	for _, r := range server.requests() {
		if bearerIs(r, "stale") {
			n++
		}
	}
	// Several calls really were refused at once: the test is not vacuous.
	if n < 2 {
		t.Fatalf("only %d stale requests", n)
	}
	if !ptrIs(stored(t, store, embyUser), "tok-1") {
		t.Fatal(stored(t, store, embyUser))
	}
}

func Test_retries_once_after_401(t *testing.T) {
	server, exchanges := requestLike(t, func(tok string) bool { return tok != "stale" }, nil)
	store := &emby.MemoryCredentialStore{}
	seed(t, store, embyUser, server.URL, "stale")
	ur := newTestService(t, store, server.URL)
	_, err := discoverCall(ur, identity(embyUser))
	mustOK(t, err)
	if exchanges.Load() != 1 || !ptrIs(stored(t, store, embyUser), "tok-1") {
		t.Fatal(exchanges.Load())
	}
	var calls []recorded
	for _, r := range server.requests() {
		if r.Path == "/api/discover" {
			calls = append(calls, r)
		}
	}
	if len(calls) != 2 || !bearerIs(calls[0], "stale") || !bearerIs(calls[1], "tok-1") {
		t.Fatalf("%+v", calls)
	}
}

func Test_rejected_after_single_retry(t *testing.T) {
	server, exchanges := requestLike(t, func(string) bool { return false }, nil)
	store := &emby.MemoryCredentialStore{}
	ur := newTestService(t, store, server.URL)
	id := identity(embyUser)
	_, err := discoverCall(ur, id)
	assertErr(t, err, NewRejected("Session invalide"))
	if exchanges.Load() != 2 || stored(t, store, embyUser) != nil {
		t.Fatal(exchanges.Load())
	}
	// No loop: later calls fail at once until "Se connecter".
	_, err = discoverCall(ur, id)
	assertKind(t, err, KindRejected)
	if st := ur.Status(bg, id); st.State != StateRejected {
		t.Fatal(st.State)
	}
	if exchanges.Load() != 2 {
		t.Fatal(exchanges.Load())
	}
	mustOK(t, ur.SignIn(id))
	_, _ = discoverCall(ur, id)
	if exchanges.Load() != 4 {
		t.Fatal(exchanges.Load())
	}
}

func Test_refused_exchange_is_not_retried(t *testing.T) {
	version := fixture(t, "version-ok")
	server := startFake(t, func(r recorded) reply {
		if r.Path == "/api/version" {
			return jsonReply(200, version)
		}
		return jsonReply(401, `{"error":"Jeton Emby refusé"}`)
	})
	store := &emby.MemoryCredentialStore{}
	ur := newTestService(t, store, server.URL)
	id := identity(embyUser)
	for range 3 {
		_, err := discoverCall(ur, id)
		assertErr(t, err, NewRejected("Jeton Emby refusé"))
	}
	if n := countPath(server.requests(), "/api/auth/emby-token"); n != 1 {
		t.Fatal(n)
	}
	// A new Emby session (another token) may try again.
	renewed := id
	renewed.EmbyToken = emby.NewAccessToken("new-emby-token")
	_, _ = discoverCall(ur, renewed)
	if n := len(server.requests()); n != 3 {
		t.Fatal(n)
	}
}

func Test_rate_limit_blocks_further_calls(t *testing.T) {
	version := fixture(t, "version-ok")
	server := startFake(t, func(r recorded) reply {
		if r.Path == "/api/version" {
			return jsonReply(200, version)
		}
		return jsonReply(429, `{"error":"Trop de tentatives"}`).header("Retry-After", "300")
	})
	store := &emby.MemoryCredentialStore{}
	ur := newTestService(t, store, server.URL)
	id := identity(embyUser)
	_, err := discoverCall(ur, id)
	assertErr(t, err, NewRateLimited(300))
	sent := len(server.requests())
	_, err = discoverCall(ur, id)
	if e := assertKind(t, err, KindRateLimited); e.RetryAfterS > 301 {
		t.Fatal(e.RetryAfterS)
	}
	if len(server.requests()) != sent {
		t.Fatal("requête envoyée pendant le blocage")
	}
}

func Test_sign_out_does_not_auto_reconnect(t *testing.T) {
	server, exchanges := requestLike(t, func(string) bool { return true }, nil)
	store := &emby.MemoryCredentialStore{}
	path := tempSettings(t)
	restart := func() *Service { return NewService(store, path, "PC-TEST", "9.9.9") }
	ur := restart()
	mustOK(t, ur.SetAddress(server.URL))
	id := identity(embyUser)
	if st := ur.Status(bg, id); st.State != StateReady {
		t.Fatal(st)
	}
	mustOK(t, ur.SignOut(bg, id))
	if stored(t, store, embyUser) != nil {
		t.Fatal("jeton gardé")
	}
	_, err := discoverCall(ur, id)
	assertErr(t, err, ErrSignedOut)
	// The flag survives a restart.
	restarted := restart()
	status := restarted.Status(bg, id)
	if status.State != StateSignedOut || !ptrIs(status.Address, server.URL) {
		t.Fatalf("%+v", status)
	}
	img, _ := ParseUrImage("/w342/abc.jpg")
	_, _, err = restarted.Image(bg, id, img)
	assertErr(t, err, ErrSignedOut)
	if exchanges.Load() != 1 {
		t.Fatal(exchanges.Load())
	}
	// Another Emby account is not signed out.
	if st := restarted.Status(bg, identity("otheruser")); st.State != StateReady {
		t.Fatal(st)
	}
	mustOK(t, restarted.SignIn(id))
	status = restarted.Status(bg, id)
	if status.State != StateReady || status.Viewer.Name != "Camille" || !ptrIs(status.APIVersion, "1.0.0") {
		t.Fatalf("%+v", status)
	}
	if exchanges.Load() != 3 {
		t.Fatal(exchanges.Load())
	}
}

func Test_sign_out_revokes_and_deletes(t *testing.T) {
	server, _ := requestLike(t, func(string) bool { return true }, nil)
	store := &emby.MemoryCredentialStore{}
	ur := newTestService(t, store, server.URL)
	id := identity(embyUser)
	_, err := discoverCall(ur, id)
	mustOK(t, err)
	mustOK(t, ur.SignOut(bg, id))
	reqs := server.requests()
	logout := reqs[len(reqs)-1]
	if logout.Method != "POST" || logout.Path != "/api/auth/logout" || !bearerIs(logout, "tok-1") {
		t.Fatalf("%+v", logout)
	}
	if stored(t, store, embyUser) != nil {
		t.Fatal("jeton gardé")
	}

	// Unreachable server: the token is deleted all the same.
	dead := deadAddress(t)
	ur = newTestService(t, store, dead)
	seed(t, store, embyUser, dead, "kept-token")
	mustOK(t, ur.SignOut(bg, id))
	if stored(t, store, embyUser) != nil {
		t.Fatal("jeton gardé")
	}
}

func Test_token_is_scoped_to_emby_account(t *testing.T) {
	server, exchanges := requestLike(t, func(string) bool { return true }, nil)
	store := &emby.MemoryCredentialStore{}
	ur := newTestService(t, store, server.URL)
	for _, u := range []string{"usera", "userb", "usera"} {
		_, err := discoverCall(ur, identity(u))
		mustOK(t, err)
	}
	if exchanges.Load() != 2 {
		t.Fatal(exchanges.Load())
	}
	if got := Account(embyServer, "usera"); got != "request/"+embyServer+"/usera" {
		t.Fatal(got)
	}
	if !ptrIs(stored(t, store, "usera"), "tok-1") || !ptrIs(stored(t, store, "userb"), "tok-2") {
		t.Fatal(stored(t, store, "usera"), stored(t, store, "userb"))
	}
}

func Test_status_not_configured(t *testing.T) {
	store := &emby.MemoryCredentialStore{}
	ur := newTestService(t, store, "")
	id := identity(embyUser)
	status := ur.Status(bg, id)
	if status.State != StateNotConfigured {
		t.Fatal(status.State)
	}
	b, err := json.Marshal(status)
	mustOK(t, err)
	if string(b) != `{"state":"notConfigured","address":null,"viewer":null,"apiVersion":null,"missing":[],"message":null}` {
		t.Fatal(string(b))
	}
	_, err = discoverCall(ur, id)
	assertErr(t, err, ErrNotConfigured)
	assertErr(t, ur.SetAddress("http://request.exemple.fr"), ErrInvalidAddress)

	// An old server: « trop ancien » with the missing features, no exchange.
	versionOld := fixture(t, "version-old")
	old := startFake(t, func(recorded) reply { return jsonReply(200, versionOld) })
	mustOK(t, ur.SetAddress(old.URL))
	status = ur.Status(bg, id)
	if status.State != StateTooOld || !ptrIs(status.APIVersion, "1.0.0") || len(status.Missing) != 4 {
		t.Fatalf("%+v", status)
	}
	if n := len(old.requests()); n != 1 {
		t.Fatal(n)
	}
}

func Test_image_path_validation(t *testing.T) {
	ok, found := ParseUrImage("/w342/abc_D-1.jpg")
	if !found || ok.Size != "w342" || ok.File != "abc_D-1.jpg" {
		t.Fatal(ok)
	}
	if _, found := ParseUrImage("/w1280/x.webp"); !found {
		t.Fatal("webp refusé")
	}
	for _, bad := range []string{
		"/w342/../x.jpg",
		"../x.jpg",
		"/w9999/a.jpg",
		"/original/a.jpg",
		"/w342/a.svg",
		"/w342/a.jpg/x",
		"/w342/a%2Fb.jpg",
		"/w342/.jpg",
		"/w342/a.JPG.exe",
		"/w342",
		"/w342/" + strings.Repeat("a", 65) + ".jpg",
	} {
		if r, found := ParseUrImage(bad); found {
			t.Errorf("%q accepté : %+v", bad, r)
		}
	}
	s := func(v string) *string { return &v }
	r := imageRef(s("/abc.jpg"), []string{"w185", "w342"})
	if r == nil || r.Src != "http://requestimg.localhost/w185/abc.jpg" ||
		r.Srcset != "http://requestimg.localhost/w185/abc.jpg 185w, http://requestimg.localhost/w342/abc.jpg 342w" {
		t.Fatalf("%+v", r)
	}
	for _, p := range []*string{s("/a.svg"), s("abc.jpg"), s("/../a.jpg"), nil} {
		if r := imageRef(p, []string{"w185"}); r != nil {
			t.Errorf("%v : %+v", p, r)
		}
	}
}

func Test_image_is_fetched_with_bearer_and_cached(t *testing.T) {
	server, _ := requestLike(t, func(tok string) bool { return tok != "stale" }, nil)
	store := &emby.MemoryCredentialStore{}
	seed(t, store, embyUser, server.URL, "stale")
	cache := artworkcache.New(filepath.Join(t.TempDir(), "artwork"), 1<<20)
	ur := newTestService(t, store, server.URL).WithArtworkCache(cache)
	id := identity(embyUser)
	img, _ := ParseUrImage("/w342/abc.jpg")
	ct, data, err := ur.Image(bg, id, img)
	mustOK(t, err)
	want := []byte{0xFF, 0xD8, 0xFF, 0xE0, 7}
	if ct != "image/jpeg" || !reflect.DeepEqual(data, want) {
		t.Fatal(ct, data)
	}
	fetched := len(server.requests())
	// Second time from the disk cache: nothing sent.
	_, again, err := ur.Image(bg, id, img)
	mustOK(t, err)
	if !reflect.DeepEqual(again, want) || len(server.requests()) != fetched {
		t.Fatal(again, len(server.requests()))
	}
	var images []recorded
	for _, r := range server.requests() {
		if strings.HasPrefix(r.Path, "/api/images/") {
			images = append(images, r)
		}
	}
	if len(images) != 2 || !bearerIs(images[1], "tok-1") {
		t.Fatalf("401 then replay : %+v", images)
	}
}

// assertImages walks the JSON sent to the UI: every non-null `posterPath`,
// `backdropPath` or `profilePath` comes with its `poster`, `backdrop` or `profile`
// ImageRef.
func assertImages(t *testing.T, value any, at string) {
	t.Helper()
	switch v := value.(type) {
	case map[string]any:
		for _, pair := range [][2]string{{"posterPath", "poster"}, {"backdropPath", "backdrop"}, {"profilePath", "profile"}} {
			if p, ok := v[pair[0]]; ok && p != nil {
				ref, _ := v[pair[1]].(map[string]any)
				src, _ := ref["src"].(string)
				if !strings.HasPrefix(src, "http://requestimg.localhost/") {
					t.Errorf("%s.%s missing", at, pair[1])
				}
			}
		}
		for k, child := range v {
			assertImages(t, child, at+"."+k)
		}
	case []any:
		for i, child := range v {
			assertImages(t, child, fmt.Sprintf("%s[%d]", at, i))
		}
	}
}

func checkDecorated[T any, P interface {
	*T
	decorator
}](t *testing.T, name string) {
	t.Helper()
	value := parse[T](t, name)
	P(&value).decorate()
	b, err := json.Marshal(value)
	mustOK(t, err)
	var tree any
	mustOK(t, json.Unmarshal(b, &tree))
	assertImages(t, tree, name)
	if !strings.Contains(string(b), "requestimg.localhost") {
		t.Errorf("%s : aucune image", name)
	}
}

func Test_every_image_path_has_an_image_ref(t *testing.T) {
	checkDecorated[DiscoverResponse](t, "discover")
	checkDecorated[PagedMedia](t, "paged-media")
	checkDecorated[MediaSearchResults](t, "search")
	checkDecorated[MediaDetails](t, "media-movie")
	checkDecorated[MediaDetails](t, "media-tv")
	checkDecorated[RequestView](t, "request-view")
	checkDecorated[RequestPage](t, "request-page")
	checkDecorated[RequestDetail](t, "request-detail")
}

func Test_builds_list_queries(t *testing.T) {
	if got := RequestsQuery([]RequestStatus{"approved", "sent"}, 2); !reflect.DeepEqual(got,
		[]Param{{"mine", "true"}, {"page", "2"}, {"status", "approved,sent"}}) {
		t.Fatal(got)
	}
	if got := RequestsQuery(nil, 0); !reflect.DeepEqual(got, []Param{{"mine", "true"}, {"page", "1"}}) {
		t.Fatal(got)
	}
	var filters BrowseFilters
	mustOK(t, decodeStrict([]byte(`{"page":3,"genres":[16,35],"yearFrom":1990,"rating":7,"sort":"release"}`), &filters))
	if got := filters.Query(); !reflect.DeepEqual(got, []Param{
		{"page", "3"}, {"genres", "16,35"}, {"yearFrom", "1990"}, {"rating", "7"}, {"sort", "release"},
	}) {
		t.Fatal(got)
	}
}

func Test_token_follows_the_address(t *testing.T) {
	first, _ := requestLike(t, func(string) bool { return true }, nil)
	second, exchanges := requestLike(t, func(tok string) bool { return tok == "tok-1" }, nil)
	store := &emby.MemoryCredentialStore{}
	ur := newTestService(t, store, first.URL)
	id := identity(embyUser)
	_, err := discoverCall(ur, id)
	mustOK(t, err)
	mustOK(t, ur.SetAddress(second.URL))
	// The first server's token is never sent to the second one.
	if r := ur.RevocationFor(embyServer, embyUser); r != nil {
		t.Fatal("révocation vers le second serveur")
	}
	_, err = discoverCall(ur, id)
	mustOK(t, err)
	if exchanges.Load() != 1 {
		t.Fatal(exchanges.Load())
	}
	var paths []string
	for _, r := range second.requests() {
		_, has := bearer(r)
		paths = append(paths, fmt.Sprintf("%s %v", r.Path, has))
	}
	if !reflect.DeepEqual(paths, []string{"/api/version false", "/api/auth/emby-token false", "/api/discover true"}) {
		t.Fatal(paths)
	}
}

func Test_session_id_stays_one_path_segment(t *testing.T) {
	for in, want := range map[string]string{
		"s_01HZX3K8-a~": "s_01HZX3K8-a~",
		"../x?y=1":      "%2E%2E%2Fx%3Fy%3D1",
		"é":             "%C3%A9",
	} {
		if got := PathSegment(in); got != want {
			t.Errorf("%q : %q", in, got)
		}
	}
}

// stickyStore is a vault whose delete always fails.
type stickyStore struct{ emby.MemoryCredentialStore }

func (*stickyStore) Delete(string) error { return emby.NewCredentialStore("refus") }

func Test_sign_out_holds_when_the_vault_keeps_the_token(t *testing.T) {
	server, _ := requestLike(t, func(string) bool { return true }, nil)
	store := &stickyStore{}
	ur := newTestService(t, store, server.URL)
	id := identity(embyUser)
	_, err := discoverCall(ur, id)
	mustOK(t, err)
	if ur.SignOut(bg, id) == nil {
		t.Fatal("déconnexion sans erreur")
	}
	sent := len(server.requests())
	_, err = discoverCall(ur, id)
	assertErr(t, err, ErrSignedOut)
	img, _ := ParseUrImage("/w342/abc.jpg")
	_, _, err = ur.Image(bg, id, img)
	assertErr(t, err, ErrSignedOut)
	if len(server.requests()) != sent {
		t.Fatal("no HTTP call after sign-out")
	}
}

func Test_rate_limit_on_a_call_does_not_block_others(t *testing.T) {
	var hits atomic.Int64
	version, tokenFixture, discover := fixture(t, "version-ok"), fixture(t, "emby-token"), fixture(t, "discover")
	server := startFake(t, func(r recorded) reply {
		switch r.Path {
		case "/api/version":
			return jsonReply(200, version)
		case "/api/auth/emby-token":
			return jsonReply(200, tokenFixture)
		}
		if hits.Add(1) == 1 {
			return jsonReply(429, `{"error":"Trop"}`).header("Retry-After", "300")
		}
		return jsonReply(200, discover)
	})
	store := &emby.MemoryCredentialStore{}
	ur := newTestService(t, store, server.URL)
	id := identity(embyUser)
	_, err := discoverCall(ur, id)
	assertErr(t, err, NewRateLimited(300))
	_, err = discoverCall(ur, id)
	mustOK(t, err)
	if hits.Load() != 2 {
		t.Fatal(hits.Load())
	}
}

func Test_real_server_probe(t *testing.T) {
	t.Skip("ignoré comme en Rust (#[ignore]) : demande les vrais serveurs Emby et Request et le compte de test")
}

// writesServer is `writes_server`: accepts `tok-1`; `POST /api/requests` refuses
// tmdbId 403 (403) and 409 (409) with the server's `{"error":…}`, else answers a
// RequestDetail.
func writesServer(t *testing.T) *fakeServer {
	version, errFixture, detail := fixture(t, "version-ok"), fixture(t, "error"), fixture(t, "request-detail")
	tokenFixture := strings.ReplaceAll(fixture(t, "emby-token"), fixtureToken, "tok-1")
	return startFake(t, func(r recorded) reply {
		switch {
		case r.Path == "/api/version":
			return jsonReply(200, version)
		case r.Path == "/api/auth/emby-token":
			return jsonReply(200, tokenFixture)
		case !bearerIs(r, "tok-1"):
			return jsonReply(401, `{"error":"Session invalide"}`)
		case r.Method == "POST" && r.Path == "/api/requests" && strings.Contains(r.Body, `"tmdbId":403`):
			return jsonReply(403, errFixture)
		case r.Method == "POST" && r.Path == "/api/requests" && strings.Contains(r.Body, `"tmdbId":409`):
			return jsonReply(409, `{"error":"Quota de séries atteint"}`)
		case r.Method == "POST" && r.Path == "/api/requests":
			return jsonReply(201, detail)
		case r.Method == "POST" && r.Path == "/api/requests/102/cancel":
			return jsonReply(200, detail)
		}
		return emptyReply(404)
	})
}

func postRequest(ur *Service, r NewRequest) (RequestDetail, error) {
	return ServiceSend[RequestDetail](bg, ur, identity(embyUser), http.MethodPost, "/api/requests", r)
}

func Test_request_bodies_on_the_wire(t *testing.T) {
	server := writesServer(t)
	store := &emby.MemoryCredentialStore{}
	ur := newTestService(t, store, server.URL)
	list, all := SeasonsList(1, 2), SeasonsAll
	for _, r := range []NewRequest{
		{MediaType: MediaMovie, TmdbID: 603},
		{MediaType: MediaTv, TmdbID: 1399, Seasons: &list},
		{MediaType: MediaTv, TmdbID: 1399, Seasons: &all},
	} {
		// The contract answers a RequestDetail: the timeline comes back with it.
		d, err := postRequest(ur, r)
		mustOK(t, err)
		if len(d.Events) != 3 {
			t.Fatal(len(d.Events))
		}
	}
	var bodies []string
	for _, r := range server.requests() {
		if r.Method == "POST" && r.Path == "/api/requests" {
			bodies = append(bodies, r.Body)
		}
	}
	if !reflect.DeepEqual(bodies, []string{
		`{"type":"movie","tmdbId":603}`,
		`{"type":"tv","tmdbId":1399,"seasons":[1,2]}`,
		`{"type":"tv","tmdbId":1399,"seasons":"all"}`,
	}) {
		t.Fatal(bodies)
	}
}

func Test_quota_refusals_show_the_server_message(t *testing.T) {
	server := writesServer(t)
	store := &emby.MemoryCredentialStore{}
	ur := newTestService(t, store, server.URL)
	_, forbidden := postRequest(ur, NewRequest{MediaType: MediaMovie, TmdbID: 403})
	if forbidden == nil || forbidden.Error() != "Quota de films atteint" {
		t.Fatal(forbidden)
	}
	_, conflict := postRequest(ur, NewRequest{MediaType: MediaMovie, TmdbID: 409})
	assertErr(t, conflict, NewServer(409, "Quota de séries atteint"))
	if conflict.Error() != "Quota de séries atteint" {
		t.Fatal(conflict.Error())
	}
}

func Test_cancel_posts_to_the_request(t *testing.T) {
	server := writesServer(t)
	store := &emby.MemoryCredentialStore{}
	ur := newTestService(t, store, server.URL)
	detail, err := ServiceSend[RequestDetail](bg, ur, identity(embyUser), http.MethodPost, "/api/requests/102/cancel", nil)
	mustOK(t, err)
	if len(detail.Events) != 3 {
		t.Fatal(len(detail.Events))
	}
	for _, r := range server.requests() {
		if r.Path == "/api/requests/102/cancel" {
			if r.Method != "POST" || !bearerIs(r, "tok-1") || r.Body != "" {
				t.Fatalf("%+v", r)
			}
			return
		}
	}
	t.Fatal("annulation non envoyée")
}

// Test_token_never_in_logged_text: nothing the client prints or logs carries a
// token: errors, statuses, the recorded Authorization header and the exchange
// answer once redacted (`diagnostics::redact`, ported in internal/redact).
func Test_token_never_in_logged_text(t *testing.T) {
	server := writesServer(t)
	store := &emby.MemoryCredentialStore{}
	ur := newTestService(t, store, server.URL)
	id := identity(embyUser)
	_, err := postRequest(ur, NewRequest{MediaType: MediaMovie, TmdbID: 403})
	if err == nil {
		t.Fatal("refus attendu")
	}
	status := ur.Status(bg, id)
	revocation := ur.RevocationFor(embyServer, embyUser)
	if revocation == nil {
		t.Fatal("aucune session à révoquer")
	}
	var texts []string
	for _, verb := range []string{"%v", "%+v", "%#v"} {
		for _, v := range []any{err, status, id, id.EmbyToken, ur, revocation, *revocation} {
			texts = append(texts, fmt.Sprintf(verb, v))
		}
	}
	reqs := server.requests()
	var exchange recorded
	for _, r := range reqs {
		if r.Path == "/api/auth/emby-token" {
			exchange = r
		}
	}
	last := reqs[len(reqs)-1]
	if last.Authorization == nil || exchange.Body == "" {
		t.Fatal("en-tête ou échange non enregistré")
	}
	texts = append(texts,
		err.Error(),
		redact.Redact("Authorization: "+*last.Authorization),
		redact.Redact(exchange.Body),
		redact.Redact(fixture(t, "emby-token")),
	)
	for _, text := range texts {
		for _, secret := range []string{"tok-1", embyToken, fixtureToken} {
			if strings.Contains(text, secret) {
				t.Fatalf("%s in %s", secret, text)
			}
		}
	}
}
