package updater

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// The manifest is desktop/latest.json, in the format of tauri-plugin-updater
// (src/release/manifest.ts), read like the plugin's RemoteRelease: `version`
// (alias `name`, leading `v` dropped), optional `notes` and RFC 3339 `pub_date`,
// then either `platforms` keyed by target ("static") or a top-level url
// ("dynamic"). The Go app adds one field next to `url`: `ed25519`, the base64
// ed25519 signature of SignedMessage(version, installer) (signature.go). The
// Tauri app ignores it (no deny_unknown_fields) and keeps verifying its
// minisign `signature`.

// Targets, searched in this order (plugin get_urls: `{os}-{arch}-{installer}`,
// then `{os}-{arch}`); a target counts only when its entry has an `ed25519`
// signature, as the plugin takes the first target with both url and signature.
var targets = []string{"windows-x86_64-nsis", "windows-x86_64"}

type rawPlatform struct {
	URL       *string `json:"url"`
	Signature *string `json:"signature"`
	Ed25519   *string `json:"ed25519"`
}

type rawManifest struct {
	Version   *string                `json:"version"`
	Name      *string                `json:"name"`
	Notes     *string                `json:"notes"`
	PubDate   *string                `json:"pub_date"`
	Platforms map[string]rawPlatform `json:"platforms"`
	URL       *string                `json:"url"`
	Signature *string                `json:"signature"`
	Ed25519   *string                `json:"ed25519"`
}

// release is a newer version announced by the manifest, for this target.
type release struct {
	version   string // canonical semver, without the leading v
	notes     *string
	url       string
	signature string // base64 ed25519, from the `ed25519` field
}

// checkEntry is the deserialization of a ReleaseManifestPlatform: a valid
// absolute url and a signature, for every entry, before any comparison. The
// signature is the minisign `signature` or the Go `ed25519`, so a Tauri-only
// entry still parses and is then skipped by the target search.
func checkEntry(p rawPlatform) (string, error) {
	if p.URL == nil {
		return "", newError(KindSerialization, errors.New("missing field `url`"))
	}
	u, err := url.Parse(*p.URL)
	if err != nil || !u.IsAbs() {
		return "", newError(KindSerialization, errors.New("invalid download url"))
	}
	if p.Signature == nil && p.Ed25519 == nil {
		return "", newError(KindSerialization, errors.New("missing field `signature`"))
	}
	return u.String(), nil
}

func hasEd25519(p rawPlatform) bool {
	return p.Ed25519 != nil && strings.TrimSpace(*p.Ed25519) != ""
}

// parseManifest is the RemoteRelease deserializer followed by
// update_from_release: nil when the release is not newer than current, even if
// it has no entry for this target.
func parseManifest(body []byte, current Version) (*release, error) {
	var raw rawManifest
	dec := json.NewDecoder(bytes.NewReader(body))
	if err := dec.Decode(&raw); err != nil {
		return nil, newError(KindSerialization, err)
	}
	v := raw.Version
	if v == nil {
		v = raw.Name
	}
	if v == nil {
		return nil, newError(KindSerialization, errors.New("missing field `version`"))
	}
	version, err := ParseVersion(strings.TrimLeft(*v, "v"))
	if err != nil {
		return nil, newError(KindSerialization, err)
	}
	if raw.PubDate != nil {
		if _, err := time.Parse(time.RFC3339, *raw.PubDate); err != nil {
			return nil, newError(KindSerialization, fmt.Errorf("invalid value for `pub_date`: %w", err))
		}
	}
	urls := map[string]string{}
	if raw.Platforms == nil {
		if raw.URL == nil {
			return nil, newError(KindSerialization, errors.New("the `url` field was not set on the updater response"))
		}
		if raw.Signature == nil && raw.Ed25519 == nil {
			return nil, newError(KindSerialization, errors.New("the `signature` field was not set on the updater response"))
		}
	}
	for target, p := range raw.Platforms {
		u, err := checkEntry(p)
		if err != nil {
			return nil, err
		}
		urls[target] = u
	}

	if version.Compare(current) <= 0 {
		return nil, nil
	}

	var entry rawPlatform
	var download string
	if raw.Platforms == nil {
		entry = rawPlatform{URL: raw.URL, Signature: raw.Signature, Ed25519: raw.Ed25519}
		u, err := checkEntry(entry)
		if err != nil {
			return nil, err
		}
		download = u
	} else {
		present := false
		for _, t := range targets {
			p, ok := raw.Platforms[t]
			if !ok {
				continue
			}
			present = true
			if hasEd25519(p) {
				entry, download = p, urls[t]
				break
			}
		}
		if download == "" {
			if present {
				// The target is there, signed for the Tauri app only.
				return nil, newError(KindMissingSignature, nil)
			}
			return nil, newError(KindTargetsNotFound, fmt.Errorf("none of %v", targets))
		}
	}
	if !hasEd25519(entry) {
		return nil, newError(KindMissingSignature, nil)
	}
	return &release{
		version:   version.String(),
		notes:     raw.Notes,
		url:       download,
		signature: strings.TrimSpace(*entry.Ed25519),
	}, nil
}

// validate is what building the plugin checks (app.updater() in Rust): at least
// one endpoint, http only when insecure transport is allowed, a semver current
// version. Its failures are logged as "setup".
func (u *Updater) validate() (Version, error) {
	if len(u.cfg.Endpoints) == 0 {
		return Version{}, newError(KindEmptyEndpoints, nil)
	}
	for _, e := range u.cfg.Endpoints {
		parsed, err := url.Parse(e)
		if err != nil {
			return Version{}, newError(KindOther, errors.New("invalid endpoint"))
		}
		if parsed.Scheme != "https" && !u.cfg.AllowInsecureTransport {
			return Version{}, newError(KindInsecureTransport, nil)
		}
	}
	current, err := ParseVersion(u.cfg.CurrentVersion)
	if err != nil {
		return Version{}, newError(KindOther, err)
	}
	return current, nil
}

// endpointURL replaces the plugin's placeholders ({{current_version}} with `+`
// encoded, {{target}}, {{arch}}, {{bundle_type}}); the shipped endpoint has none.
func endpointURL(endpoint string, current Version) string {
	return strings.NewReplacer(
		"{{current_version}}", strings.ReplaceAll(current.String(), "+", "%2B"),
		"{{target}}", "windows",
		"{{arch}}", "x86_64",
		"{{bundle_type}}", "nsis",
	).Replace(endpoint)
}

// check is Updater::check: each endpoint in order, the first release wins. 204
// means up to date; a non-2xx status is skipped and ends as ReleaseNotFound. As
// in the plugin, a body that is not JSON is a request error (category network)
// returned at once, while JSON of the wrong shape is a manifest error, kept
// while the next endpoints are tried.
func (u *Updater) check(ctx context.Context, current Version) (*release, error) {
	var lastErr error
	for _, endpoint := range u.cfg.Endpoints {
		req, err := http.NewRequestWithContext(ctx, http.MethodGet, endpointURL(endpoint, current), nil)
		if err != nil {
			lastErr = newError(KindNetwork, err)
			continue
		}
		req.Header.Set("Accept", "application/json")
		req.Header.Set("User-Agent", userAgent(current))
		res, err := u.httpClient().Do(req)
		if err != nil {
			lastErr = newError(KindNetwork, err)
			continue
		}
		if res.StatusCode < 200 || res.StatusCode > 299 {
			_ = res.Body.Close()
			continue
		}
		if res.StatusCode == http.StatusNoContent {
			_ = res.Body.Close()
			return nil, nil
		}
		body, _, err := readBody(res)
		if err != nil {
			return nil, newError(KindNetwork, err)
		}
		if !json.Valid(body) {
			return nil, newError(KindNetwork, errors.New("error decoding response body"))
		}
		rel, err := parseManifest(body, current)
		if err != nil {
			if k := kindOf(err); k == KindSerialization {
				lastErr = err
				continue
			}
			return nil, err
		}
		return rel, nil
	}
	if lastErr != nil {
		return nil, lastErr
	}
	return nil, newError(KindReleaseNotFound, nil)
}

// download is Update::download: the installer into memory, then its signature.
func (u *Updater) download(ctx context.Context, rel *release, current Version) ([]byte, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, rel.url, nil)
	if err != nil {
		return nil, newError(KindNetwork, err)
	}
	req.Header.Set("Accept", "application/octet-stream")
	req.Header.Set("User-Agent", userAgent(current))
	res, err := u.httpClient().Do(req)
	if err != nil {
		return nil, newError(KindNetwork, err)
	}
	body, status, err := readBody(res)
	if err != nil {
		return nil, newError(KindNetwork, err)
	}
	if status < 200 || status > 299 {
		return nil, newError(KindNetwork, fmt.Errorf("download request failed with status: %d", status))
	}
	if err := Verify(body, rel.version, rel.signature, u.cfg.PublicKey); err != nil {
		return nil, err
	}
	return body, nil
}

func readBody(res *http.Response) ([]byte, int, error) {
	defer func() { _ = res.Body.Close() }()
	body, err := io.ReadAll(res.Body)
	return body, res.StatusCode, err
}

func userAgent(current Version) string { return "Viewer/" + current.String() }

func kindOf(err error) Kind {
	var e *Error
	if errors.As(err, &e) {
		return e.Kind
	}
	return KindOther
}
