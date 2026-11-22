package main

import (
	"net/http"
	"net/http/httptest"
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
