// Package request is the Request integration (ADR 0125), the « Demander »
// tab, port of src-tauri/src/request. Every call is made here, in Go; the UI
// never sees a token nor builds an API or image URL.
package request

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/netip"
	"net/url"
	"strings"
	"unicode"

	"viewer/internal/emby"
	"viewer/internal/redact"
)

// RequiredFeatures are the features the client relies on; a server missing one is
// "trop ancien".
var RequiredFeatures = [4]string{
	"auth.emby-token",
	"auth.bearer",
	"auth.sessions",
	"media.item-id",
}

// apiMajor is the API major version this client speaks.
const apiMajor = "1"

// Account is the credential account of the Request session of one Emby
// account: `request/{embyServerId}/{embyUserId}`.
func Account(serverID, userID string) string {
	return "request/" + emby.CredentialAccount(serverID, userID)
}

// ErrorKind is the `kind` of a RequestError, as the UI reads it.
type ErrorKind string

const (
	KindNotConfigured   ErrorKind = "notConfigured"
	KindInvalidAddress  ErrorKind = "invalidAddress"
	KindUnreachable     ErrorKind = "unreachable"
	KindTooOld          ErrorKind = "tooOld"
	KindRejected        ErrorKind = "rejected"
	KindRateLimited     ErrorKind = "rateLimited"
	KindForbidden       ErrorKind = "forbidden"
	KindNotFound        ErrorKind = "notFound"
	KindServer          ErrorKind = "server"
	KindInvalidResponse ErrorKind = "invalidResponse"
	KindEmbySignedOut   ErrorKind = "embySignedOut"
	KindSignedOut       ErrorKind = "signedOut"
	KindCredentialStore ErrorKind = "credentialStore"
	KindSettings        ErrorKind = "settings"
)

// RequestError is `RequestError`: the failures surfaced to the UI as
// `{kind, message}`, like EmbyError. Messages are French; for an HTTP error the
// server's own `error` text is kept as is (Text).
//
//   - Text: the payload of Rejected, Forbidden, NotFound, Server, CredentialStore
//     and Settings;
//   - Status: the HTTP status of Server;
//   - APIVersion and Missing: TooOld;
//   - RetryAfterS: RateLimited.
//
// It is a value type: return it as `error`, read it back with errors.As, and match
// a kind with errors.Is (Is compares the kind only).
type RequestError struct {
	Kind        ErrorKind
	Text        string
	Status      uint16
	APIVersion  string
	Missing     []string
	RetryAfterS uint64
}

// Variants without a payload.
var (
	ErrNotConfigured   = RequestError{Kind: KindNotConfigured}
	ErrInvalidAddress  = RequestError{Kind: KindInvalidAddress}
	ErrUnreachable     = RequestError{Kind: KindUnreachable}
	ErrInvalidResponse = RequestError{Kind: KindInvalidResponse}
	ErrEmbySignedOut   = RequestError{Kind: KindEmbySignedOut}
	ErrSignedOut       = RequestError{Kind: KindSignedOut}
)

// Variants with a payload.
func NewTooOld(apiVersion string, missing []string) RequestError {
	if missing == nil {
		missing = []string{}
	}
	return RequestError{Kind: KindTooOld, APIVersion: apiVersion, Missing: missing}
}
func NewRejected(text string) RequestError {
	return RequestError{Kind: KindRejected, Text: text}
}
func NewRateLimited(retryAfterS uint64) RequestError {
	return RequestError{Kind: KindRateLimited, RetryAfterS: retryAfterS}
}
func NewForbidden(text string) RequestError {
	return RequestError{Kind: KindForbidden, Text: text}
}
func NewNotFound(text string) RequestError {
	return RequestError{Kind: KindNotFound, Text: text}
}
func NewServer(status uint16, text string) RequestError {
	return RequestError{Kind: KindServer, Status: status, Text: text}
}
func NewCredentialStore(text string) RequestError {
	return RequestError{Kind: KindCredentialStore, Text: text}
}
func NewSettings(text string) RequestError {
	return RequestError{Kind: KindSettings, Text: text}
}

// minutes is `minutes`: whole minutes, rounded up, at least 1.
func minutes(seconds uint64) uint64 {
	m := seconds / 60
	if seconds%60 != 0 {
		m++
	}
	return max(m, 1)
}

// Error is the `#[error(...)]` text of the Rust variant.
func (e RequestError) Error() string {
	switch e.Kind {
	case KindNotConfigured:
		return "aucune adresse Request n'est enregistrée"
	case KindInvalidAddress:
		return "adresse Request invalide : utilise https:// (http:// seulement sur le réseau local)"
	case KindUnreachable:
		return "Request est injoignable"
	case KindTooOld:
		return "Request est trop ancien (API " + e.APIVersion + ") pour cette version d'Viewer"
	case KindRateLimited:
		return fmt.Sprintf("trop de tentatives de connexion : réessaie dans %d min", minutes(e.RetryAfterS))
	case KindInvalidResponse:
		return "réponse inattendue d'Request"
	case KindEmbySignedOut:
		return "aucune session Emby ouverte"
	case KindSignedOut:
		return "déconnecté d'Request"
	case KindCredentialStore:
		return "coffre d'identification Windows indisponible : " + e.Text
	case KindSettings:
		return "réglage non enregistré : " + e.Text
	default: // Rejected, Forbidden, NotFound, Server: the server's text
		return e.Text
	}
}

// Is matches on the kind, so errors.Is(err, ErrSignedOut) works.
func (e RequestError) Is(target error) bool {
	var t RequestError
	return errors.As(target, &t) && t.Kind == e.Kind
}

// MarshalJSON is `impl Serialize for RequestError`: `{kind, message}`, in that
// order, the message being the Display text.
func (e RequestError) MarshalJSON() ([]byte, error) {
	return json.Marshal(struct {
		Kind    ErrorKind `json:"kind"`
		Message string    `json:"message"`
	}{e.Kind, e.Error()})
}

// kindOf is the kind of a RequestError, "" for any other error.
func kindOf(err error) ErrorKind {
	var e RequestError
	if errors.As(err, &e) {
		return e.Kind
	}
	return ""
}

// fromEmby is `From<EmbyError> for RequestError` (credential store and
// identity errors). A RequestError passes through.
func fromEmby(err error) error {
	if err == nil {
		return nil
	}
	var ur RequestError
	if errors.As(err, &ur) {
		return ur
	}
	var e emby.EmbyError
	if errors.As(err, &e) {
		switch e.Kind {
		case emby.KindCredentialStore:
			return NewCredentialStore(e.Detail)
		case emby.KindNotSignedIn:
			return ErrEmbySignedOut
		}
	}
	return NewCredentialStore(err.Error())
}

// NormalizeAddress is `normalize_address` followed by `address_text`: spaces and
// trailing `/` removed, `https://` when no scheme is given, and the address as
// stored and shown (no trailing `/`). `http://` only for loopback and private
// networks, so the Emby token never crosses the Internet in clear text.
//
// The url crate's parse (WHATWG) of the scheme and authority is done by
// emby.ParseServerURL, given `scheme://authority` only, so its own path rules
// (`/web`, `/emby`) never apply; the path is normalized here (see
// research/request.md for the divergences).
func NormalizeAddress(input string) (string, error) {
	trimmed := strings.TrimRight(strings.TrimSpace(input), "/")
	if trimmed == "" || strings.ContainsFunc(trimmed, unicode.IsSpace) {
		return "", ErrInvalidAddress
	}
	withScheme := trimmed
	if !strings.Contains(trimmed, "://") {
		withScheme = "https://" + trimmed
	}
	// For http(s), any `?` or `#` starts a query or a fragment: refused.
	if strings.ContainsAny(withScheme, "?#") {
		return "", ErrInvalidAddress
	}
	scheme, rest, _ := strings.Cut(withScheme, ":")
	scheme = redact.ASCIILower(scheme)
	if scheme != "http" && scheme != "https" {
		return "", ErrInvalidAddress
	}
	// Special authority: after any number of `/` and `\`, up to the next one.
	rest = strings.TrimLeft(rest, `/\`)
	end := strings.IndexAny(rest, `/\`)
	if end < 0 {
		end = len(rest)
	}
	authority, rawPath := rest[:end], rest[end:]
	// Credentials, an empty host, a bad port or host are refused there; plain HTTP
	// to a public host too (its local rule is wider than ours, checked below).
	base, err := emby.ParseServerURL(scheme + "://" + authority)
	if err != nil {
		return "", ErrInvalidAddress
	}
	origin := base.String()
	if scheme == "http" {
		u, err := url.Parse(origin)
		if err != nil || !isLocal(u.Hostname()) {
			return "", ErrInvalidAddress
		}
	}
	return strings.TrimRight(origin+normalizePath(rawPath), "/"), nil
}

// isLocal is `is_local` on the serialized host: IPv4 loopback or private, IPv6
// loopback, `localhost` or `*.local`.
func isLocal(host string) bool {
	if addr, err := netip.ParseAddr(host); err == nil {
		if addr.Is4() {
			return addr.IsLoopback() || addr.IsPrivate()
		}
		// Ipv6Addr::is_loopback is `::1` only, not an IPv4-mapped loopback.
		return addr == netip.IPv6Loopback()
	}
	d := redact.ASCIILower(host)
	return d == "localhost" || strings.HasSuffix(d, ".local")
}

// normalizePath is the WHATWG path state for a special scheme: `/` and `\` split
// segments, `.` and `..` (also percent-encoded) are resolved, and the path
// percent-encode set is encoded. raw is empty or starts with `/` or `\`.
func normalizePath(raw string) string {
	if raw == "" {
		return "/"
	}
	parts := strings.Split(strings.ReplaceAll(raw[1:], `\`, "/"), "/")
	var out []string
	for i, seg := range parts {
		last := i == len(parts)-1
		switch {
		case isDoubleDot(seg):
			if len(out) > 0 {
				out = out[:len(out)-1]
			}
			if last {
				out = append(out, "")
			}
		case isSingleDot(seg):
			if last {
				out = append(out, "")
			}
		default:
			out = append(out, encodePathSegment(seg))
		}
	}
	return "/" + strings.Join(out, "/")
}

func isSingleDot(s string) bool { return s == "." || strings.EqualFold(s, "%2e") }

func isDoubleDot(s string) bool {
	switch redact.ASCIILower(s) {
	case "..", ".%2e", "%2e.", "%2e%2e":
		return true
	}
	return false
}

// hexDigits are the upper-case digits of a percent-encoding.
const hexDigits = "0123456789ABCDEF"

// encodePathSegment applies the path percent-encode set: C0 controls, space, `"`,
// `#`, `<`, `>`, `?`, backquote, `{`, `}` and every byte above `~`.
func encodePathSegment(s string) string {
	var b strings.Builder
	for i := 0; i < len(s); i++ {
		c := s[i]
		switch {
		case c < 0x20 || c > 0x7e || strings.IndexByte(" \"#<>?`{}", c) >= 0:
			b.WriteByte('%')
			b.WriteByte(hexDigits[c>>4])
			b.WriteByte(hexDigits[c&0xf])
		default:
			b.WriteByte(c)
		}
	}
	return b.String()
}

// ensureCompatible is `ensure_compatible`: API major `1` with every feature of
// RequiredFeatures.
func ensureCompatible(v ApiVersion) error {
	missing := []string{}
	for _, f := range RequiredFeatures {
		found := false
		for _, have := range v.Features {
			if have == f {
				found = true
				break
			}
		}
		if !found {
			missing = append(missing, f)
		}
	}
	major, _, _ := strings.Cut(v.APIVersion, ".")
	if major != apiMajor || len(missing) > 0 {
		return NewTooOld(v.APIVersion, missing)
	}
	return nil
}
