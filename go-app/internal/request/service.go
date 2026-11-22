package request

// Request session service (ADR 0125), port of src-tauri/src/request/service.rs:
// address in `request.json`, token in the OS credential store per Emby account,
// single-flight exchange, one retry after a 401, persistent sign-out.

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"sync"
	"time"

	"viewer/internal/artworkcache"
	"viewer/internal/emby"
)

// Identity is the Emby account a Request session belongs to, with the token to
// trade.
type Identity struct {
	ServerID  string
	UserID    string
	EmbyToken emby.AccessToken
}

func (id Identity) account() string { return Account(id.ServerID, id.UserID) }

// settings is `request.json`: not a secret (address and signed-out accounts).
type settings struct {
	Address *string `json:"address"`
	// SignedOut: credential accounts that chose "Se déconnecter".
	SignedOut []string `json:"signedOut" ur:"default"`
}

func (s settings) clone() settings {
	out := settings{SignedOut: slices.Clone(s.SignedOut)}
	if s.Address != nil {
		a := *s.Address
		out.Address = &a
	}
	if out.SignedOut == nil {
		out.SignedOut = []string{}
	}
	return out
}

// UrState is `UrState` (camelCase).
type UrState string

const (
	StateNotConfigured UrState = "notConfigured"
	StateSignedOut     UrState = "signedOut"
	StateTooOld        UrState = "tooOld"
	StateUnreachable   UrState = "unreachable"
	StateRejected      UrState = "rejected"
	StateReady         UrState = "ready"
)

// UrStatus is the state of the « Demander » tab.
type UrStatus struct {
	State      UrState  `json:"state"`
	Address    *string  `json:"address"`
	Viewer     *Viewer  `json:"viewer"`
	APIVersion *string  `json:"apiVersion"`
	Missing    []string `json:"missing"`
	Message    *string  `json:"message"`
}

func newStatus(state UrState, address *string) UrStatus {
	return UrStatus{State: state, Address: address, Missing: []string{}}
}

func failedStatus(address *string, err error) UrStatus {
	var state UrState
	switch kindOf(err) {
	case KindNotConfigured:
		state = StateNotConfigured
	case KindSignedOut:
		state = StateSignedOut
	case KindTooOld:
		state = StateTooOld
	case KindRejected, KindRateLimited, KindForbidden:
		state = StateRejected
	default:
		state = StateUnreachable
	}
	status := newStatus(state, address)
	message := err.Error()
	status.Message = &message
	var e RequestError
	if kindOf(err) == KindTooOld && asError(err, &e) {
		v := e.APIVersion
		status.APIVersion = &v
		status.Missing = append([]string{}, e.Missing...)
	}
	return status
}

// checked is a server address checked compatible, with its client.
type checked struct {
	address string
	client  *Client
	version ApiVersion
}

// Revocation is a best-effort `POST /api/auth/logout` of a session already deleted
// locally.
type Revocation struct {
	client *Client
	token  SessionToken
}

// String, GoString and Format keep the token out of every fmt verb (fmt does not
// call SessionToken's on an unexported field).
func (r Revocation) String() string { return redactedText("Revocation") }

func (r Revocation) GoString() string { return r.String() }

func (r Revocation) Format(f fmt.State, _ rune) { writeRedacted(f, "Revocation") }

func (r Revocation) MarshalJSON() ([]byte, error) { return refuseMarshal("Revocation") }

// Send runs the revocation; a failure is only logged, by kind.
func (r *Revocation) Send(ctx context.Context) {
	if err := r.client.SendEmpty(ctx, http.MethodPost, "/api/auth/logout", r.token); err != nil {
		slog.Warn("Request logout not confirmed", "kind", string(kindOf(err)))
	}
}

// rejection keeps the SHA-256 of the refused Emby token, only to compare it with
// the next one: a Service printed by value (fmt walks unexported fields, whatever the
// verb) has no token to show.
type rejection struct {
	tokenHash [sha256.Size]byte
	message   string
}

func hashToken(t emby.AccessToken) [sha256.Size]byte { return sha256.Sum256([]byte(t.Expose())) }

func (r rejection) String() string { return redactedText("rejection") }

func (r rejection) GoString() string { return r.String() }

func (r rejection) Format(f fmt.State, _ rune) { writeRedacted(f, "rejection") }

func (r rejection) MarshalJSON() ([]byte, error) { return refuseMarshal("rejection") }

// String, GoString and Format of Identity: the Emby token is trusted to nobody.
func (id Identity) String() string { return redactedText("Identity") }

func (id Identity) GoString() string { return id.String() }

func (id Identity) Format(f fmt.State, _ rune) { writeRedacted(f, "Identity") }

func (id Identity) MarshalJSON() ([]byte, error) { return refuseMarshal("Identity") }

// Service is `RequestService`. Safe for concurrent use.
type Service struct {
	store      emby.CredentialStore
	path       string
	deviceName string
	version    string
	artwork    *artworkcache.Cache

	settingsMu sync.Mutex
	settings   settings

	checkedMu sync.Mutex
	checked   *checked

	// exchange is the single-flight exchange: the server allows 10 failures per IP
	// and 5 minutes, shared by the household (ADR 0125).
	exchange sync.Mutex

	// rejected: accounts whose exchange was refused, with the Emby token refused: no
	// new attempt with that token until "Se connecter" or another address.
	rejectedMu sync.Mutex
	rejected   map[string]rejection

	// blockedUntil: after a 429 of the exchange, nothing is sent before it (zero:
	// not blocked).
	blockedMu    sync.Mutex
	blockedUntil time.Time

	// now is the clock of blockedUntil (replaced by the tests).
	now func() time.Time
}

// String, GoString and Format keep the refused Emby tokens out of every fmt verb.
func (s *Service) String() string { return redactedText("RequestService") }

func (s *Service) GoString() string { return s.String() }

func (s *Service) Format(f fmt.State, _ rune) { writeRedacted(f, "RequestService") }

// NewService is `RequestService::new`: settings read from path (an unreadable
// file is the default), the address checked again.
func NewService(store emby.CredentialStore, path, deviceName, version string) *Service {
	var st settings
	if data, err := os.ReadFile(path); err != nil || decodeStrict(data, &st) != nil {
		st = settings{}
	}
	// A hand-edited file never bypasses the address policy.
	if st.Address != nil {
		if a, err := NormalizeAddress(*st.Address); err == nil {
			st.Address = &a
		} else {
			st.Address = nil
		}
	}
	return &Service{
		store:      store,
		path:       path,
		settings:   st.clone(),
		deviceName: deviceName,
		version:    version,
		rejected:   make(map[string]rejection),
		now:        time.Now,
	}
}

// WithArtworkCache is `with_artwork_cache`.
func (s *Service) WithArtworkCache(cache *artworkcache.Cache) *Service {
	s.artwork = cache
	return s
}

func (s *Service) address() *string {
	s.settingsMu.Lock()
	defer s.settingsMu.Unlock()
	if s.settings.Address == nil {
		return nil
	}
	a := *s.settings.Address
	return &a
}

// updateSettings writes the changed settings aside, then renames: a reader never
// sees half a file. The settings change in memory only once written.
func (s *Service) updateSettings(f func(*settings)) error {
	s.settingsMu.Lock()
	defer s.settingsMu.Unlock()
	next := s.settings.clone()
	f(&next)
	ioErr := func(err error) error { return NewSettings("request.json : " + err.Error()) }
	if err := os.MkdirAll(filepath.Dir(s.path), 0o755); err != nil {
		return ioErr(err)
	}
	var buf bytes.Buffer
	enc := json.NewEncoder(&buf)
	enc.SetEscapeHTML(false)
	enc.SetIndent("", "  ")
	if err := enc.Encode(next); err != nil {
		return NewSettings(err.Error())
	}
	// `with_extension("json.tmp")`.
	temp := strings.TrimSuffix(s.path, filepath.Ext(s.path)) + ".json.tmp"
	if err := os.WriteFile(temp, bytes.TrimSuffix(buf.Bytes(), []byte("\n")), 0o644); err != nil {
		return ioErr(err)
	}
	if err := os.Rename(temp, s.path); err != nil {
		return ioErr(err)
	}
	s.settings = next
	return nil
}

func (s *Service) isSignedOut(account string) bool {
	s.settingsMu.Lock()
	defer s.settingsMu.Unlock()
	return slices.Contains(s.settings.SignedOut, account)
}

// load is the stored token, only if it was issued by the current address: a token of
// a previous address is never sent to another host.
func (s *Service) load(account string) (*SessionToken, error) {
	stored, err := s.store.Load(account)
	if err != nil {
		return nil, fromEmby(err)
	}
	if stored == nil {
		return nil, nil
	}
	issuer, token, ok := strings.Cut(stored.Expose(), " ")
	address := s.address()
	if !ok || address == nil || *address != issuer {
		return nil, nil
	}
	t := NewSessionToken(token)
	return &t, nil
}

// save keeps `{address} {token}` (a token has no space).
func (s *Service) save(account string, token SessionToken) error {
	address := s.address()
	if address == nil {
		return ErrNotConfigured
	}
	return fromEmby(s.store.Save(account, emby.NewAccessToken(*address+" "+token.Expose())))
}

func (s *Service) deleteToken(account string) error {
	return fromEmby(s.store.Delete(account))
}

// checkBlocked fails fast while a 429 of the exchange is running: only the exchange
// counts against the shared failure limit, a 429 elsewhere concerns that call only.
func (s *Service) checkBlocked() error {
	s.blockedMu.Lock()
	defer s.blockedMu.Unlock()
	if s.blockedUntil.IsZero() {
		return nil
	}
	if left := s.blockedUntil.Sub(s.now()); left > 0 {
		return NewRateLimited(uint64(left/time.Second) + 1)
	}
	s.blockedUntil = time.Time{}
	return nil
}

// note starts the block of a 429.
func (s *Service) note(err error) {
	var e RequestError
	if kindOf(err) == KindRateLimited && asError(err, &e) {
		s.blockedMu.Lock()
		s.blockedUntil = s.now().Add(time.Duration(e.RetryAfterS) * time.Second)
		s.blockedMu.Unlock()
	}
}

// client is the client of the configured address, its API version checked on first
// use and kept until the address changes.
func (s *Service) client(ctx context.Context) (checked, error) {
	address := s.address()
	if address == nil {
		return checked{}, ErrNotConfigured
	}
	s.checkedMu.Lock()
	if c := s.checked; c != nil && c.address == *address {
		out := *c
		s.checkedMu.Unlock()
		return out, nil
	}
	s.checkedMu.Unlock()
	client := NewClient(*address, s.version)
	version, err := client.Version(ctx)
	if err != nil {
		return checked{}, err
	}
	if err := ensureCompatible(version); err != nil {
		return checked{}, err
	}
	c := checked{address: *address, client: client, version: version}
	s.checkedMu.Lock()
	if now := s.address(); now != nil && *now == c.address {
		s.checked = &c
	}
	s.checkedMu.Unlock()
	return c, nil
}

// token is the stored token, or a new one from a single-flight exchange. failed is
// the token a 401 refused: it is replaced only if still the stored one.
func (s *Service) token(ctx context.Context, client *Client, id Identity, failed *SessionToken) (SessionToken, error) {
	key := id.account()
	// First: a token left behind by a failed vault delete is never used.
	if s.isSignedOut(key) {
		return SessionToken{}, ErrSignedOut
	}
	if failed == nil {
		t, err := s.load(key)
		if err != nil {
			return SessionToken{}, err
		}
		if t != nil {
			return *t, nil
		}
	}
	s.exchange.Lock()
	defer s.exchange.Unlock()
	if s.isSignedOut(key) {
		return SessionToken{}, ErrSignedOut
	}
	stored, err := s.load(key)
	if err != nil {
		return SessionToken{}, err
	}
	switch {
	case stored != nil && failed != nil && *stored != *failed:
		return *stored, nil
	case stored != nil && failed == nil:
		return *stored, nil
	case stored != nil:
		if err := s.deleteToken(key); err != nil {
			return SessionToken{}, err
		}
	}
	s.rejectedMu.Lock()
	r, refused := s.rejected[key]
	s.rejectedMu.Unlock()
	if refused && r.tokenHash == hashToken(id.EmbyToken) {
		return SessionToken{}, NewRejected(r.message)
	}
	if err := s.checkBlocked(); err != nil {
		return SessionToken{}, err
	}
	session, err := client.Exchange(ctx, id.UserID, id.EmbyToken, s.deviceName+" (Windows)")
	s.note(err)
	if err != nil {
		var e RequestError
		if kindOf(err) == KindRejected && asError(err, &e) {
			s.latchRejected(id, e.Text)
		}
		return SessionToken{}, err
	}
	if err := s.save(key, session.Token); err != nil {
		return SessionToken{}, err
	}
	return session.Token, nil
}

func (s *Service) latchRejected(id Identity, message string) {
	s.rejectedMu.Lock()
	defer s.rejectedMu.Unlock()
	s.rejected[id.account()] = rejection{tokenHash: hashToken(id.EmbyToken), message: message}
}

// Call is `call`: runs f with a session token, stored or exchanged; on a 401 the
// token is replaced once and f replayed; a second 401 ends in Rejected.
func Call[T any](ctx context.Context, s *Service, id Identity, f func(context.Context, *Client, SessionToken) (T, error)) (T, error) {
	var zero T
	if s.isSignedOut(id.account()) {
		return zero, ErrSignedOut
	}
	c, err := s.client(ctx)
	if err != nil {
		return zero, err
	}
	first, err := s.token(ctx, c.client, id, nil)
	if err != nil {
		return zero, err
	}
	out, err := f(ctx, c.client, first)
	if kindOf(err) != KindRejected {
		return out, err
	}
	second, err := s.token(ctx, c.client, id, &first)
	if err != nil {
		return zero, err
	}
	out, err = f(ctx, c.client, second)
	if kindOf(err) != KindRejected {
		return out, err
	}
	var e RequestError
	asError(err, &e)
	s.exchange.Lock()
	defer s.exchange.Unlock()
	stored, lerr := s.load(id.account())
	if lerr != nil {
		return zero, lerr
	}
	if stored != nil && *stored == second {
		if derr := s.deleteToken(id.account()); derr != nil {
			return zero, derr
		}
	}
	s.latchRejected(id, e.Text)
	return zero, NewRejected(e.Text)
}

// ServiceGet is the service's `get`.
func ServiceGet[T any](ctx context.Context, s *Service, id Identity, path string, query []Param) (T, error) {
	return Call(ctx, s, id, func(ctx context.Context, c *Client, t SessionToken) (T, error) {
		return Get[T](ctx, c, path, query, t)
	})
}

// ServiceSend is the service's `send`; body nil sends none.
func ServiceSend[T any](ctx context.Context, s *Service, id Identity, method, path string, body any) (T, error) {
	return Call(ctx, s, id, func(ctx context.Context, c *Client, t SessionToken) (T, error) {
		return Send[T](ctx, c, method, path, body, t)
	})
}

// SendEmpty is the service's `send_empty`.
func (s *Service) SendEmpty(ctx context.Context, id Identity, method, path string) error {
	_, err := Call(ctx, s, id, func(ctx context.Context, c *Client, t SessionToken) (struct{}, error) {
		return struct{}{}, c.SendEmpty(ctx, method, path, t)
	})
	return err
}

// Status is the state of the tab: address, sign-out, compatibility, then the viewer.
func (s *Service) Status(ctx context.Context, id Identity) UrStatus {
	address := s.address()
	if address == nil {
		return newStatus(StateNotConfigured, nil)
	}
	if s.isSignedOut(id.account()) {
		return newStatus(StateSignedOut, address)
	}
	me, err := ServiceGet[ViewerResponse](ctx, s, id, "/api/auth/me", nil)
	if err != nil {
		return failedStatus(address, err)
	}
	status := newStatus(StateReady, address)
	status.Viewer = &me.Viewer
	s.checkedMu.Lock()
	if s.checked != nil {
		v := s.checked.version.APIVersion
		status.APIVersion = &v
	}
	s.checkedMu.Unlock()
	return status
}

// SetAddress saves a new address; every cached check and refusal is dropped.
func (s *Service) SetAddress(input string) error {
	address, err := NormalizeAddress(input)
	if err != nil {
		return err
	}
	if err := s.updateSettings(func(st *settings) { st.Address = &address }); err != nil {
		return err
	}
	s.checkedMu.Lock()
	s.checked = nil
	s.checkedMu.Unlock()
	s.rejectedMu.Lock()
	clear(s.rejected)
	s.rejectedMu.Unlock()
	s.blockedMu.Lock()
	s.blockedUntil = time.Time{}
	s.blockedMu.Unlock()
	return nil
}

// SignIn is "Se connecter": allows the exchange again for this account.
func (s *Service) SignIn(id Identity) error {
	key := id.account()
	if err := s.updateSettings(func(st *settings) {
		st.SignedOut = slices.DeleteFunc(st.SignedOut, func(a string) bool { return a == key })
	}); err != nil {
		return err
	}
	s.rejectedMu.Lock()
	delete(s.rejected, key)
	s.rejectedMu.Unlock()
	return nil
}

// SignOut is "Se déconnecter": persistent flag, token deleted, then revoked on the
// server at best (an unreachable server never keeps the token on disk).
func (s *Service) SignOut(ctx context.Context, id Identity) error {
	key := id.account()
	token, err := func() (*SessionToken, error) {
		s.exchange.Lock()
		defer s.exchange.Unlock()
		if err := s.updateSettings(func(st *settings) {
			if !slices.Contains(st.SignedOut, key) {
				st.SignedOut = append(st.SignedOut, key)
			}
		}); err != nil {
			return nil, err
		}
		token, _ := s.load(key)
		if err := s.deleteToken(key); err != nil {
			return nil, err
		}
		return token, nil
	}()
	if err != nil {
		return err
	}
	if r := s.revocation(token); r != nil {
		r.Send(ctx)
	}
	return nil
}

// RevocationFor is the revocation of the stored session of an Emby account about to
// be signed out or forgotten (the Emby service deletes the token itself).
func (s *Service) RevocationFor(serverID, userID string) *Revocation {
	token, _ := s.load(Account(serverID, userID))
	return s.revocation(token)
}

func (s *Service) revocation(token *SessionToken) *Revocation {
	if token == nil {
		return nil
	}
	address := s.address()
	if address == nil {
		return nil
	}
	return &Revocation{client: NewClient(*address, s.version), token: *token}
}

// Image is the artwork for the `requestimg` protocol: from the disk cache, else fetched
// with the Bearer (same 401 handling as any call) and kept. It returns the content
// type and the bytes.
func (s *Service) Image(ctx context.Context, id Identity, req UrImage) (string, []byte, error) {
	if s.address() == nil {
		return "", nil, ErrNotConfigured
	}
	if s.isSignedOut(id.account()) {
		return "", nil, ErrSignedOut
	}
	key := req.CacheKey()
	if s.artwork != nil {
		if ct, data, ok := s.artwork.Get(key); ok {
			return ct, data, nil
		}
	}
	type image struct {
		data        []byte
		contentType string
	}
	got, err := Call(ctx, s, id, func(ctx context.Context, c *Client, t SessionToken) (image, error) {
		data, ct, err := c.Image(ctx, req.Size, req.File, t)
		return image{data, ct}, err
	})
	if err != nil {
		return "", nil, err
	}
	if s.artwork != nil {
		if _, err := s.artwork.Put(key, got.data); err != nil {
			slog.Debug("Request artwork not cached", "error", err)
		}
	}
	return got.contentType, got.data, nil
}

func asError(err error, out *RequestError) bool { return errors.As(err, out) }
