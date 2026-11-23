package stremio

// Typed Stremio HTTP client: the account API (login, addon collection) and the
// addons' stream resource.
//
// Same transport as the Request client (dial and TLS handshake 5 s, TLS
// validated, no redirect followed, no retry), with a deadline per request: 15 s for
// the API, StreamTimeout per addon. The API answers a refusal (wrong password,
// unknown session) with HTTP 200 and an `error` object: that is KindRejected. A
// failure to get an answer is KindUnreachable; any other unusable answer
// KindServer. No error holds an address, a key or a URL.

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"viewer/internal/emby"
)

const (
	// APIBase is the Stremio account API.
	APIBase = "https://api.strem.io"
	// StreamTimeout bounds one addon's stream request.
	StreamTimeout = 10 * time.Second

	connectTimeout = 5 * time.Second
	apiTimeout     = 15 * time.Second
	// maxBodyBytes: larger answers are refused (the bound of Emby and Request
	// images; a real collection is under 100 KiB).
	maxBodyBytes = emby.MaxImageBytes
)

// Client is immutable and safe for concurrent use.
type Client struct {
	http    *http.Client
	apiBase string
	version string
	// Deadlines, shortened by tests.
	apiTimeout, streamTimeout time.Duration
}

// NewClient builds a client of the API at apiBase (APIBase in production).
func NewClient(apiBase, version string) *Client {
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
			// A redirect could carry the authKey or an addon's key to another host.
			CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
		},
		apiBase:       strings.TrimRight(apiBase, "/"),
		version:       version,
		apiTimeout:    apiTimeout,
		streamTimeout: StreamTimeout,
	}
}

// String keeps the client printable without its transport internals.
func (c *Client) String() string { return "StremioClient" }

// get sends req and reads its body, at most maxBodyBytes. fail is the Error of
// this call (its Addon set); its kind is replaced by the failure's.
func (c *Client) get(req *http.Request, fail Error) ([]byte, error) {
	req.Header.Set("User-Agent", emby.ClientName+"/"+c.version)
	req.Header.Set("Accept", "application/json")
	res, err := c.http.Do(req)
	if err != nil {
		fail.Kind = KindUnreachable
		return nil, fail
	}
	defer res.Body.Close()
	if res.StatusCode < 200 || res.StatusCode > 299 {
		fail.Kind = KindServer
		return nil, fail
	}
	data, err := io.ReadAll(io.LimitReader(res.Body, maxBodyBytes+1))
	if err != nil {
		fail.Kind = KindUnreachable
		return nil, fail
	}
	if len(data) > maxBodyBytes {
		fail.Kind = KindServer
		return nil, fail
	}
	return data, nil
}

// apiEnvelope is every API answer: `result`, or `error` for a refusal.
type apiEnvelope struct {
	Result json.RawMessage `json:"result"`
	Error  json.RawMessage `json:"error"`
}

// call posts body to the API method and decodes its `result` into out.
func (c *Client) call(ctx context.Context, method string, body any, out any, login bool) error {
	ctx, cancel := context.WithTimeout(ctx, c.apiTimeout)
	defer cancel()
	payload, err := json.Marshal(body)
	if err != nil {
		return Error{Kind: KindServer}
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.apiBase+"/api/"+method, bytes.NewReader(payload))
	if err != nil {
		return Error{Kind: KindUnreachable}
	}
	req.Header.Set("Content-Type", "application/json")
	data, err := c.get(req, Error{})
	if err != nil {
		return err
	}
	var env apiEnvelope
	if json.Unmarshal(data, &env) != nil {
		return Error{Kind: KindServer}
	}
	if len(env.Error) > 0 && !bytes.Equal(bytes.TrimSpace(env.Error), []byte("null")) {
		return Error{Kind: KindRejected, login: login}
	}
	if len(env.Result) == 0 || json.Unmarshal(env.Result, out) != nil {
		return Error{Kind: KindServer}
	}
	return nil
}

// Login is `POST /api/login`. The password is sent once and kept nowhere.
func (c *Client) Login(ctx context.Context, email, password string) (AuthKey, error) {
	body := struct {
		Type     string `json:"type"`
		Email    string `json:"email"`
		Password string `json:"password"`
		Facebook bool   `json:"facebook"`
	}{"Login", email, password, false}
	var result *struct {
		AuthKey string `json:"authKey"`
	}
	if err := c.call(ctx, "login", body, &result, true); err != nil {
		return AuthKey{}, err
	}
	if result == nil || result.AuthKey == "" {
		return AuthKey{}, Error{Kind: KindServer}
	}
	return NewAuthKey(result.AuthKey), nil
}

// Addons is `POST /api/addonCollectionGet`: the account's addons, in collection
// order. An addon whose manifest or address is refused is skipped and logged by
// name (by position when it has none).
func (c *Client) Addons(ctx context.Context, key AuthKey) ([]Addon, error) {
	body := struct {
		Type    string `json:"type"`
		AuthKey string `json:"authKey"`
		Update  bool   `json:"update"`
	}{"AddonCollectionGet", key.value, true}
	var result *struct {
		Addons []struct {
			TransportURL string          `json:"transportUrl"`
			Manifest     json.RawMessage `json:"manifest"`
		} `json:"addons"`
	}
	if err := c.call(ctx, "addonCollectionGet", body, &result, false); err != nil {
		return nil, err
	}
	if result == nil {
		return nil, Error{Kind: KindServer}
	}
	addons := make([]Addon, 0, len(result.Addons))
	for i, entry := range result.Addons {
		a, err := ParseAddon(entry.TransportURL, entry.Manifest)
		if err != nil {
			e, _ := err.(Error)
			if e.Addon != "" {
				slog.Warn("Stremio addon skipped", "addon", e.Addon, "kind", string(e.Kind))
			} else {
				slog.Warn("Stremio addon skipped", "position", i, "kind", string(e.Kind))
			}
			continue
		}
		addons = append(addons, a)
	}
	return addons, nil
}

// streamJSON is one entry of an addon's `streams`. Hints of an odd shape are kept
// raw and read leniently, so they never make a playable stream unplayable.
type streamJSON struct {
	Name          string          `json:"name"`
	Title         string          `json:"title"`
	Description   string          `json:"description"`
	URL           string          `json:"url"`
	SizeBytes     json.RawMessage `json:"sizeBytes"`
	BehaviorHints struct {
		Filename     json.RawMessage `json:"filename"`
		VideoSize    json.RawMessage `json:"videoSize"`
		ProxyHeaders json.RawMessage `json:"proxyHeaders"`
	} `json:"behaviorHints"`
}

// Streams is `GET {base}/stream/{type}/{id}.json` on addon a. Only entries with an
// http(s) `url` are playable (an `url` wins over an `infoHash`); the others
// (torrent, YouTube, external link) are counted in Unsupported. The `:` of an
// episode id stays unescaped, the form every captured addon answers.
func (c *Client) Streams(ctx context.Context, a Addon, t ContentType, id string) (StreamsResult, error) {
	if (t != Movie && t != Series) || id == "" {
		return StreamsResult{}, ErrInvalidID
	}
	ctx, cancel := context.WithTimeout(ctx, c.streamTimeout)
	defer cancel()
	fail := Error{Addon: a.Name}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, a.base+"/stream/"+string(t)+"/"+url.PathEscape(id)+".json", nil)
	if err != nil {
		fail.Kind = KindServer
		return StreamsResult{}, fail
	}
	data, err := c.get(req, fail)
	if err != nil {
		return StreamsResult{}, err
	}
	var body struct {
		Streams []json.RawMessage `json:"streams"`
	}
	if json.Unmarshal(data, &body) != nil {
		fail.Kind = KindServer
		return StreamsResult{}, fail
	}
	out := StreamsResult{Streams: []Stream{}}
	for _, raw := range body.Streams {
		s, ok := parseStream(raw)
		if !ok {
			out.Unsupported++
			continue
		}
		out.Streams = append(out.Streams, s)
	}
	return out, nil
}

// parseStream reads one playable entry; false when it has no http(s) url.
func parseStream(raw json.RawMessage) (Stream, bool) {
	var e streamJSON
	if json.Unmarshal(raw, &e) != nil || e.URL == "" {
		return Stream{}, false
	}
	u, err := url.Parse(e.URL)
	if err != nil || (u.Scheme != "https" && u.Scheme != "http") || u.Host == "" {
		return Stream{}, false
	}
	s := Stream{Name: e.Name, Title: e.Title, url: e.URL}
	if s.Title == "" {
		s.Title = e.Description
	}
	var filename string
	if json.Unmarshal(e.BehaviorHints.Filename, &filename) == nil {
		s.Filename = filename
	}
	s.Size = size(e.BehaviorHints.VideoSize)
	if s.Size == 0 {
		s.Size = size(e.SizeBytes)
	}
	var proxy struct {
		Request map[string]string `json:"request"`
	}
	if json.Unmarshal(e.BehaviorHints.ProxyHeaders, &proxy) == nil {
		for k, v := range proxy.Request {
			if headerSafe(k) && headerSafe(v) {
				if s.headers == nil {
					s.headers = map[string]string{}
				}
				s.headers[k] = v
			}
		}
	}
	return s, true
}

// size reads a byte count given as a JSON number (integer or not); 0 otherwise.
func size(raw json.RawMessage) int64 {
	var n json.Number
	if json.Unmarshal(raw, &n) != nil {
		return 0
	}
	if i, err := strconv.ParseInt(n.String(), 10, 64); err == nil {
		return max(i, 0)
	}
	if f, err := n.Float64(); err == nil && f > 0 && f < 1<<62 {
		return int64(f)
	}
	return 0
}

// headerSafe refuses an empty header name or value part holding a control
// character (CR/LF would split the request).
func headerSafe(s string) bool {
	if s == "" {
		return false
	}
	for i := 0; i < len(s); i++ {
		if s[i] < 0x20 || s[i] == 0x7f {
			return false
		}
	}
	return true
}
