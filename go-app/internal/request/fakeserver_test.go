package request

// Fake Request server, port of `FakeServer`, `Reply` and `request_like`
// (src-tauri/src/request/tests.rs). Like the Rust one it records every request
// (method, raw path with its query as sent, Authorization, body) and closes the
// connection after each answer, so the transport never replays a request on a
// reused connection.
//
// The fixtures are read where Rust's `include_str!` reads them, the shared contract
// fixtures of contracts/request/fixtures (validated against openapi.json by the
// Vitest contract test), not copied into go-app/testdata.

import (
	"encoding/json"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"

	"viewer/internal/emby"
)

const (
	embyUser     = "4a1f0e2b9c8d4e7fa6b5c4d3e2f1a0b9"
	embyToken    = "emby-token-0123456789abcdef"
	embyServer   = "d969e3d9465b49ec8f73a5f1eab2e86f"
	fixtureToken = "ur_sess_EXEMPLE_NON_SECRET_0000000000000000"
)

func fixture(t testing.TB, name string) string {
	t.Helper()
	data, err := os.ReadFile(filepath.Join("..", "..", "..", "contracts", "request", "fixtures", name+".json"))
	if err != nil {
		t.Fatalf("fixture %s : %v", name, err)
	}
	return string(data)
}

// recorded is `Recorded`.
type recorded struct {
	Method        string
	Path          string
	Authorization *string
	Body          string
	Header        http.Header
}

// reply is `Reply`.
type reply struct {
	status      int
	contentType string
	headers     [][2]string
	body        []byte
}

func jsonReply(status int, body string) reply {
	return reply{status: status, contentType: "application/json", body: []byte(body)}
}

func emptyReply(status int) reply { return jsonReply(status, "") }

func (r reply) header(name, value string) reply {
	r.headers = append(r.headers, [2]string{name, value})
	return r
}

type route func(r recorded) reply

type fakeServer struct {
	URL string
	mu  sync.Mutex
	log []recorded
}

// startFake is `FakeServer::start`; the server stops with the test. Unlike the Rust
// server, requests may be answered concurrently.
func startFake(t testing.TB, route route) *fakeServer {
	t.Helper()
	f := &fakeServer{}
	srv := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		req := recorded{Method: r.Method, Path: r.RequestURI, Body: string(body), Header: r.Header.Clone()}
		if v, ok := r.Header["Authorization"]; ok {
			a := v[0]
			req.Authorization = &a
		}
		rep := route(req)
		f.mu.Lock()
		f.log = append(f.log, req)
		f.mu.Unlock()
		w.Header().Set("Content-Type", rep.contentType)
		w.Header().Set("Content-Length", strconv.Itoa(len(rep.body)))
		for _, h := range rep.headers {
			w.Header().Set(h[0], h[1])
		}
		w.WriteHeader(rep.status)
		_, _ = w.Write(rep.body)
	}))
	srv.Config.SetKeepAlivesEnabled(false)
	srv.Start()
	t.Cleanup(srv.Close)
	f.URL = srv.URL
	return f
}

func (f *fakeServer) requests() []recorded {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]recorded(nil), f.log...)
}

func (f *fakeServer) client() *Client { return NewClient(f.URL, "9.9.9") }

// deadAddress is the address of a port nobody listens on any more.
func deadAddress(t testing.TB) string {
	t.Helper()
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	addr := "http://" + l.Addr().String()
	_ = l.Close()
	return addr
}

func token(value string) SessionToken { return NewSessionToken(value) }

func identity(userID string) Identity {
	return Identity{ServerID: embyServer, UserID: userID, EmbyToken: emby.NewAccessToken(embyToken)}
}

// stored is the session token kept for userID, without its issuer.
func stored(t testing.TB, store emby.CredentialStore, userID string) *string {
	t.Helper()
	tok, err := store.Load(Account(embyServer, userID))
	if err != nil {
		t.Fatal(err)
	}
	if tok == nil {
		return nil
	}
	_, v, ok := strings.Cut(tok.Expose(), " ")
	if !ok {
		return nil
	}
	return &v
}

// seed stores a token for issuer, as the service keeps it.
func seed(t testing.TB, store emby.CredentialStore, userID, issuer, value string) {
	t.Helper()
	address, err := NormalizeAddress(issuer)
	if err != nil {
		t.Fatal(err)
	}
	if err := store.Save(Account(embyServer, userID), emby.NewAccessToken(address+" "+value)); err != nil {
		t.Fatal(err)
	}
}

func bearer(r recorded) (string, bool) {
	if r.Authorization == nil {
		return "", false
	}
	return strings.CutPrefix(*r.Authorization, "Bearer ")
}

func bearerIs(r recorded, want string) bool {
	b, ok := bearer(r)
	return ok && b == want
}

// requestLike is `request_like`: exchange n answers token `tok-n`; a Bearer
// call answers 401 unless accept takes its token. onBearer, when set, runs first
// for every Bearer call (a test barrier).
func requestLike(t testing.TB, accept func(string) bool, onBearer func(recorded)) (*fakeServer, *atomic.Int64) {
	t.Helper()
	exchanges := new(atomic.Int64)
	var session struct {
		Viewer json.RawMessage `json:"viewer"`
	}
	if err := json.Unmarshal([]byte(fixture(t, "emby-token")), &session); err != nil {
		t.Fatal(err)
	}
	viewer := `{"viewer":` + string(session.Viewer) + `}`
	tokenFixture := fixture(t, "emby-token")
	version := fixture(t, "version-ok")
	discover := fixture(t, "discover")
	server := startFake(t, func(r recorded) reply {
		path, _, _ := strings.Cut(r.Path, "?")
		switch path {
		case "/api/version":
			return jsonReply(200, version)
		case "/api/auth/emby-token":
			n := exchanges.Add(1)
			return jsonReply(200, strings.ReplaceAll(tokenFixture, fixtureToken, "tok-"+strconv.FormatInt(n, 10)))
		}
		if onBearer != nil {
			onBearer(r)
		}
		if b, ok := bearer(r); !ok || !accept(b) {
			return jsonReply(401, `{"error":"Session invalide"}`)
		}
		switch path {
		case "/api/auth/me":
			return jsonReply(200, viewer)
		case "/api/auth/logout":
			return emptyReply(204)
		case "/api/discover":
			return jsonReply(200, discover)
		case "/api/images/w342/abc.jpg":
			return reply{status: 200, contentType: "image/jpeg", body: []byte{0xFF, 0xD8, 0xFF, 0xE0, 7}}
		}
		return emptyReply(404)
	})
	return server, exchanges
}

func tempSettings(t testing.TB) string {
	t.Helper()
	return filepath.Join(t.TempDir(), "request.json")
}

func newTestService(t testing.TB, store emby.CredentialStore, address string) *Service {
	t.Helper()
	svc := NewService(store, tempSettings(t), "PC-TEST", "9.9.9")
	if address != "" {
		if err := svc.SetAddress(address); err != nil {
			t.Fatal(err)
		}
	}
	return svc
}
