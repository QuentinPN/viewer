package main

import (
	"context"
	"encoding/json"
	"testing"

	"viewer/internal/bridge"
)

// ur_open_trailer is registered and rejects a bad key with the bare string of the
// Rust TrailerError (a `serialize_str`), before any browser is started.
func TestUrOpenTrailerIsRegistered(t *testing.T) {
	reg := bridge.NewRegistry()
	registerCommands(reg, nil)
	_, err := bridge.New(reg).Invoke(context.Background(), "ur_open_trailer", json.RawMessage(`{"key":"a b"}`))
	if err == nil {
		t.Fatal("clé invalide acceptée")
	}
	if got := string(bridge.MarshalError(err)); got != `"bande-annonce invalide"` {
		t.Fatal(got)
	}
}
