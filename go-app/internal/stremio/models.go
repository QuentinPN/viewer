package stremio

import (
	"encoding/json"
	"fmt"
	"log/slog"
	"maps"
	"net/url"
	"regexp"
	"strconv"
	"strings"

	"viewer/internal/netaddr"
	"viewer/internal/redact"
)

// ContentType is the `{type}` of a stream request.
type ContentType string

const (
	Movie  ContentType = "movie"
	Series ContentType = "series"
)

// AuthKey is the session key of the Stremio account. It prints masked under every
// fmt verb and never serializes; only this package reads it.
type AuthKey struct{ value string }

func NewAuthKey(value string) AuthKey { return AuthKey{value: value} }

func (k AuthKey) String() string               { return redact.Masked("AuthKey") }
func (k AuthKey) GoString() string             { return k.String() }
func (k AuthKey) Format(f fmt.State, _ rune)   { redact.WriteMasked(f, "AuthKey") }
func (k AuthKey) LogValue() slog.Value         { return slog.StringValue(k.String()) }
func (k AuthKey) MarshalJSON() ([]byte, error) { return redact.RefuseMarshal("AuthKey") }

// resource is one entry of a manifest's `resources`: the short form `"stream"`
// (types and idPrefixes nil: the manifest's apply) or the object form
// `{name, types, idPrefixes}`, each field overriding the manifest's when present.
type resource struct {
	name       string
	types      []string
	idPrefixes []string
}

// Addon is an installed addon: its manifest and its base address, the transportUrl
// without `/manifest.json`. The base is a secret (debrid configuration): it prints
// masked and the Addon never serializes.
type Addon struct {
	Name, ID, Version string

	base       string
	resources  []resource
	types      []string
	idPrefixes []string
}

func (a Addon) String() string               { return redact.Masked("Addon") }
func (a Addon) GoString() string             { return a.String() }
func (a Addon) Format(f fmt.State, _ rune)   { redact.WriteMasked(f, "Addon") }
func (a Addon) MarshalJSON() ([]byte, error) { return redact.RefuseMarshal("Addon") }

// manifestSuffix ends every transportUrl.
const manifestSuffix = "/manifest.json"

type manifestJSON struct {
	ID         string            `json:"id"`
	Version    string            `json:"version"`
	Name       string            `json:"name"`
	Resources  []json.RawMessage `json:"resources"`
	Types      []string          `json:"types"`
	IDPrefixes []string          `json:"idPrefixes"`
}

type resourceJSON struct {
	Name       string   `json:"name"`
	Types      []string `json:"types"`
	IDPrefixes []string `json:"idPrefixes"`
}

// ParseAddon reads one addon of the collection. An unreadable manifest, or one
// without a name, is KindServer; a transportUrl that is not `…/manifest.json` over
// https (http only on a loopback or private host), or that holds credentials, a
// query or a fragment, is KindInvalid. Neither error holds the address.
func ParseAddon(transportURL string, manifest json.RawMessage) (Addon, error) {
	var m manifestJSON
	if err := json.Unmarshal(manifest, &m); err != nil || strings.TrimSpace(m.Name) == "" {
		return Addon{}, Error{Kind: KindServer}
	}
	invalid := Error{Kind: KindInvalid, Addon: m.Name}
	base, ok := strings.CutSuffix(transportURL, manifestSuffix)
	if !ok {
		return Addon{}, invalid
	}
	u, err := url.Parse(transportURL)
	if err != nil || u.Host == "" || u.Hostname() == "" || u.User != nil || u.RawQuery != "" || u.ForceQuery || u.Fragment != "" {
		return Addon{}, invalid
	}
	switch u.Scheme {
	case "https":
	case "http":
		if !netaddr.IsLocalHost(u.Hostname()) {
			return Addon{}, invalid
		}
	default:
		return Addon{}, invalid
	}
	a := Addon{Name: m.Name, ID: m.ID, Version: m.Version, base: base, types: m.Types, idPrefixes: m.IDPrefixes}
	for _, raw := range m.Resources {
		var short string
		if json.Unmarshal(raw, &short) == nil {
			a.resources = append(a.resources, resource{name: short})
			continue
		}
		var full resourceJSON
		if json.Unmarshal(raw, &full) == nil && full.Name != "" {
			a.resources = append(a.resources, resource{name: full.Name, types: full.Types, idPrefixes: full.IDPrefixes})
		}
		// Any other form is skipped: it cannot declare a stream resource.
	}
	return a, nil
}

var imdbID = regexp.MustCompile(`^tt[0-9]{5,}$`)

// StreamID is the `{id}` of a stream request: `tt…` for a movie (season and
// episode 0), `tt…:S:E` for an episode (both ≥ 1). Anything else is ErrInvalidID.
func StreamID(imdb string, season, episode int) (string, error) {
	if !imdbID.MatchString(imdb) {
		return "", ErrInvalidID
	}
	switch {
	case season == 0 && episode == 0:
		return imdb, nil
	case season >= 1 && episode >= 1:
		return imdb + ":" + strconv.Itoa(season) + ":" + strconv.Itoa(episode), nil
	default:
		return "", ErrInvalidID
	}
}

// Stream is one playable stream of an addon: what the UI may show (Name, Title,
// Filename, Size) and, kept inside, the URL and the HTTP headers to open it with.
// The URL is a signed debrid link: it prints masked and never serializes.
type Stream struct {
	Name, Title, Filename string
	// Size in bytes, 0 when unknown.
	Size int64

	url     string
	headers map[string]string
}

func (s Stream) String() string               { return redact.Masked("Stream") }
func (s Stream) GoString() string             { return s.String() }
func (s Stream) Format(f fmt.State, _ rune)   { redact.WriteMasked(f, "Stream") }
func (s Stream) MarshalJSON() ([]byte, error) { return redact.RefuseMarshal("Stream") }

// URL is the address to give to the player, never to the UI.
func (s Stream) URL() string { return s.url }

// Headers are the request headers the addon asks for (`proxyHeaders.request`), a
// copy; nil when there are none.
func (s Stream) Headers() map[string]string { return maps.Clone(s.headers) }

// StreamsResult is the answer of one addon: its playable streams, in its order, and
// how many entries it gave that cannot be played (torrent without URL, YouTube,
// external link).
type StreamsResult struct {
	Streams     []Stream
	Unsupported int
}

func (r StreamsResult) String() string               { return redact.Masked("StreamsResult") }
func (r StreamsResult) GoString() string             { return r.String() }
func (r StreamsResult) Format(f fmt.State, _ rune)   { redact.WriteMasked(f, "StreamsResult") }
func (r StreamsResult) MarshalJSON() ([]byte, error) { return redact.RefuseMarshal("StreamsResult") }
