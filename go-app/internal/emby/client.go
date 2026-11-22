package emby

// Typed Emby HTTP client, port of src-tauri/src/emby/client.rs. Authentication
// travels only in the `X-Emby-Authorization` header, never in URLs (except the
// library socket URL, as in Rust: ADR 0110).
//
// net/http against reqwest (research/http-client.md): same timeouts, no redirect
// followed, no retry (client.rs has none), no gzip, system TLS roots. Every call
// takes a context: a context cancelled by the caller ends the call with ErrCancelled,
// a deadline with ErrTimeout.

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net"
	"net/http"
	"strconv"
	"strings"
	"time"

	"viewer/internal/formenc"
)

// ClientName is the `Client` of the authorization header.
const ClientName = "Viewer"

const (
	connectTimeout = 5 * time.Second
	requestTimeout = 15 * time.Second
)

// DeviceIdentity is how this installation identifies itself to the server (one
// device per install).
type DeviceIdentity struct {
	Name    string
	ID      string
	Version string
}

// AccessToken is an Emby access token. Printing it, logging it or marshaling it
// never shows the value: only Expose does.
type AccessToken struct {
	value string
}

func NewAccessToken(value string) AccessToken { return AccessToken{value: value} }

func (t AccessToken) Expose() string { return t.value }

func (t AccessToken) String() string { return "AccessToken(<redacted>)" }

func (t AccessToken) GoString() string { return t.String() }

func (t AccessToken) LogValue() slog.Value { return slog.StringValue(t.String()) }

// MarshalJSON keeps the token out of any JSON (the Rust type has no Serialize).
func (t AccessToken) MarshalJSON() ([]byte, error) {
	return nil, errors.New("AccessToken ne se sérialise pas")
}

// Client is `EmbyClient`. It is immutable: WithToken returns a copy, and copies
// share the HTTP connection pool.
type Client struct {
	http   *http.Client
	server ServerURL
	device DeviceIdentity
	token  *AccessToken
}

// NewClient is `EmbyClient::new`.
func NewClient(server ServerURL, device DeviceIdentity) (*Client, error) {
	return NewClientWithTimeouts(server, device, connectTimeout, requestTimeout)
}

// NewClientWithTimeouts is `with_timeouts` (tests use short bounds to simulate a hung
// server): connect bounds the TCP connection and the TLS handshake, request the whole
// call, body included.
func NewClientWithTimeouts(server ServerURL, device DeviceIdentity, connect, request time.Duration) (*Client, error) {
	dialer := &net.Dialer{Timeout: connect}
	transport := &http.Transport{
		Proxy:               http.ProxyFromEnvironment,
		DialContext:         dialer.DialContext,
		TLSHandshakeTimeout: connect,
		ForceAttemptHTTP2:   true,
		DisableCompression:  true,
		MaxIdleConns:        16,
		IdleConnTimeout:     90 * time.Second,
	}
	return &Client{
		http: &http.Client{
			Transport: transport,
			Timeout:   request,
			// An Emby server never needs redirects for API calls; following them
			// could forward the auth header to another host.
			CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
		},
		server: server,
		device: device,
	}, nil
}

// WithToken is `with_token`.
func (c *Client) WithToken(token AccessToken) *Client {
	out := *c
	out.token = &token
	return &out
}

// SameToken: both clients carry the same token. A 401 only ends the session whose
// token was refused, not a newer one (ADR 0012).
func (c *Client) SameToken(other *Client) bool {
	if c == nil || other == nil || c.token == nil || other.token == nil {
		return false
	}
	return c.token.Expose() == other.token.Expose()
}

// accessToken is only for EmbyService.exchange_identity (ADR 0125).
func (c *Client) accessToken() *AccessToken { return c.token }

func (c *Client) Server() ServerURL { return c.server }

// DeviceID is the id of this device, matched against `GET /Sessions` (ADR 0124).
func (c *Client) DeviceID() string { return c.device.ID }

// LibrarySocketURL is `ws(s)://…/embywebsocket` of the library events (ADR 0110).
// Emby 4.10 only binds the socket to the session with the token in `api_key`, so this
// URL holds a secret: never log it.
func (c *Client) LibrarySocketURL() (string, error) {
	if c.token == nil {
		return "", ErrNotSignedIn
	}
	base := c.server.String() + "/embywebsocket"
	// http and ws share port 80, https and wss 443: `set_scheme` keeps the rest as is.
	var rest string
	switch {
	case strings.HasPrefix(base, "https://"):
		rest = "wss://" + strings.TrimPrefix(base, "https://")
	case strings.HasPrefix(base, "http://"):
		rest = "ws://" + strings.TrimPrefix(base, "http://")
	default:
		return "", NewInvalidURL("ws")
	}
	return rest + "?" + encodeQuery([]Param{{"api_key", c.token.Expose()}, {"deviceId", c.device.ID}}), nil
}

// AuthorizationHeader is the `X-Emby-Authorization` value. Quotes and commas are
// stripped from values so a device name cannot break the header syntax.
func (c *Client) AuthorizationHeader() string {
	clean := strings.NewReplacer(`"`, "", ",", "").Replace
	h := fmt.Sprintf(`Emby Client="%s", Device="%s", DeviceId="%s", Version="%s"`,
		ClientName, clean(c.device.Name), clean(c.device.ID), clean(c.device.Version))
	if c.token != nil {
		h += fmt.Sprintf(`, Token="%s"`, clean(c.token.Expose()))
	}
	return h
}

// StreamAuthHeaders is the authentication of the player's HTTP requests: a header,
// never the URL.
func (c *Client) StreamAuthHeaders() ([][2]string, error) {
	if c.token == nil {
		return nil, ErrNotSignedIn
	}
	return [][2]string{{"X-Emby-Token", c.token.Expose()}}, nil
}

func (c *Client) requireToken() error {
	if c.token == nil {
		return ErrNotSignedIn
	}
	return nil
}

// ------------------------------------------------------------------ transport

// request sends method to url with query (encoded as form_urlencoded, in order) and an
// optional JSON body. Query values never carry secrets.
func (c *Client) request(ctx context.Context, method, url string, query []Param, body any) (*http.Response, error) {
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
			return nil, NewInvalidResponse(err.Error())
		}
		reader = bytes.NewReader(bytes.TrimSuffix(buf.Bytes(), []byte("\n")))
	}
	req, err := http.NewRequestWithContext(ctx, method, url, reader)
	if err != nil {
		// A builder error is none of is_timeout, is_decode, is_connect, is_request in
		// reqwest: the last branch of the mapping.
		return nil, NewInvalidResponse(rootCause(err))
	}
	req.Header.Set("X-Emby-Authorization", c.AuthorizationHeader())
	req.Header.Set("Accept", "application/json")
	req.Header.Set("User-Agent", ClientName+"/"+c.device.Version)
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	res, err := c.http.Do(req)
	if err != nil {
		return nil, sendError(ctx, err)
	}
	return res, nil
}

// sendError is `From<reqwest::Error> for EmbyError` for a failed send.
func sendError(ctx context.Context, err error) error {
	if errors.Is(ctx.Err(), context.Canceled) {
		return ErrCancelled
	}
	if isTimeout(err) {
		return ErrTimeout
	}
	// net/http cannot tell a connect failure from another request failure: both are
	// `is_connect() || is_request()` in reqwest, hence Unreachable.
	return NewUnreachable(rootCause(err))
}

// bodyError maps a failure while reading the answer: reqwest classes it `Decode`.
func bodyError(ctx context.Context, err error) error {
	if errors.Is(ctx.Err(), context.Canceled) {
		return ErrCancelled
	}
	if isTimeout(err) {
		return ErrTimeout
	}
	return NewInvalidResponse("JSON illisible")
}

func isTimeout(err error) bool {
	if errors.Is(err, context.DeadlineExceeded) {
		return true
	}
	var ne net.Error
	return errors.As(err, &ne) && ne.Timeout()
}

// rootCause is the text of the innermost error: never the *url.Error text, which
// holds the URL.
func rootCause(err error) string {
	for {
		next := errors.Unwrap(err)
		if next == nil {
			return err.Error()
		}
		err = next
	}
}

// check maps the HTTP status to an error. authenticated tells an expired token (401
// on a token call) from a wrong password (401 on sign-in). The body of a refused
// answer is closed.
func check(res *http.Response, authenticated bool) (*http.Response, error) {
	s := res.StatusCode
	if s >= 200 && s < 300 {
		return res, nil
	}
	_ = res.Body.Close()
	switch {
	case s == http.StatusUnauthorized && authenticated:
		return nil, ErrSessionExpired
	case s == http.StatusUnauthorized:
		return nil, ErrInvalidCredentials
	case s == http.StatusForbidden:
		return nil, ErrForbidden
	case s >= 300 && s < 400:
		return nil, NewInvalidResponse(fmt.Sprintf("redirection HTTP %d non suivie", s))
	case s == http.StatusNotFound:
		return nil, ErrNotFound
	default:
		return nil, NewServer(uint16(s))
	}
}

// decodeJSON reads the whole answer and decodes it with serde's rules.
func decodeJSON[T any](ctx context.Context, res *http.Response) (T, error) {
	var out T
	defer res.Body.Close()
	data, err := io.ReadAll(res.Body)
	if err != nil {
		return out, bodyError(ctx, err)
	}
	if err := decodeStrict(data, &out); err != nil {
		return out, NewInvalidResponse(err.Error())
	}
	return out, nil
}

// call sends the request, checks the status and decodes the answer.
func call[T any](ctx context.Context, c *Client, method, url string, query []Param, body any, authenticated bool) (T, error) {
	var zero T
	res, err := c.request(ctx, method, url, query, body)
	if err != nil {
		return zero, err
	}
	if res, err = check(res, authenticated); err != nil {
		return zero, err
	}
	return decodeJSON[T](ctx, res)
}

// callNoContent sends the request and checks the status only.
func (c *Client) callNoContent(ctx context.Context, method, url string, query []Param, body any) error {
	res, err := c.request(ctx, method, url, query, body)
	if err != nil {
		return err
	}
	if res, err = check(res, true); err != nil {
		return err
	}
	_, _ = io.Copy(io.Discard, res.Body)
	return res.Body.Close()
}

// pathSegment checks an id before it goes into a path: ids come from the server,
// but are still checked.
func pathSegment(id string) (string, error) {
	if id != "" && !strings.ContainsFunc(id, func(r rune) bool { return !isASCIIAlnum(r) && r != '-' }) {
		return id, nil
	}
	return "", NewInvalidResponse("identifiant invalide")
}

// segments checks every id, in order, and returns the first error.
func segments(ids ...string) error {
	for _, id := range ids {
		if _, err := pathSegment(id); err != nil {
			return err
		}
	}
	return nil
}

// encodeQuery is `form_urlencoded::Serializer` as `Url::parse_with_params` uses it:
// pairs in order, `byte_serialize` (formenc) on keys and values.
func encodeQuery(query []Param) string {
	pairs := make([][2]string, len(query))
	for i, p := range query {
		pairs[i] = [2]string{p.Key, p.Value}
	}
	return formenc.Serialize(pairs)
}

// ------------------------------------------------------------------ endpoints

// "Reprendre" and "À suivre" rows: series name, runtime and the parent artwork come by
// default; the images feed the 16:9 card.
const (
	watchFields = "PrimaryImageAspectRatio"
	watchImages = "Primary,Backdrop,Thumb"
	heroFields  = "Overview,ProductionYear,PrimaryImageAspectRatio"
	heroImages  = "Primary,Backdrop,Logo"
)

// PublicInfo is `GET /System/Info/Public`.
func (c *Client) PublicInfo(ctx context.Context) (PublicSystemInfo, error) {
	return call[PublicSystemInfo](ctx, c, http.MethodGet, c.server.API("/System/Info/Public"), nil, nil, false)
}

// Authenticate signs in by user name. The password is only sent in the request body.
func (c *Client) Authenticate(ctx context.Context, username, password string) (AuthenticationResult, error) {
	result, err := call[AuthenticationResult](ctx, c, http.MethodPost, c.server.API("/Users/AuthenticateByName"), nil,
		AuthenticateUserByName{Username: username, Pw: password}, false)
	if err != nil {
		return AuthenticationResult{}, err
	}
	if result.AccessToken == "" {
		return AuthenticationResult{}, NewInvalidResponse("jeton absent")
	}
	return result, nil
}

// User is the current user; it also checks that the stored token is still accepted.
func (c *Client) User(ctx context.Context, userID string) (UserDto, error) {
	if err := c.requireToken(); err != nil {
		return UserDto{}, err
	}
	if err := segments(userID); err != nil {
		return UserDto{}, err
	}
	return call[UserDto](ctx, c, http.MethodGet, c.server.API("/Users/"+userID), nil, nil, true)
}

// Logout revokes the token server-side (`204` on success).
func (c *Client) Logout(ctx context.Context) error {
	if err := c.requireToken(); err != nil {
		return err
	}
	return c.callNoContent(ctx, http.MethodPost, c.server.API("/Sessions/Logout"), nil, nil)
}

type itemsPage = QueryResult[BaseItemDto]

// userCall checks the token and the ids, then calls url (built from the checked ids).
func userCall[T any](ctx context.Context, c *Client, method string, ids []string, url func() string, query func() []Param) (T, error) {
	var zero T
	if err := c.requireToken(); err != nil {
		return zero, err
	}
	if err := segments(ids...); err != nil {
		return zero, err
	}
	var q []Param
	if query != nil {
		q = query()
	}
	return call[T](ctx, c, method, url(), q, nil, true)
}

// Views is the libraries visible to the user (`/Users/{Id}/Views`).
func (c *Client) Views(ctx context.Context, userID string) (itemsPage, error) {
	return userCall[itemsPage](ctx, c, http.MethodGet, []string{userID},
		func() string { return c.server.API("/Users/" + userID + "/Views") }, nil)
}

// ItemsShape is what a library query returns (ADR 0101). StartIndex and Limit only
// apply to ShapePage.
type ItemsShape int

const (
	ShapePage ItemsShape = iota
	ShapeSortNames
	ShapeRandom
)

// ItemsQuery is the parameters of one library page.
type ItemsQuery struct {
	ParentID   string
	ItemTypes  string
	StartIndex uint32
	Limit      uint32
	// Options are checked by the caller: the genre is a usable one (IsUsableGenre).
	Options ItemsOptions
	Shape   ItemsShape
}

// Items is one page of a library, sorted and filtered as asked; or, by Shape, the
// sort names of the whole list or one random title of it (ADR 0101). The server
// ignores an unknown `ParentId` and returns the whole server: callers pass known
// library ids only.
func (c *Client) Items(ctx context.Context, userID string, query ItemsQuery) (itemsPage, error) {
	return userCall[itemsPage](ctx, c, http.MethodGet, []string{userID, query.ParentID},
		func() string { return c.server.API("/Users/" + userID + "/Items") },
		func() []Param {
			o := query.Options
			params := []Param{
				{"ParentId", query.ParentID},
				{"Recursive", "true"},
				{"IncludeItemTypes", query.ItemTypes},
			}
			switch query.Shape {
			case ShapePage:
				params = append(params,
					Param{"SortBy", o.Sort.SortBy()},
					Param{"SortOrder", o.SortOrder()},
					Param{"StartIndex", strconv.FormatUint(uint64(query.StartIndex), 10)},
					Param{"Limit", strconv.FormatUint(uint64(query.Limit), 10)},
					Param{"Fields", "PrimaryImageAspectRatio,ProductionYear"},
					Param{"EnableImageTypes", "Primary"},
					Param{"ImageTypeLimit", "1"},
					Param{"EnableUserData", "true"},
				)
			case ShapeSortNames:
				// Same order as the grid, no page: positions match what it shows.
				params = append(params,
					Param{"SortBy", o.Sort.SortBy()},
					Param{"SortOrder", o.SortOrder()},
					Param{"Fields", "SortName"},
					Param{"EnableImages", "false"},
					Param{"EnableUserData", "false"},
				)
			case ShapeRandom:
				// Drawn by the server among the titles the grid shows.
				params = append(params,
					Param{"SortBy", "Random"},
					Param{"Limit", "1"},
					Param{"Fields", "PrimaryImageAspectRatio,ProductionYear"},
					Param{"EnableImageTypes", "Primary"},
					Param{"ImageTypeLimit", "1"},
					Param{"EnableUserData", "true"},
				)
			}
			if f := o.Filters(); f != "" {
				params = append(params, Param{"Filters", f})
			}
			if o.Genre != nil {
				params = append(params, Param{"Genres", *o.Genre})
			}
			if years, ok := o.Years(); ok {
				params = append(params, Param{"Years", years})
			}
			return params
		})
}

// ProductionYears is the production year of every film or series of a library,
// without filters (decade picker, ADR 0101).
func (c *Client) ProductionYears(ctx context.Context, userID, parentID, itemTypes string) (itemsPage, error) {
	return userCall[itemsPage](ctx, c, http.MethodGet, []string{userID, parentID},
		func() string { return c.server.API("/Users/" + userID + "/Items") },
		func() []Param {
			return []Param{
				{"ParentId", parentID},
				{"Recursive", "true"},
				{"IncludeItemTypes", itemTypes},
				{"Fields", "ProductionYear"},
				{"EnableImages", "false"},
				{"EnableUserData", "false"},
			}
		})
}

// Genres is the genres of the films or series of a library, by name (`/Genres`).
func (c *Client) Genres(ctx context.Context, userID, parentID, itemTypes string) (itemsPage, error) {
	return userCall[itemsPage](ctx, c, http.MethodGet, []string{userID, parentID},
		func() string { return c.server.API("/Genres") },
		func() []Param {
			return []Param{
				{"UserId", userID},
				{"ParentId", parentID},
				{"IncludeItemTypes", itemTypes},
				{"Recursive", "true"},
				{"SortBy", "SortName"},
				{"SortOrder", "Ascending"},
				{"EnableImages", "false"},
				{"EnableUserData", "false"},
			}
		})
}

func limitText(limit uint32) string { return strconv.FormatUint(uint64(limit), 10) }

// ResumeItems is the films and episodes started but not finished, most recent first
// (`/Users/{Id}/Items/Resume`).
func (c *Client) ResumeItems(ctx context.Context, userID string, limit uint32) (itemsPage, error) {
	return c.resume(ctx, userID, limit, watchFields, watchImages)
}

// HeroResume is the home hero (ADR 0015): the items of ResumeItems, with the overview
// and the wide artwork (episodes also get their series' backdrop and logo).
func (c *Client) HeroResume(ctx context.Context, userID string, limit uint32) (itemsPage, error) {
	return c.resume(ctx, userID, limit, heroFields, heroImages)
}

func (c *Client) resume(ctx context.Context, userID string, limit uint32, fields, images string) (itemsPage, error) {
	return userCall[itemsPage](ctx, c, http.MethodGet, []string{userID},
		func() string { return c.server.API("/Users/" + userID + "/Items/Resume") },
		func() []Param {
			return []Param{
				{"Limit", limitText(limit)},
				{"MediaTypes", "Video"},
				{"IncludeItemTypes", "Movie,Episode"},
				{"Fields", fields},
				{"EnableUserData", "true"},
				{"ImageTypeLimit", "1"},
				{"EnableImageTypes", images},
			}
		})
}

// HeroLatest is the home hero (ADR 0015): films and series not watched yet that have
// a backdrop, most recently added first.
func (c *Client) HeroLatest(ctx context.Context, userID string, limit uint32) (itemsPage, error) {
	return userCall[itemsPage](ctx, c, http.MethodGet, []string{userID},
		func() string { return c.server.API("/Users/" + userID + "/Items") },
		func() []Param {
			return []Param{
				{"Recursive", "true"},
				{"IncludeItemTypes", "Movie,Series"},
				{"IsPlayed", "false"},
				{"ImageTypes", "Backdrop"},
				{"SortBy", "DateCreated"},
				{"SortOrder", "Descending"},
				{"Limit", limitText(limit)},
				{"Fields", heroFields},
				{"EnableUserData", "true"},
				{"ImageTypeLimit", "1"},
				{"EnableImageTypes", heroImages},
			}
		})
}

// FirstUnplayedEpisode is the first episode of a series not watched yet, in the
// series' order (`/Shows/{Id}/Episodes?IsPlayed=false`). Unknown series: 404.
func (c *Client) FirstUnplayedEpisode(ctx context.Context, userID, seriesID string) (itemsPage, error) {
	return userCall[itemsPage](ctx, c, http.MethodGet, []string{seriesID, userID},
		func() string { return c.server.API("/Shows/" + seriesID + "/Episodes") },
		func() []Param {
			return []Param{
				{"UserId", userID},
				{"IsPlayed", "false"},
				{"Limit", "1"},
				{"EnableUserData", "true"},
				{"EnableImageTypes", "Primary"},
				{"ImageTypeLimit", "1"},
			}
		})
}

// NextUp is the next episode to watch of each series in progress (`/Shows/NextUp`).
func (c *Client) NextUp(ctx context.Context, userID string, limit uint32) (itemsPage, error) {
	return userCall[itemsPage](ctx, c, http.MethodGet, []string{userID},
		func() string { return c.server.API("/Shows/NextUp") },
		func() []Param {
			return []Param{
				{"UserId", userID},
				{"Limit", limitText(limit)},
				{"Fields", watchFields},
				{"EnableUserData", "true"},
				{"ImageTypeLimit", "1"},
				{"EnableImageTypes", watchImages},
			}
		})
}

// Search is the films, series or episodes whose name matches term, across every
// library the user can see (`/Users/{Id}/Items?SearchTerm=`). itemType is `Movie`,
// `Series` or `Episode`; episodes come with the 16:9 images of the home cards.
func (c *Client) Search(ctx context.Context, userID, term, itemType string, limit uint32) (itemsPage, error) {
	fields, images := "PrimaryImageAspectRatio,ProductionYear", "Primary"
	if itemType == "Episode" {
		fields, images = watchFields, watchImages
	}
	return userCall[itemsPage](ctx, c, http.MethodGet, []string{userID},
		func() string { return c.server.API("/Users/" + userID + "/Items") },
		func() []Param {
			return []Param{
				{"SearchTerm", term},
				{"Recursive", "true"},
				{"IncludeItemTypes", itemType},
				{"Limit", limitText(limit)},
				{"Fields", fields},
				{"EnableImageTypes", images},
				{"ImageTypeLimit", "1"},
				{"EnableUserData", "true"},
			}
		})
}

// HomeSections is the sections of the user's home screen (`/Users/{Id}/HomeSections`).
func (c *Client) HomeSections(ctx context.Context, userID string) ([]HomeSectionDto, error) {
	return userCall[[]HomeSectionDto](ctx, c, http.MethodGet, []string{userID},
		func() string { return c.server.API("/Users/" + userID + "/HomeSections") }, nil)
}

// SectionItems is the items of one home section (`/Users/{Id}/Sections/{Id}/Items`).
// On Emby 4.10.1.0 the "resume" section holds the next episodes `/Shows/NextUp` omits.
func (c *Client) SectionItems(ctx context.Context, userID, sectionID string, limit uint32) (itemsPage, error) {
	return userCall[itemsPage](ctx, c, http.MethodGet, []string{userID, sectionID},
		func() string { return c.server.API("/Users/" + userID + "/Sections/" + sectionID + "/Items") },
		func() []Param {
			return []Param{
				{"Limit", limitText(limit)},
				{"Fields", watchFields},
				{"EnableUserData", "true"},
				{"ImageTypeLimit", "1"},
				{"EnableImageTypes", watchImages},
			}
		})
}

// Latest is the latest additions of a library, newest first
// (`/Users/{Id}/Items/Latest`). Episodes come grouped as their series; the answer is a
// bare array. Emby 4.10.1.0 answers 500 to an unknown `ParentId`: callers pass known
// ids only.
func (c *Client) Latest(ctx context.Context, userID, parentID string, limit uint32) ([]BaseItemDto, error) {
	return userCall[[]BaseItemDto](ctx, c, http.MethodGet, []string{userID, parentID},
		func() string { return c.server.API("/Users/" + userID + "/Items/Latest") },
		func() []Param {
			return []Param{
				{"ParentId", parentID},
				{"Limit", limitText(limit)},
				{"Fields", "PrimaryImageAspectRatio,ProductionYear"},
				{"EnableUserData", "true"},
				{"ImageTypeLimit", "1"},
				{"EnableImageTypes", "Primary"},
			}
		})
}

// Item is the full item as the user sees it (`/Users/{Id}/Items/{Id}`): overview,
// people, media sources and user data come without `Fields`. Unknown id: 404.
func (c *Client) Item(ctx context.Context, userID, itemID string) (BaseItemDto, error) {
	return userCall[BaseItemDto](ctx, c, http.MethodGet, []string{userID, itemID},
		func() string { return c.server.API("/Users/" + userID + "/Items/" + itemID) }, nil)
}

// Titles is the titles of a person, collection, genre or studio (`/Users/{Id}/Items`,
// ADR 0121); the query comes checked from TitlesParams.
func (c *Client) Titles(ctx context.Context, userID string, params []Param) (itemsPage, error) {
	return userCall[itemsPage](ctx, c, http.MethodGet, []string{userID},
		func() string { return c.server.API("/Users/" + userID + "/Items") },
		func() []Param { return params })
}

// Similar is the films and series like an item (`/Items/{Id}/Similar`, ADR 0121); the
// query comes checked from SimilarParams.
func (c *Client) Similar(ctx context.Context, itemID string, params []Param) (itemsPage, error) {
	return userCall[itemsPage](ctx, c, http.MethodGet, []string{itemID},
		func() string { return c.server.API("/Items/" + itemID + "/Similar") },
		func() []Param { return params })
}

// LocalTrailers is the trailer files of an item (`/Users/{Id}/Items/{Id}/LocalTrailers`,
// a bare `BaseItemDto[]`), as the TV reads them (ADR 0121).
func (c *Client) LocalTrailers(ctx context.Context, userID, itemID string) ([]BaseItemDto, error) {
	return userCall[[]BaseItemDto](ctx, c, http.MethodGet, []string{userID, itemID},
		func() string { return c.server.API("/Users/" + userID + "/Items/" + itemID + "/LocalTrailers") },
		func() []Param { return []Param{{"EnableImages", "false"}, {"EnableUserData", "false"}} })
}

// Seasons is the seasons of a series, in order (`0` = specials). Unknown series: 404.
func (c *Client) Seasons(ctx context.Context, userID, seriesID string) (itemsPage, error) {
	return userCall[itemsPage](ctx, c, http.MethodGet, []string{seriesID, userID},
		func() string { return c.server.API("/Shows/" + seriesID + "/Seasons") },
		func() []Param {
			return []Param{
				{"UserId", userID},
				{"EnableUserData", "true"},
				{"EnableImageTypes", "Primary"},
				{"ImageTypeLimit", "1"},
			}
		})
}

// EpisodesQuery is the parameters of one page of episodes.
type EpisodesQuery struct {
	SeriesID   string
	SeasonID   string
	StartIndex uint32
	Limit      uint32
}

// Episodes is one page of episodes of a season. Emby 4.10.1.0 trusts `SeasonId` over
// the series id: a season of another series returns that series' episodes, so callers
// must check `SeriesId` on the answer.
func (c *Client) Episodes(ctx context.Context, userID string, query EpisodesQuery) (itemsPage, error) {
	return userCall[itemsPage](ctx, c, http.MethodGet, []string{query.SeriesID, userID, query.SeasonID},
		func() string { return c.server.API("/Shows/" + query.SeriesID + "/Episodes") },
		func() []Param {
			return []Param{
				{"UserId", userID},
				{"SeasonId", query.SeasonID},
				{"StartIndex", limitText(query.StartIndex)},
				{"Limit", limitText(query.Limit)},
				// Without it episodes come without their overview.
				{"Fields", "Overview"},
				{"EnableUserData", "true"},
				{"EnableImageTypes", "Primary"},
				{"ImageTypeLimit", "1"},
			}
		})
}

// AdjacentEpisodes is the episodes around one of a series, across seasons (ADR 0016).
// Emby 4.10.1.0 answers up to 5 episodes before it then the rest of the series, not
// just its two neighbours: no images nor user data, to keep the answer small.
func (c *Client) AdjacentEpisodes(ctx context.Context, userID, seriesID, episodeID string) (itemsPage, error) {
	return userCall[itemsPage](ctx, c, http.MethodGet, []string{seriesID, userID, episodeID},
		func() string { return c.server.API("/Shows/" + seriesID + "/Episodes") },
		func() []Param {
			return []Param{
				{"UserId", userID},
				{"AdjacentTo", episodeID},
				{"EnableImages", "false"},
				{"EnableUserData", "false"},
			}
		})
}

// PlaybackInfo is the playback negotiation (ADR 0007): the server answers with its
// sources and how each can be played for this profile. Unknown id: 404.
func (c *Client) PlaybackInfo(ctx context.Context, userID, itemID string, profile *DeviceProfile,
	forceTranscode bool, mediaSourceID *string, audioStreamIndex *int32) (PlaybackInfoResponse, error) {
	if err := c.requireToken(); err != nil {
		return PlaybackInfoResponse{}, err
	}
	if err := segments(itemID, userID); err != nil {
		return PlaybackInfoResponse{}, err
	}
	body := PlaybackInfoRequest{
		UserID:              userID,
		MaxStreamingBitrate: profile.MaxStreamingBitrate,
		DeviceProfile:       profile,
		IsPlayback:          true,
		AutoOpenLiveStream:  false,
		EnableDirectPlay:    !forceTranscode,
		EnableDirectStream:  !forceTranscode,
		MediaSourceID:       mediaSourceID,
		AudioStreamIndex:    audioStreamIndex,
	}
	return call[PlaybackInfoResponse](ctx, c, http.MethodPost, c.server.API("/Items/"+itemID+"/PlaybackInfo"),
		[]Param{{"UserId", userID}}, body, true)
}

// ReportPlayback is a playback report (ADR 0008) to `/Sessions/Playing`, `…/Progress`
// or `…/Stopped`. Emby 4.10.1.0 answers 204.
func (c *Client) ReportPlayback(ctx context.Context, path string, body PlaybackReportBody) error {
	if err := c.requireToken(); err != nil {
		return err
	}
	if err := segments(body.ItemID); err != nil {
		return err
	}
	return c.callNoContent(ctx, http.MethodPost, c.server.API(path), nil, body)
}

// PostCapabilities declares this session controllable and able to receive
// `SendString` (`POST /Sessions/Capabilities/Full`, ADR 0124). Emby answers 204.
func (c *Client) PostCapabilities(ctx context.Context) error {
	if err := c.requireToken(); err != nil {
		return err
	}
	// A map, as `json!`: keys in alphabetical order.
	body := map[string]any{
		"PlayableMediaTypes":   []string{"Video"},
		"SupportedCommands":    []string{"SendString"},
		"SupportsMediaControl": true,
	}
	return c.callNoContent(ctx, http.MethodPost, c.server.API("/Sessions/Capabilities/Full"), nil, body)
}

// Sessions is the sessions the user can control (`GET /Sessions?ControllableByUserId=`).
func (c *Client) Sessions(ctx context.Context, userID string) ([]SessionInfoDto, error) {
	return userCall[[]SessionInfoDto](ctx, c, http.MethodGet, []string{userID},
		func() string { return c.server.API("/Sessions") },
		func() []Param { return []Param{{"ControllableByUserId", userID}} })
}

// SendString relays text to another session (`POST /Sessions/{Id}/Command`, general
// command `SendString`). The `/Command/SendString` path drops the arguments.
func (c *Client) SendString(ctx context.Context, sessionID, text string) error {
	if err := c.requireToken(); err != nil {
		return err
	}
	if err := segments(sessionID); err != nil {
		return err
	}
	body := map[string]any{"Name": "SendString", "Arguments": map[string]any{"String": text}}
	return c.callNoContent(ctx, http.MethodPost, c.server.API("/Sessions/"+sessionID+"/Command"), nil, body)
}

// StopActiveEncodings stops the server's encodings of one play session of this device
// (`DELETE /Videos/ActiveEncodings?DeviceId=&PlaySessionId=`, ADR 0117).
func (c *Client) StopActiveEncodings(ctx context.Context, playSessionID string) error {
	if err := c.requireToken(); err != nil {
		return err
	}
	if err := segments(playSessionID); err != nil {
		return err
	}
	return c.callNoContent(ctx, http.MethodDelete, c.server.API("/Videos/ActiveEncodings"),
		[]Param{{"DeviceId", c.device.ID}, {"PlaySessionId", playSessionID}}, nil)
}

// SetFavorite adds or removes a favorite (`POST` / `DELETE
// /Users/{Id}/FavoriteItems/{Id}`); the server answers with the item's new user data.
func (c *Client) SetFavorite(ctx context.Context, userID, itemID string, favorite bool) (UserItemData, error) {
	method := http.MethodDelete
	if favorite {
		method = http.MethodPost
	}
	return userCall[UserItemData](ctx, c, method, []string{userID, itemID},
		func() string { return c.server.API("/Users/" + userID + "/FavoriteItems/" + itemID) }, nil)
}

// Favorites is the favorite films and series of the user, by title (`Filters=IsFavorite`).
func (c *Client) Favorites(ctx context.Context, userID string, limit uint32) (itemsPage, error) {
	return userCall[itemsPage](ctx, c, http.MethodGet, []string{userID},
		func() string { return c.server.API("/Users/" + userID + "/Items") },
		func() []Param {
			return []Param{
				{"Filters", "IsFavorite"},
				{"Recursive", "true"},
				{"IncludeItemTypes", "Movie,Series"},
				{"SortBy", "SortName"},
				{"SortOrder", "Ascending"},
				{"Limit", limitText(limit)},
				{"Fields", "PrimaryImageAspectRatio,ProductionYear"},
				{"EnableImageTypes", "Primary"},
				{"ImageTypeLimit", "1"},
				{"EnableUserData", "true"},
			}
		})
}

// SetPlayed marks an item played on datePlayed (`yyyyMMddHHmmss`, UTC), or unplayed
// with nil (`POST` / `DELETE /Users/{Id}/PlayedItems/{Id}`, ADR 0096); the server
// answers with the item's new user data.
func (c *Client) SetPlayed(ctx context.Context, userID, itemID string, datePlayed *string) (UserItemData, error) {
	method := http.MethodDelete
	var query []Param
	if datePlayed != nil {
		method = http.MethodPost
		query = []Param{{"DatePlayed", *datePlayed}}
	}
	return userCall[UserItemData](ctx, c, method, []string{userID, itemID},
		func() string { return c.server.API("/Users/" + userID + "/PlayedItems/" + itemID) },
		func() []Param { return query })
}

// HideFromResume takes an item out of "Reprendre", or puts it back
// (`POST /Users/{Id}/Items/{Id}/HideFromResume?Hide=`, ADR 0096).
func (c *Client) HideFromResume(ctx context.Context, userID, itemID string, hide bool) (UserItemData, error) {
	return userCall[UserItemData](ctx, c, http.MethodPost, []string{userID, itemID},
		func() string { return c.server.API("/Users/" + userID + "/Items/" + itemID + "/HideFromResume") },
		func() []Param { return []Param{{"Hide", strconv.FormatBool(hide)}} })
}

// Playlists is the playlists of the user, by name
// (`/Users/{Id}/Items?IncludeItemTypes=Playlist&Recursive=true`, ADR 0100).
func (c *Client) Playlists(ctx context.Context, userID string, limit uint32) (itemsPage, error) {
	return userCall[itemsPage](ctx, c, http.MethodGet, []string{userID},
		func() string { return c.server.API("/Users/" + userID + "/Items") },
		func() []Param {
			return []Param{
				{"IncludeItemTypes", "Playlist"},
				{"Recursive", "true"},
				{"SortBy", "SortName"},
				{"SortOrder", "Ascending"},
				{"Limit", limitText(limit)},
				{"EnableImageTypes", "Primary"},
				{"ImageTypeLimit", "1"},
			}
		})
}

// HomePlaylists is the playlists of the home row, A → Z with their entry count (ADR
// 0122). No `MediaTypes`: on Emby 4.10.2.0 `MediaTypes=Video` next to
// `IncludeItemTypes=Playlist` answered every video of the server; audio playlists are
// left out by the caller.
func (c *Client) HomePlaylists(ctx context.Context, userID string, limit uint32) (itemsPage, error) {
	return userCall[itemsPage](ctx, c, http.MethodGet, []string{userID},
		func() string { return c.server.API("/Users/" + userID + "/Items") },
		func() []Param {
			return []Param{
				{"IncludeItemTypes", "Playlist"},
				{"Recursive", "true"},
				{"SortBy", "SortName"},
				{"SortOrder", "Ascending"},
				{"Limit", limitText(limit)},
				{"Fields", "ChildCount,PrimaryImageAspectRatio"},
				{"EnableUserData", "false"},
				{"ImageTypeLimit", "1"},
				{"EnableImageTypes", "Primary"},
			}
		})
}

// PlaylistItems is the entries of a playlist in its order
// (`GET /Playlists/{Id}/Items?UserId=`, ADR 0122).
func (c *Client) PlaylistItems(ctx context.Context, userID, playlistID string, limit uint32) (itemsPage, error) {
	return userCall[itemsPage](ctx, c, http.MethodGet, []string{playlistID, userID},
		func() string { return c.server.API("/Playlists/" + playlistID + "/Items") },
		func() []Param {
			return []Param{
				{"UserId", userID},
				{"Limit", limitText(limit)},
				{"Fields", "PrimaryImageAspectRatio"},
				{"EnableUserData", "true"},
				{"ImageTypeLimit", "1"},
				{"EnableImageTypes", "Primary,Backdrop,Thumb"},
			}
		})
}

// AddToPlaylist appends an item to a playlist
// (`POST /Playlists/{Id}/Items?Ids=&UserId=` → `AddToPlaylistResult`, ADR 0100).
func (c *Client) AddToPlaylist(ctx context.Context, userID, playlistID, itemID string) (AddToPlaylistResultDto, error) {
	return userCall[AddToPlaylistResultDto](ctx, c, http.MethodPost, []string{playlistID, itemID, userID},
		func() string { return c.server.API("/Playlists/" + playlistID + "/Items") },
		func() []Param { return []Param{{"Ids", itemID}, {"UserId", userID}} })
}

// CreatePlaylist creates a video playlist holding one item
// (`POST /Playlists?Name=&Ids=&MediaType=Video&UserId=` → `PlaylistCreationResult`,
// ADR 0100). The name is checked by the caller.
func (c *Client) CreatePlaylist(ctx context.Context, userID, name, itemID string) (PlaylistCreationResultDto, error) {
	return userCall[PlaylistCreationResultDto](ctx, c, http.MethodPost, []string{itemID, userID},
		func() string { return c.server.API("/Playlists") },
		func() []Param {
			return []Param{{"Name", name}, {"Ids", itemID}, {"MediaType", "Video"}, {"UserId", userID}}
		})
}

// Image is the artwork bytes and content type for the `emby-img` protocol (ADR 0005).
// Like Rust, it does not require a token: the service checks the session first.
func (c *Client) Image(ctx context.Context, req ImageRequest) (string, []byte, error) {
	query := []Param{
		{"tag", req.Tag},
		{"maxWidth", strconv.FormatUint(uint64(req.MaxWidth), 10)},
		{"quality", "90"},
	}
	res, err := c.request(ctx, http.MethodGet, c.server.API(req.APIPath()), query, nil)
	if err != nil {
		return "", nil, err
	}
	if res, err = check(res, true); err != nil {
		return "", nil, err
	}
	defer res.Body.Close()
	contentType := res.Header.Get("Content-Type")
	if !strings.HasPrefix(contentType, "image/") {
		return "", nil, NewInvalidResponse("ce n'est pas une image")
	}
	if res.ContentLength > MaxImageBytes {
		return "", nil, NewInvalidResponse("image trop lourde")
	}
	// One byte past the bound is enough to refuse it, without reading a huge body.
	data, err := io.ReadAll(io.LimitReader(res.Body, MaxImageBytes+1))
	if err != nil {
		return "", nil, bodyError(ctx, err)
	}
	if len(data) > MaxImageBytes {
		return "", nil, NewInvalidResponse("image trop lourde")
	}
	return contentType, data, nil
}

// EmbyDate is secs since 1970 as `yyyyMMddHHmmss` in UTC, the format of `DatePlayed`.
func EmbyDate(secs int64) string {
	return time.Unix(secs, 0).UTC().Format("20060102150405")
}

// DatePlayedNow is now as `DatePlayed`.
func DatePlayedNow() string {
	secs := time.Now().Unix()
	if secs < 0 {
		secs = 0
	}
	return EmbyDate(secs)
}
