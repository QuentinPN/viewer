package stremio

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// fixture reads a shared contract fixture of contracts/stremio/fixtures, not a
// copy in go-app/testdata (Android TV reads the same files).
func fixture(t testing.TB, name string) []byte {
	t.Helper()
	data, err := os.ReadFile(filepath.Join("..", "..", "..", "contracts", "stremio", "fixtures", name+".json"))
	if err != nil {
		t.Fatalf("fixture %s : %v", name, err)
	}
	return data
}

// collectionEntry is one `result.addons[]` of addon_collection.json.
type collectionEntry struct {
	TransportURL string          `json:"transportUrl"`
	Manifest     json.RawMessage `json:"manifest"`
}

func collectionEntries(t testing.TB) []collectionEntry {
	t.Helper()
	var body struct {
		Result struct {
			Addons []collectionEntry `json:"addons"`
		} `json:"result"`
	}
	if err := json.Unmarshal(fixture(t, "addon_collection"), &body); err != nil {
		t.Fatal(err)
	}
	return body.Result.Addons
}

func TestStreamID(t *testing.T) {
	ok := []struct {
		imdb            string
		season, episode int
		want            string
	}{
		{"tt0111161", 0, 0, "tt0111161"},
		{"tt0944947", 1, 2, "tt0944947:1:2"},
		{"tt12345", 10, 100, "tt12345:10:100"},
	}
	for _, c := range ok {
		got, err := StreamID(c.imdb, c.season, c.episode)
		if err != nil || got != c.want {
			t.Errorf("StreamID(%q,%d,%d) = %q, %v ; attendu %q", c.imdb, c.season, c.episode, got, err, c.want)
		}
	}
	bad := []struct {
		imdb            string
		season, episode int
	}{
		{"123", 0, 0},
		{"tt1", 1, 0},
		{"tt0944947", 1, 0},
		{"tt0944947", 0, 2},
		{"tt0944947", -1, -1},
		{"tt1234", 0, 0},
		{"TT0111161", 0, 0},
		{"tt0111161/../x", 0, 0},
		{"tt0111161:1:2", 0, 0},
		{"", 0, 0},
	}
	for _, c := range bad {
		if got, err := StreamID(c.imdb, c.season, c.episode); !errors.Is(err, ErrInvalidID) {
			t.Errorf("StreamID(%q,%d,%d) = %q, %v ; attendu ErrInvalidID", c.imdb, c.season, c.episode, got, err)
		}
	}
}

func TestParseAddonRejectsPublicHTTP(t *testing.T) {
	manifest := json.RawMessage(`{"id":"a","version":"1.0.0","name":"Local","resources":["stream"],"types":["movie"]}`)
	refused := []string{
		"http://example.com/x/manifest.json",
		"http://8.8.8.8/manifest.json",
		"ftp://192.168.1.14/manifest.json",
		"https://example.com/x/catalog.json",
		"https://user:pw@example.com/x/manifest.json",
		"https://example.com/x/manifest.json?key=1",
		"https://example.com/x/manifest.json#frag",
		"/x/manifest.json",
		"https:///manifest.json",
	}
	for _, u := range refused {
		_, err := ParseAddon(u, manifest)
		var e Error
		if !errors.As(err, &e) || e.Kind != KindInvalid || e.Addon != "Local" {
			t.Errorf("ParseAddon(%q) : %v", u, err)
			continue
		}
		if strings.Contains(err.Error(), "example.com") || strings.Contains(err.Error(), "manifest") {
			t.Errorf("ParseAddon(%q) : l'erreur montre l'adresse : %v", u, err)
		}
	}
	accepted := map[string]string{
		"http://192.168.1.14:7000/manifest.json":        "http://192.168.1.14:7000",
		"http://127.0.0.1:11470/manifest.json":          "http://127.0.0.1:11470",
		"https://addon.example/cfg%3D%3D/manifest.json": "https://addon.example/cfg%3D%3D",
	}
	for u, base := range accepted {
		a, err := ParseAddon(u, manifest)
		if err != nil {
			t.Errorf("ParseAddon(%q) : %v", u, err)
			continue
		}
		if a.base != base || a.Name != "Local" || a.ID != "a" || a.Version != "1.0.0" {
			t.Errorf("ParseAddon(%q) = base %q, %q %q %q", u, a.base, a.Name, a.ID, a.Version)
		}
	}
}

func TestParseAddonRefusesABadManifest(t *testing.T) {
	for _, m := range []string{`not json`, `{"id":"a","version":"1","name":""}`, `{"name":1}`, `[]`} {
		_, err := ParseAddon("https://addon.example/x/manifest.json", json.RawMessage(m))
		var e Error
		if !errors.As(err, &e) || e.Kind != KindServer {
			t.Errorf("manifeste %s : %v", m, err)
		}
	}
}

const (
	secretSegment = "c2VjcmV0LWRlYnJpZC1rZXk"
	secretKey     = "REDACTED_AUTHKEY"
	secretStream  = "https://debrid.example/" + secretSegment + "/file.mkv"
	secretHeader  = "Bearer hdr-" + secretSegment
)

func TestSecretsMasked(t *testing.T) {
	addon, err := ParseAddon("https://addon-4.example/"+secretSegment+"/manifest.json",
		json.RawMessage(`{"id":"a","version":"1","name":"Comet","resources":["stream"],"types":["movie"]}`))
	if err != nil {
		t.Fatal(err)
	}
	stream := Stream{Name: "Comet", Description: "1080p", url: secretStream, headers: map[string]string{"Authorization": secretHeader}}
	key := NewAuthKey(secretKey)
	values := map[string]any{
		"Addon":         addon,
		"*Addon":        &addon,
		"Stream":        stream,
		"AuthKey":       key,
		"[]Addon":       []Addon{addon},
		"StreamsResult": StreamsResult{Streams: []Stream{stream}},
	}
	secrets := []string{secretSegment, secretKey, "debrid.example", "addon-4.example", "hdr-"}
	for name, v := range values {
		for _, verb := range []string{"%v", "%+v", "%#v", "%s", "%q", "%x", "%X", "%d"} {
			for _, out := range []string{fmt.Sprintf(verb, v), fmt.Sprint(v)} {
				for _, s := range secrets {
					if strings.Contains(out, s) || strings.Contains(strings.ToLower(out), fmt.Sprintf("%x", s)) {
						t.Errorf("%s %s : %s", name, verb, out)
					}
				}
			}
		}
		if out, err := json.Marshal(v); err == nil {
			t.Errorf("%s : sérialisé %s", name, out)
		}
	}
	if stream.URL() != secretStream || stream.Headers()["Authorization"] != secretHeader {
		t.Fatal("accesseurs")
	}
	stream.Headers()["Authorization"] = "x"
	if stream.Headers()["Authorization"] != secretHeader {
		t.Fatal("Headers doit rendre une copie")
	}
}

func TestErrorsNeverShowAnAddress(t *testing.T) {
	errs := []Error{
		{Kind: KindRejected}, {Kind: KindRejected, login: true}, {Kind: KindUnreachable},
		{Kind: KindUnreachable, Addon: "Comet"}, {Kind: KindServer}, {Kind: KindServer, Addon: "Comet"},
		{Kind: KindInvalid}, {Kind: KindInvalid, Addon: "Comet"}, ErrSignedOut, ErrReauthRequired, ErrInvalidID,
		ErrNoImdb, ErrExpired, {Kind: KindUnreachable, Addon: "Comet", page: true}, {Kind: KindServer, Addon: "Comet", page: true},
		{Kind: KindSignedOut, page: true}, {Kind: KindReauthRequired, page: true},
	}
	for _, e := range errs {
		msg := e.Error()
		if msg == "" || strings.Contains(msg, "://") {
			t.Errorf("%s : %q", e.Kind, msg)
		}
		b, err := json.Marshal(e)
		if err != nil {
			t.Fatal(err)
		}
		var shape struct{ Kind, Message string }
		if err := json.Unmarshal(b, &shape); err != nil || shape.Kind != string(e.Kind) || shape.Message != msg {
			t.Errorf("forme %s", b)
		}
	}
	if !errors.Is(Error{Kind: KindRejected, login: true}, Error{Kind: KindRejected}) || errors.Is(ErrSignedOut, ErrReauthRequired) {
		t.Fatal("Is compare le kind")
	}
	if got := (Error{Kind: KindRejected, login: true}).Error(); got != "Adresse e-mail ou mot de passe incorrect." {
		t.Fatal(got)
	}
	if got := (Error{Kind: KindUnreachable}).Error(); got != "Stremio ne répond pas. Réessaie plus tard." {
		t.Fatal(got)
	}
}
