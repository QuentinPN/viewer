package main

import (
	"maps"
	"net/http"
	"net/http/httptest"
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

// The CSP, directive by directive: tauri.conf.json's (removed with src-tauri/)
// with 'self' in connect-src and artwork from 'self' instead of the Tauri
// protocol hosts.
func Test_go_csp_directives(t *testing.T) {
	want := map[string]string{
		"default-src": "'self'",
		"img-src":     "'self' data: blob:",
		"style-src":   "'self' 'unsafe-inline'",
		"connect-src": "'self' ipc: http://ipc.localhost",
		"object-src":  "'none'",
		"base-uri":    "'none'",
		"form-action": "'none'",
	}
	if got := cspDirectives(contentSecurityPolicy); !maps.Equal(got, want) {
		t.Fatalf("CSP Wails %v\nattendu %v", got, want)
	}
}
