package stremio

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"slices"
	"strings"
	"sync"

	"viewer/internal/emby"
	"viewer/internal/redact"
)

// Account is the vault account of the Stremio session: one account per install.
const Account = "stremio"

// State is the account state shown in Réglages › Stremio.
type State string

const (
	StateSignedOut      State = "signed_out"
	StateSignedIn       State = "signed_in"
	StateReauthRequired State = "reauth_required"
	StateUnreachable    State = "unreachable"
)

// AddonInfo is what the UI sees of an addon that serves streams: its name and the
// content types it serves. Never its address.
type AddonInfo struct {
	Name   string `json:"name"`
	Movie  bool   `json:"movie"`
	Series bool   `json:"series"`
}

// Status is the account state and, when signed in, its stream addons in
// collection order.
type Status struct {
	State  State       `json:"state"`
	Addons []AddonInfo `json:"addons"`
}

// probeID is any IMDb id: an addon serves streams when it accepts this one.
const probeID = "tt0000001"

// Service keeps the authKey in the OS vault and the addon collection in memory
// only (the addresses carry debrid keys): it is fetched once per session and again
// after a sign-in. A refused session turns into StateReauthRequired until the next
// sign-in or sign-out; the password is never kept, so there is no silent re-login.
type Service struct {
	store  emby.CredentialStore
	client *Client

	// fetch lets one collection request run at a time; the others wait for it.
	fetch sync.Mutex

	mu sync.Mutex
	// st, and the collection inside it, are behind pointers: fmt cannot call
	// Addon.Format on an unexported field, so a Service printed by value would show
	// each addon's base; a pointer prints as an address (under a bad verb such as
	// %s, fmt expands one level only).
	st *cacheState
}

// cacheState is what the Service remembers in memory.
type cacheState struct {
	// gen changes on each sign-in and sign-out: an answer fetched for an older
	// generation is dropped.
	gen      uint64
	addons   *[]Addon // nil: not fetched
	rejected bool
}

// NewService builds the service over the vault and the client.
func NewService(store emby.CredentialStore, client *Client) *Service {
	return &Service{store: store, client: client, st: &cacheState{}}
}

func (s *Service) String() string             { return redact.Masked("StremioService") }
func (s *Service) GoString() string           { return s.String() }
func (s *Service) Format(f fmt.State, _ rune) { redact.WriteMasked(f, "StremioService") }

// reset forgets the collection and the refusal, and starts a new generation.
func (s *Service) reset() {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.st.gen++
	s.st.addons = nil
	s.st.rejected = false
}

// collection is the account's addons: from memory, or fetched with the stored key.
// ErrSignedOut without a key, ErrReauthRequired once the session was refused, the
// client's error otherwise (nothing is remembered: the next call asks again), or
// the vault's error.
func (s *Service) collection(ctx context.Context) ([]Addon, error) {
	s.fetch.Lock()
	defer s.fetch.Unlock()
	for {
		s.mu.Lock()
		gen, cached, rejected := s.st.gen, s.st.addons, s.st.rejected
		s.mu.Unlock()
		tok, err := s.store.Load(Account)
		if err != nil {
			return nil, err
		}
		if tok == nil {
			return nil, ErrSignedOut
		}
		if rejected {
			return nil, ErrReauthRequired
		}
		if cached != nil {
			return *cached, nil
		}
		addons, err := s.client.Addons(ctx, NewAuthKey(tok.Expose()))
		s.mu.Lock()
		if s.st.gen != gen {
			// Signed in or out meanwhile: this answer belongs to another key.
			s.mu.Unlock()
			continue
		}
		switch {
		case err == nil:
			s.st.addons = &addons
		case kindOf(err) == KindRejected:
			s.st.rejected = true
			err = ErrReauthRequired
		}
		s.mu.Unlock()
		if err != nil {
			return nil, err
		}
		return addons, nil
	}
}

// Status is the account state; signed in, it lists the addons serving streams.
func (s *Service) Status(ctx context.Context) Status {
	st := Status{State: StateSignedOut, Addons: []AddonInfo{}}
	addons, err := s.collection(ctx)
	switch {
	case err == nil:
		st.State = StateSignedIn
		for _, a := range addons {
			info := AddonInfo{Name: a.Name, Movie: a.Serves(Movie, probeID), Series: a.Serves(Series, probeID)}
			if info.Movie || info.Series {
				st.Addons = append(st.Addons, info)
			}
		}
	case errors.Is(err, ErrSignedOut):
	case errors.Is(err, ErrReauthRequired):
		st.State = StateReauthRequired
	case kindOf(err) != "":
		slog.Warn("Stremio addon collection unavailable", "kind", string(kindOf(err)))
		st.State = StateUnreachable
	default:
		// The vault cannot be read: shown signed out; a sign-in reports the error.
		slog.Warn("Stremio credential store unavailable")
	}
	return st
}

// Addons is the account's whole collection (tranche 2 filters it with Serves):
// ErrSignedOut, ErrReauthRequired or the failure of the request.
func (s *Service) Addons(ctx context.Context) ([]Addon, error) {
	addons, err := s.collection(ctx)
	if err != nil {
		return nil, err
	}
	return slices.Clone(addons), nil
}

// SignIn logs in with the e-mail (trimmed) and password, stores the authKey in the
// vault and forgets the previous collection. The password is used once.
func (s *Service) SignIn(ctx context.Context, email, password string) error {
	email = strings.TrimSpace(email)
	if email == "" || password == "" {
		return Error{Kind: KindInvalid}
	}
	key, err := s.client.Login(ctx, email, password)
	if err != nil {
		slog.Info("Stremio sign-in failed", "kind", string(kindOf(err)))
		return err
	}
	if err := s.store.Save(Account, emby.NewAccessToken(key.value)); err != nil {
		return err
	}
	s.reset()
	slog.Info("Stremio signed in")
	return nil
}

// SignOut forgets the authKey and the collection. It is local only: the session
// stays usable in the Stremio apps.
func (s *Service) SignOut() error {
	err := s.store.Delete(Account)
	s.reset()
	if err == nil {
		slog.Info("Stremio signed out")
	}
	return err
}

// kindOf is the kind of an Error, "" for any other error.
func kindOf(err error) ErrorKind {
	var e Error
	if errors.As(err, &e) {
		return e.Kind
	}
	return ""
}
