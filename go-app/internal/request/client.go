package request

// Typed Request HTTP client, port of src-tauri/src/request/client.rs.
// `POST /api/auth/emby-token` goes without any `Authorization` header; every other
// authenticated call carries the session token as `Authorization: Bearer`, never in
// a URL.
//
// net/http against reqwest (research/request.md): same timeouts, no redirect
// followed, no retry. Every send failure is Unreachable and every failure while
// reading the body InvalidResponse, as `From<reqwest::Error>`: there is no timeout
// nor cancelled kind here (a cancelled context ends as Unreachable).

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"strconv"
	"strings"
	"time"
	"unicode"

	"viewer/internal/emby"
)

const (
	connectTimeout = 5 * time.Second
	requestTimeout = 15 * time.Second
	// DeviceNameMax is the longest device name the server accepts.
	DeviceNameMax = 64
	// MaxImageBytes: larger image answers are refused (same bound as Emby artwork).
	MaxImageBytes = emby.MaxImageBytes
	// defaultRetryAfterS is the wait advised when a 429 has no usable Retry-After.
	defaultRetryAfterS = 60
	// maxRetryAfterS bounds a server's Retry-After: it sets a deadline in memory.
	maxRetryAfterS = 24 * 60 * 60
)

// imageTypes are the image types served to the WebView; SVG is never passed through.
var imageTypes = [...]string{"image/jpeg", "image/png", "image/webp"}

// Param is one query parameter, kept in order.
type Param struct{ Key, Value string }

type exchangeBody struct {
	UserID      string `json:"userId"`
	AccessToken string `json:"accessToken"`
	DeviceName  string `json:"deviceName"`
}

type apiError struct {
	Error string `json:"error"`
}

// Client is `RequestClient`. It is immutable and safe for concurrent use.
type Client struct {
	http *http.Client
	// base is the address without trailing `/`; API paths start with `/api/`.
	base    string
	version string
}

// NewClient is `RequestClient::new`; base is a NormalizeAddress result.
func NewClient(base, version string) *Client {
	dialer := &net.Dialer{Timeout: connectTimeout}
	transport := &http.Transport{
		Proxy:               http.ProxyFromEnvironment,
		DialContext:         dialer.DialContext,
		TLSHandshakeTimeout: connectTimeout,
		ForceAttemptHTTP2:   true,
		DisableCompression:  true,
		MaxIdleConns:        16,
		IdleConnTimeout:     90 * time.Second,
	}
	return &Client{
		http: &http.Client{
			Transport: transport,
			Timeout:   requestTimeout,
			// A redirect could carry the Bearer or the Emby token to another host.
			CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
		},
		base:    strings.TrimRight(base, "/"),
		version: version,
	}
}

// String keeps the client printable without its transport internals.
func (c *Client) String() string { return "RequestClient(" + c.base + ")" }

func (c *Client) request(ctx context.Context, method, path string, query []Param, body any, token *SessionToken) (*http.Request, error) {
	url := c.base + path
	if len(query) > 0 {
		url += "?" + encodeQuery(query)
	}
	var reader io.Reader
	if body != nil {
		var buf bytes.Buffer
		enc := json.NewEncoder(&buf)
		// serde_json does not escape <, > and &.
		enc.SetEscapeHTML(false)
		if err := enc.Encode(body); err != nil {
			return nil, ErrUnreachable
		}
		reader = bytes.NewReader(bytes.TrimSuffix(buf.Bytes(), []byte("\n")))
	}
	req, err := http.NewRequestWithContext(ctx, method, url, reader)
	if err != nil {
		return nil, ErrUnreachable
	}
	req.Header.Set("User-Agent", emby.ClientName+"/"+c.version)
	req.Header.Set("Accept", "application/json")
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	if token != nil {
		req.Header.Set("Authorization", "Bearer "+token.Expose())
	}
	return req, nil
}

// do sends req: any failure to get an answer is Unreachable, never with the URL.
func (c *Client) do(req *http.Request) (*http.Response, error) {
	res, err := c.http.Do(req)
	if err != nil {
		return nil, ErrUnreachable
	}
	return res, nil
}

func (c *Client) send(ctx context.Context, method, path string, query []Param, body any, token *SessionToken) (*http.Response, error) {
	req, err := c.request(ctx, method, path, query, body, token)
	if err != nil {
		return nil, err
	}
	res, err := c.do(req)
	if err != nil {
		return nil, err
	}
	return check(res)
}

// Version is `GET /api/version`, public.
func (c *Client) Version(ctx context.Context) (ApiVersion, error) {
	res, err := c.send(ctx, http.MethodGet, "/api/version", nil, nil, nil)
	if err != nil {
		return ApiVersion{}, err
	}
	return decodeJSON[ApiVersion](res)
}

// Exchange trades the Emby identity for a session token. Sent without
// `Authorization`.
func (c *Client) Exchange(ctx context.Context, userID string, embyToken emby.AccessToken, deviceName string) (EmbyTokenSession, error) {
	var name []rune
	for _, r := range deviceName {
		if len(name) == DeviceNameMax {
			break
		}
		if !unicode.IsControl(r) {
			name = append(name, r)
		}
	}
	body := exchangeBody{UserID: userID, AccessToken: embyToken.Expose(), DeviceName: string(name)}
	res, err := c.send(ctx, http.MethodPost, "/api/auth/emby-token", nil, body, nil)
	if err != nil {
		return EmbyTokenSession{}, err
	}
	return decodeJSON[EmbyTokenSession](res)
}

// Get is `get`: an authenticated GET decoded as T.
func Get[T any](ctx context.Context, c *Client, path string, query []Param, token SessionToken) (T, error) {
	res, err := c.send(ctx, http.MethodGet, path, query, nil, &token)
	if err != nil {
		var zero T
		return zero, err
	}
	return decodeJSON[T](res)
}

// Send is `send`: an authenticated call with an optional JSON body (nil: none),
// decoded as T.
func Send[T any](ctx context.Context, c *Client, method, path string, body any, token SessionToken) (T, error) {
	res, err := c.send(ctx, method, path, nil, body, &token)
	if err != nil {
		var zero T
		return zero, err
	}
	return decodeJSON[T](res)
}

// SendEmpty is `send_empty`: a call answered by `204 No Content`.
func (c *Client) SendEmpty(ctx context.Context, method, path string, token SessionToken) error {
	res, err := c.send(ctx, method, path, nil, nil, &token)
	if err != nil {
		return err
	}
	_ = res.Body.Close()
	return nil
}

// Image is the TMDB image through the server (`/api/images/{size}/{file}`): bytes
// and content type, only JPEG, PNG or WebP, at most MaxImageBytes.
func (c *Client) Image(ctx context.Context, size, file string, token SessionToken) ([]byte, string, error) {
	req, err := c.request(ctx, http.MethodGet, "/api/images/"+size+"/"+file, nil, nil, &token)
	if err != nil {
		return nil, "", err
	}
	// reqwest's `.header` appends: the request carries both Accept values.
	req.Header.Add("Accept", "image/*")
	res, err := c.do(req)
	if err != nil {
		return nil, "", err
	}
	if res, err = check(res); err != nil {
		return nil, "", err
	}
	defer res.Body.Close()
	contentType := ""
	if v, ok := headerText(res.Header, "Content-Type"); ok {
		v, _, _ = strings.Cut(v, ";")
		contentType = strings.TrimSpace(v)
	}
	allowed := false
	for _, t := range imageTypes {
		allowed = allowed || t == contentType
	}
	if !allowed || res.ContentLength > MaxImageBytes {
		return nil, "", ErrInvalidResponse
	}
	data, err := io.ReadAll(io.LimitReader(res.Body, MaxImageBytes+1))
	if err != nil || len(data) > MaxImageBytes {
		return nil, "", ErrInvalidResponse
	}
	return data, contentType, nil
}

// decodeJSON is `json`: the whole body, decoded with serde's rules.
func decodeJSON[T any](res *http.Response) (T, error) {
	var out T
	defer res.Body.Close()
	data, err := io.ReadAll(res.Body)
	if err != nil {
		return out, ErrInvalidResponse
	}
	if err := decodeStrict(data, &out); err != nil {
		var zero T
		return zero, ErrInvalidResponse
	}
	return out, nil
}

// headerText is `headers().get(name).and_then(|v| v.to_str().ok())`: the first
// value, only when it is visible ASCII (or tab).
func headerText(h http.Header, name string) (string, bool) {
	values := h.Values(name)
	if len(values) == 0 {
		return "", false
	}
	v := values[0]
	for i := 0; i < len(v); i++ {
		if c := v[i]; c != '\t' && (c < 0x20 || c >= 0x7f) {
			return "", false
		}
	}
	return v, true
}

// check is `check`: success passes through; an error status becomes a typed error
// carrying the server's `error` text. The body of a refused answer is closed.
func check(res *http.Response) (*http.Response, error) {
	status := res.StatusCode
	if status >= 200 && status < 300 {
		return res, nil
	}
	retryAfter := uint64(defaultRetryAfterS)
	if v, ok := headerText(res.Header, "Retry-After"); ok {
		if s, ok := parseRustU64(strings.TrimSpace(v)); ok {
			retryAfter = min(s, maxRetryAfterS)
		}
	}
	var message string
	hasMessage := false
	if data, err := io.ReadAll(res.Body); err == nil {
		var e apiError
		if decodeStrict(data, &e) == nil && strings.TrimSpace(e.Error) != "" {
			message, hasMessage = e.Error, true
		}
	}
	_ = res.Body.Close()
	or := func(fallback string) string {
		if hasMessage {
			return message
		}
		return fallback
	}
	switch status {
	case http.StatusUnauthorized:
		return nil, NewRejected(or("connexion refusée par Request"))
	case http.StatusForbidden:
		return nil, NewForbidden(or("accès refusé par Request"))
	case http.StatusNotFound:
		return nil, NewNotFound(or("introuvable sur Request"))
	case http.StatusTooManyRequests:
		return nil, NewRateLimited(retryAfter)
	default:
		return nil, NewServer(uint16(status), or("erreur d'Request (HTTP "+strconv.Itoa(status)+")"))
	}
}

// parseRustU64 is `str::parse::<u64>`: ASCII digits, one optional leading `+`.
func parseRustU64(v string) (uint64, bool) {
	digits := strings.TrimPrefix(v, "+")
	if digits == "" || strings.ContainsFunc(digits, func(r rune) bool { return r < '0' || r > '9' }) {
		return 0, false
	}
	n, err := strconv.ParseUint(digits, 10, 64)
	return n, err == nil
}

// encodeQuery is `Url::parse_with_params`: `form_urlencoded`, in order. Local copy
// of the unexported formEncode of internal/emby (playback.go).
// TODO(dry): use emby.FormEncode once exported.
func encodeQuery(query []Param) string {
	var b strings.Builder
	enc := func(s string) {
		for i := 0; i < len(s); i++ {
			c := s[i]
			switch {
			case (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') ||
				c == '*' || c == '-' || c == '.' || c == '_':
				b.WriteByte(c)
			case c == ' ':
				b.WriteByte('+')
			default:
				b.WriteByte('%')
				b.WriteByte(hexDigits[c>>4])
				b.WriteByte(hexDigits[c&0xf])
			}
		}
	}
	for i, p := range query {
		if i > 0 {
			b.WriteByte('&')
		}
		enc(p.Key)
		b.WriteByte('=')
		enc(p.Value)
	}
	return b.String()
}
