package stremio

// Fake Stremio API and addons, serving the shared fixtures of
// contracts/stremio/fixtures. The collection's transportUrls are rewritten to
// `{srv}/addon-<n>/manifest.json` (the fixture hosts `addon-<n>.example` do not
// resolve; httptest listens on 127.0.0.1, which the local-http rule accepts), and
// `/addon-<n>/stream/{type}/{id}.json` answers the fixture's `response` for that
// addon: status 0 closes the connection.

import (
	"bytes"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"sync"
	"testing"
)

const fixtureKey = "REDACTED_AUTHKEY"

type request struct {
	Method string
	URI    string
	Body   string
}

// addonReply is one `addon-<n>` of streams_*.json.
type addonReply struct {
	Name     string          `json:"name"`
	Status   int             `json:"status"`
	Total    int             `json:"total"`
	Response json.RawMessage `json:"response"`
}

type fakeServer struct {
	URL string
	t   testing.TB

	mu       sync.Mutex
	requests []request
	// login and collection are the API answers (HTTP 200); nil handler: 200 + body.
	login      string
	collection string
	// streams by content type, then `addon-<n>`.
	streams map[ContentType]map[string]addonReply
	// handler, when set, answers every request instead of the routes above.
	handler http.HandlerFunc
}

func newFakeServer(t testing.TB) *fakeServer {
	t.Helper()
	f := &fakeServer{t: t, login: string(fixture(t, "login_ok")), streams: map[ContentType]map[string]addonReply{}}
	for ct, name := range map[ContentType]string{Movie: "streams_movie", Series: "streams_series"} {
		var m map[string]addonReply
		if err := json.Unmarshal(fixture(t, name), &m); err != nil {
			t.Fatal(err)
		}
		f.streams[ct] = m
	}
	srv := httptest.NewServer(http.HandlerFunc(f.serve))
	t.Cleanup(srv.Close)
	f.URL = srv.URL
	f.collection = f.rewrittenCollection(nil)
	return f
}

// rewrittenCollection is addon_collection.json with every transportUrl pointing at
// the fake server; extra entries are inserted before the addon of the same index.
func (f *fakeServer) rewrittenCollection(extra map[int]map[string]any) string {
	f.t.Helper()
	var body map[string]any
	if err := json.Unmarshal(fixture(f.t, "addon_collection"), &body); err != nil {
		f.t.Fatal(err)
	}
	result := body["result"].(map[string]any)
	var addons []any
	for i, a := range result["addons"].([]any) {
		if e, ok := extra[i]; ok {
			addons = append(addons, e)
		}
		a.(map[string]any)["transportUrl"] = f.URL + "/addon-" + strconv.Itoa(i) + "/manifest.json"
		addons = append(addons, a)
	}
	result["addons"] = addons
	out, err := json.Marshal(body)
	if err != nil {
		f.t.Fatal(err)
	}
	return string(out)
}

func (f *fakeServer) log() []request {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]request(nil), f.requests...)
}

func (f *fakeServer) serve(w http.ResponseWriter, r *http.Request) {
	body, _ := io.ReadAll(r.Body)
	f.mu.Lock()
	f.requests = append(f.requests, request{Method: r.Method, URI: r.RequestURI, Body: string(body)})
	handler, login, collection := f.handler, f.login, f.collection
	f.mu.Unlock()
	if handler != nil {
		handler(w, r)
		return
	}
	switch {
	case r.Method == http.MethodPost && r.URL.Path == "/api/login":
		writeJSON(w, http.StatusOK, login)
	case r.Method == http.MethodPost && r.URL.Path == "/api/addonCollectionGet":
		writeJSON(w, http.StatusOK, collection)
	case r.Method == http.MethodGet && strings.Contains(r.URL.Path, "/stream/"):
		f.serveStream(w, r)
	default:
		http.NotFound(w, r)
	}
}

// serveStream answers `/addon-<n>/stream/{type}/{id}.json` from the fixtures.
func (f *fakeServer) serveStream(w http.ResponseWriter, r *http.Request) {
	parts := strings.Split(strings.TrimPrefix(r.URL.Path, "/"), "/")
	if len(parts) != 4 || parts[1] != "stream" {
		http.NotFound(w, r)
		return
	}
	reply, ok := f.streams[ContentType(parts[2])][parts[0]]
	if !ok {
		http.NotFound(w, r)
		return
	}
	if reply.Status == 0 {
		closeConnection(w)
		return
	}
	writeJSON(w, reply.Status, string(reply.Response))
}

func writeJSON(w http.ResponseWriter, status int, body string) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Connection", "close")
	w.WriteHeader(status)
	_, _ = io.WriteString(w, body)
}

// closeConnection drops the connection without an answer (an unreachable addon).
func closeConnection(w http.ResponseWriter) {
	conn, _, err := w.(http.Hijacker).Hijack()
	if err == nil {
		_ = conn.Close()
	}
}

// addonsOf parses the fake server's collection as the client would.
func (f *fakeServer) addons(t testing.TB) []Addon {
	t.Helper()
	var body struct {
		Result struct {
			Addons []collectionEntry `json:"addons"`
		} `json:"result"`
	}
	if err := json.Unmarshal([]byte(f.collection), &body); err != nil {
		t.Fatal(err)
	}
	var out []Addon
	for _, e := range body.Result.Addons {
		a, err := ParseAddon(e.TransportURL, e.Manifest)
		if err != nil {
			t.Fatal(err)
		}
		out = append(out, a)
	}
	return out
}

func addonNamed(t testing.TB, addons []Addon, name string) Addon {
	t.Helper()
	for _, a := range addons {
		if a.Name == name {
			return a
		}
	}
	t.Fatalf("addon %q absent", name)
	return Addon{}
}

// captureLogs sends slog's default logger to a buffer for the test, at every level.
func captureLogs(t testing.TB) *syncBuffer {
	t.Helper()
	buf := &syncBuffer{}
	prev := slog.Default()
	slog.SetDefault(slog.New(slog.NewTextHandler(buf, &slog.HandlerOptions{Level: slog.LevelDebug})))
	t.Cleanup(func() { slog.SetDefault(prev) })
	return buf
}

type syncBuffer struct {
	mu  sync.Mutex
	buf bytes.Buffer
}

func (b *syncBuffer) Write(p []byte) (int, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.buf.Write(p)
}

func (b *syncBuffer) String() string {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.buf.String()
}

// assertNoSecret fails when text holds the server address, an addon path, the
// fixture key or a stream URL.
func assertNoSecret(t testing.TB, what, text, serverURL string) {
	t.Helper()
	host := strings.TrimPrefix(serverURL, "http://")
	for _, s := range []string{host, "/addon-", fixtureKey, "debrid.example", "REDACTED/", "://", "hunter2"} {
		if strings.Contains(text, s) {
			t.Errorf("%s contient %q : %s", what, s, text)
		}
	}
}
