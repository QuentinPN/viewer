package main

import (
	"encoding/json"
	"maps"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestWithCSPSetsHeader(t *testing.T) {
	h := withCSP(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	}))
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/", nil))
	if got := rec.Header().Get("Content-Security-Policy"); got != contentSecurityPolicy {
		t.Fatalf("CSP = %q", got)
	}
	if rec.Code != http.StatusNoContent {
		t.Fatalf("next handler not called: %d", rec.Code)
	}
}

func cspDirectives(policy string) map[string]string {
	out := map[string]string{}
	for _, d := range strings.Split(policy, ";") {
		name, value, _ := strings.Cut(strings.TrimSpace(d), " ")
		out[name] = value
	}
	return out
}

// The Wails CSP is tauri.conf.json's with only the two documented changes: 'self'
// in connect-src, and artwork from 'self' instead of the Tauri protocol hosts.
func Test_go_csp_is_tauris_with_artwork_from_self(t *testing.T) {
	raw, err := os.ReadFile(filepath.Join("..", "..", "..", "src-tauri", "tauri.conf.json"))
	if err != nil {
		t.Fatal(err)
	}
	var conf struct {
		App struct {
			Security struct {
				CSP string `json:"csp"`
			} `json:"security"`
		} `json:"app"`
	}
	if err := json.Unmarshal(raw, &conf); err != nil || conf.App.Security.CSP == "" {
		t.Fatal("CSP introuvable dans tauri.conf.json", err)
	}
	want := cspDirectives(conf.App.Security.CSP)
	want["connect-src"] = "'self' " + want["connect-src"]
	want["img-src"] = "'self' data: blob:"
	if got := cspDirectives(contentSecurityPolicy); !maps.Equal(got, want) {
		t.Fatalf("CSP Wails %v\nattendu %v", got, want)
	}
}
