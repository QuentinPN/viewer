package emby

import (
	"encoding/json"
	"testing"

	"viewer/internal/bridge"
)

// What the frontend receives: bridge.Invoke rejects with bridge.MarshalError(err).
// An EmbyError must reach it as the golden {kind, message}, not as a bare string.
func TestGolden_errors_through_the_bridge(t *testing.T) {
	_, invalidURL := ParseServerURL("")
	_, insecure := ParseServerURL("http://media.example.com")
	_, notOffered := (QualityCaps{Local: u64(1)}).Validated()
	cases := map[string]error{
		"errors.service.invalid_url.json":                     invalidURL,
		"errors.service.insecure_remote_url.json":             insecure,
		"errors.service.quality_not_offered.json":             notOffered,
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
	for name, err := range cases {
		t.Run(name, func(t *testing.T) {
			got := bridge.MarshalError(err)
			if e := orderedEqual(readGolden(t, name), got); e != nil {
				t.Errorf("%v\ngo: %s", e, got)
			}
			// By pointer and wrapped, the shape holds.
			if e, ok := err.(EmbyError); ok {
				if e2 := orderedEqual(readGolden(t, name), bridge.MarshalError(&e)); e2 != nil {
					t.Errorf("pointer: %v", e2)
				}
			}
		})
	}
	// invalid_response holds a serde error text: compare `kind` only (index.json).
	var kind errorJSON
	if err := json.Unmarshal(bridge.MarshalError(NewInvalidResponse("expected value at line 1 column 1")), &kind); err != nil ||
		kind.Kind != KindInvalidResponse {
		t.Fatalf("invalid_response through the bridge: %+v %v", kind, err)
	}
	var golden errorJSON
	if err := json.Unmarshal(readGolden(t, "errors.service.invalid_response.json"), &golden); err != nil || golden.Kind != kind.Kind {
		t.Fatalf("golden kind %q", golden.Kind)
	}
	all := []error{
		NewInvalidURL("adresse vide"), ErrInsecureRemoteURL, NewUnreachable("connexion refusée"), ErrTimeout,
		ErrInvalidCredentials, ErrForbidden, NewServer(503), NewInvalidResponse("JSON illisible"), ErrNotFound,
		ErrNotSignedIn, ErrSessionExpired, ErrAccountUnavailable, ErrServerMismatch, ErrCancelled,
		NewCredentialStore("accès refusé"), NewPlaybackRefused("lecture non autorisée pour ce compte"),
		ErrNoCompatibleStream, NewPlayer("source invalide"), NewSettings("débit de lecture non proposé"),
		NewSync("TV introuvable"),
	}
	out := []byte("[")
	for i, err := range all {
		if i > 0 {
			out = append(out, ',')
		}
		out = append(out, bridge.MarshalError(err)...)
	}
	out = append(out, ']')
	if e := orderedEqual(readGolden(t, "errors.emby_error.all_kinds.json"), out); e != nil {
		t.Errorf("all kinds: %v\ngo: %s", e, out)
	}
}
