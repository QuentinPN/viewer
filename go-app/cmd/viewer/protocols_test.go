package main

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"

	"viewer/internal/emby"
	"viewer/internal/request"
)

// Same ids as the fixtures of internal/emby (testdata/fixtures).
const (
	e2eUserID   = "4a1f0e2b9c8d4e7fa6b5c4d3e2f1a0b9"
	e2eServerID = "d969e3d9465b49ec8f73a5f1eab2e86f"
	e2eToken    = "0123456789abcdef0123456789abcdef"
	e2ePassword = "s3cret-pw"
)

// appImageOrigins switches the builders to the app's origin, as main does, for one
// test.
func appImageOrigins(t *testing.T) {
	t.Helper()
	useAppImageOrigins()
	t.Cleanup(func() {
		emby.SetImageOrigin(emby.TauriImageOrigin)
		request.SetImageOrigin(request.TauriOrigin)
	})
}

func e2eFixture(t *testing.T, name string) string {
	t.Helper()
	b, err := os.ReadFile(filepath.Join("..", "..", "testdata", "fixtures", name+".json"))
	if err != nil {
		t.Fatal(err)
	}
	return string(b)
}

// startArtworkEmby is a local fake Emby (no real server): sign-in, views, and the
// Primary image of library 10, the only view of views.json with an image tag.
func startArtworkEmby(t *testing.T) (*httptest.Server, *[]string) {
	t.Helper()
	public, auth, user, views := e2eFixture(t, "public_info"), e2eFixture(t, "auth_result"),
		e2eFixture(t, "user"), e2eFixture(t, "views")
	var mu sync.Mutex
	var images []string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		token := strings.Contains(r.Header.Get("X-Emby-Authorization"), `Token="`+e2eToken+`"`)
		w.Header().Set("Connection", "close")
		answer := func(contentType, payload string) {
			w.Header().Set("Content-Type", contentType)
			_, _ = io.WriteString(w, payload)
		}
		switch {
		case r.Method == "GET" && r.URL.Path == "/emby/System/Info/Public":
			answer("application/json", public)
		case r.Method == "POST" && r.URL.Path == "/emby/Users/AuthenticateByName" &&
			strings.Contains(string(body), `"Pw":"`+e2ePassword+`"`):
			answer("application/json", auth)
		case !token:
			w.WriteHeader(http.StatusUnauthorized)
		case r.URL.Path == "/emby/Users/"+e2eUserID:
			answer("application/json", user)
		case r.URL.Path == "/emby/Users/"+e2eUserID+"/Views":
			answer("application/json", views)
		case r.URL.Path == "/emby/Items/10/Images/Primary":
			mu.Lock()
			images = append(images, r.URL.RawQuery)
			mu.Unlock()
			// The JPEG signature the artwork cache checks (ADR 0019).
			answer("image/jpeg", "\xFF\xD8\xFFlibrary-10-artwork")
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	t.Cleanup(srv.Close)
	return srv, &images
}

// End to end without a real server: the ImageRef that emby_libraries hands the UI
// points at the app's own origin, and that very URL, through the asset server
// middleware of main.go, comes back as the image the fake Emby served.
func Test_go_library_artwork_is_served_on_the_app_origin(t *testing.T) {
	appImageOrigins(t)
	srv, fetched := startArtworkEmby(t)
	a, b, _, _ := testApp(t, true)
	args, _ := json.Marshal(map[string]string{"url": srv.URL, "username": "quentin", "password": e2ePassword})
	if _, err := invoke(t, b, "emby_sign_in", string(args)); err != "" {
		t.Fatal(err)
	}
	out, err := invoke(t, b, "emby_libraries", "")
	if err != "" {
		t.Fatal(err)
	}
	var libraries []emby.Library
	if err := json.Unmarshal([]byte(out), &libraries); err != nil {
		t.Fatal(err)
	}
	var image *emby.ImageRef
	for _, l := range libraries {
		if l.ID == "10" {
			image = l.Image
		}
	}
	if image == nil {
		t.Fatalf("pas d'illustration pour la bibliothèque 10 : %s", out)
	}
	wantSrc := "http://wails.localhost/emby-img/" + e2eServerID + "/10/Primary?tag=56f6b8b2_6384&maxWidth=400"
	if image.Src != wantSrc {
		t.Fatalf("src %q, attendu %q", image.Src, wantSrc)
	}

	next := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(299) })
	h := withCSP(withProtocols(a, next))
	urls := []string{image.Src}
	for _, candidate := range strings.Split(image.Srcset, ", ") {
		u, _, _ := strings.Cut(candidate, " ")
		urls = append(urls, u)
	}
	for _, u := range urls {
		rec := httptest.NewRecorder()
		h.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, u, nil))
		if rec.Code != http.StatusOK {
			t.Fatalf("%s : %d", u, rec.Code)
		}
		if got := rec.Header().Get("Content-Type"); got != "image/jpeg" {
			t.Errorf("%s : Content-Type %q", u, got)
		}
		if got := rec.Header().Get("Cache-Control"); got != "max-age=31536000, immutable" {
			t.Errorf("%s : Cache-Control %q", u, got)
		}
		if got := rec.Header().Get("Content-Security-Policy"); got != contentSecurityPolicy {
			t.Errorf("%s : CSP %q", u, got)
		}
		if got := rec.Body.String(); got != "\xFF\xD8\xFFlibrary-10-artwork" {
			t.Errorf("%s : corps %q", u, got)
		}
	}
	// One fetch per width: src repeats the first width, which the artwork cache answers.
	if got := strings.Join(*fetched, " | "); got != "tag=56f6b8b2_6384&maxWidth=400&quality=90 | tag=56f6b8b2_6384&maxWidth=640&quality=90 | tag=56f6b8b2_6384&maxWidth=800&quality=90" {
		t.Errorf("requêtes d'image : %s", got)
	}

	// Another server's id in the same path: refused like Rust (ADR 0012).
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, strings.Replace(image.Src, e2eServerID, "otherserver", 1), nil))
	if rec.Code != http.StatusForbidden {
		t.Fatalf("autre serveur : %d", rec.Code)
	}
}

// A Request URL on the app's origin (what imageRefAt builds from the origin
// useAppImageOrigins sets, see internal/request) reaches urImage, 401 signed
// out, and not the frontend assets.
func Test_go_request_artwork_url_reaches_its_protocol(t *testing.T) {
	a, _, _, _ := testApp(t, true)
	src := appOrigin + urImagePrefix + "/w185/abc.jpg"
	if src != "http://wails.localhost/requestimg/w185/abc.jpg" {
		t.Fatal(src)
	}
	next := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(299) })
	rec := httptest.NewRecorder()
	withProtocols(a, next).ServeHTTP(rec, httptest.NewRequest(http.MethodGet, src, nil))
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("%s : %d", src, rec.Code)
	}
}
