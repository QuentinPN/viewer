package emby

import (
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"testing"
)

// emby/tests.rs errors_serialize_with_kind_and_french_message
func Test_errors_serialize_with_kind_and_french_message(t *testing.T) {
	js := string(marshal(t, ErrInsecureRemoteURL))
	if !strings.Contains(js, `"kind":"insecureRemoteUrl"`) {
		t.Fatal(js)
	}
	if !strings.Contains(js, "https://") {
		t.Fatal(js)
	}
}

func TestEmbyErrorIsAndKindOf(t *testing.T) {
	var err error = NewServer(503)
	if !errors.Is(err, NewServer(500)) {
		t.Fatal("Is compares the kind only")
	}
	if errors.Is(err, ErrNotFound) {
		t.Fatal("another kind")
	}
	wrapped := fmt.Errorf("contexte : %w", ErrNotFound)
	if k, ok := KindOf(wrapped); !ok || k != KindNotFound {
		t.Fatalf("KindOf = %v %v", k, ok)
	}
	if !errors.Is(wrapped, ErrNotFound) {
		t.Fatal("wrapped")
	}
	byPtr := &EmbyError{Kind: KindTimeout}
	if k, ok := KindOf(byPtr); !ok || k != KindTimeout {
		t.Fatalf("KindOf pointer = %v %v", k, ok)
	}
	if _, ok := KindOf(errors.New("autre")); ok {
		t.Fatal("not an EmbyError")
	}
	// The shape holds by value and by pointer.
	if string(marshal(t, byPtr)) != string(marshal(t, *byPtr)) {
		t.Fatal("pointer and value marshal differently")
	}
}

// golden.rs error_cases: every variant with a fixed text.
func TestGolden_errors_emby_error_all_kinds(t *testing.T) {
	all := []EmbyError{
		NewInvalidURL("adresse vide"),
		ErrInsecureRemoteURL,
		NewUnreachable("connexion refusée"),
		ErrTimeout,
		ErrInvalidCredentials,
		ErrForbidden,
		NewServer(503),
		NewInvalidResponse("JSON illisible"),
		ErrNotFound,
		ErrNotSignedIn,
		ErrSessionExpired,
		ErrAccountUnavailable,
		ErrServerMismatch,
		ErrCancelled,
		NewCredentialStore("accès refusé"),
		NewPlaybackRefused("lecture non autorisée pour ce compte"),
		ErrNoCompatibleStream,
		NewPlayer("source invalide"),
		NewSettings("débit de lecture non proposé"),
		NewSync("TV introuvable"),
	}
	assertTypedGolden(t, "errors.emby_error.all_kinds.json", all)
}

// Errors of the service and of playback: the shape is ported here; the code that
// raises most of them (client, session, playback) comes in wave 2. The ones raised
// by this package are produced by it in server_url_test.go and quality_test.go.
func TestGolden_errors_service_and_playback_shapes(t *testing.T) {
	cases := map[string]EmbyError{
		"errors.service.invalid_credentials.json":             ErrInvalidCredentials,
		"errors.service.invalid_id.json":                      NewInvalidResponse("identifiant invalide"),
		"errors.service.invalid_playlist_name.json":           NewInvalidResponse("nom de playlist invalide"),
		"errors.service.not_found.json":                       ErrNotFound,
		"errors.service.not_signed_in.json":                   ErrNotSignedIn,
		"errors.service.server_error.json":                    NewServer(503),
		"errors.service.session_expired.json":                 ErrSessionExpired,
		"errors.service.unknown_library.json":                 ErrNotFound,
		"errors.playback.fallback_still_offers_the_file.json": ErrNoCompatibleStream,
		"errors.playback.resume_without_item.json":            ErrNotFound,
		"errors.playback.unknown_item.json":                   ErrNotFound,
	}
	for name, e := range cases {
		t.Run(name, func(t *testing.T) { assertTypedGolden(t, name, e) })
	}
}

// The message of invalid_response holds a serde error text: compare `kind` only.
func TestGolden_errors_service_invalid_response_kind(t *testing.T) {
	var got struct {
		Kind string `json:"kind"`
	}
	data := readGolden(t, "errors.service.invalid_response.json")
	if err := json.Unmarshal(data, &got); err != nil {
		t.Fatal(err)
	}
	if ErrorKind(got.Kind) != KindInvalidResponse {
		t.Fatalf("kind %q", got.Kind)
	}
}
