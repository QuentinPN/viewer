package libraryevents

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"errors"
	"log/slog"
	"net"
	"net/http"
	"net/url"
	"time"

	"github.com/coder/websocket"

	"viewer/internal/devsync"
	"viewer/internal/emby"
)

// Sync is the part of the linked playback service the socket feeds
// (devsync.Service).
type Sync interface {
	Incoming(raw string)
	OwnSession(id *string)
}

// Supervisor is `spawn` / `supervise`: one socket per signed-in session, for
// the app's lifetime. Build it with its fields, then call Run.
type Supervisor struct {
	Session devsync.Session
	Sync    Sync
	Events  *Events
	// Emit sends LibraryChangedEvent to the UI (payload nil, JSON null).
	Emit func(name string, payload any)
	// Log receives the socket's state lines; nil is slog.Default(). Never the URL.
	Log *slog.Logger
	// Recover is deferred first in each goroutine (diagnostics RecoverAndRepanic:
	// the panic reaches the log, then the crash goes on); nil does nothing.
	Recover func()
	// dial is websocket.Dial; tests replace it.
	dial func(ctx context.Context, u string) (*websocket.Conn, *http.Response, error)
	// sleep waits d or until ctx ends; tests shorten it.
	sleep func(ctx context.Context, d time.Duration) bool
}

// noRedirects is the dial's HTTP client: the socket URL carries the api_key, which
// a redirect would forward to another host (same policy as emby.Client).
var noRedirects = &http.Client{
	CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
}

func (s *Supervisor) logger() *slog.Logger {
	if s.Log != nil {
		return s.Log
	}
	return slog.Default()
}

func (s *Supervisor) wait(ctx context.Context, d time.Duration) bool {
	if s.sleep != nil {
		return s.sleep(ctx, d)
	}
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-t.C:
		return true
	case <-ctx.Done():
		return false
	}
}

// sessionURL is `session_url`: the open session's client and socket URL, false
// when signed out.
func (s *Supervisor) sessionURL() (*emby.Client, string, bool) {
	client, err := s.Session.Client()
	if err != nil || client == nil {
		return nil, "", false
	}
	u, err := client.LibrarySocketURL()
	if err != nil {
		return nil, "", false
	}
	return client, u, true
}

// Run is `supervise`: it returns when ctx ends (Rust runs until the process exits).
func (s *Supervisor) Run(ctx context.Context) {
	if s.Recover == nil {
		s.Recover = func() {}
	}
	var attempt uint32
	last := ""
	for ctx.Err() == nil {
		client, u, ok := s.sessionURL()
		if !ok {
			last = ""
			if !s.wait(ctx, sessionCheck) {
				return
			}
			continue
		}
		if last != u {
			attempt = 0
			s.Sync.OwnSession(nil)
			last = u
		}
		dial := s.dial
		if dial == nil {
			dial = func(ctx context.Context, u string) (*websocket.Conn, *http.Response, error) {
				return websocket.Dial(ctx, u, &websocket.DialOptions{HTTPClient: noRedirects})
			}
		}
		conn, resp, err := dial(ctx, u)
		if err == nil {
			s.logger().Info("session socket: connected")
			attempt = 0
			go func() {
				defer s.Recover()
				s.onConnect(ctx, client)
			}()
			s.listen(ctx, conn, u)
			s.logger().Info("session socket: disconnected")
		} else {
			if ctx.Err() != nil {
				return
			}
			// The error text holds the URL (and so the token): only its kind is logged.
			s.logger().Warn("session socket: connection failed (" + errorKind(err, resp) + ")")
		}
		if resp != nil && resp.Body != nil {
			_ = resp.Body.Close()
		}
		// A new session reconnects at once; the same one backs off.
		if _, now, ok := s.sessionURL(); ok && now == u {
			if !s.wait(ctx, Backoff(attempt)) {
				return
			}
			if attempt < ^uint32(0) {
				attempt++
			}
		}
	}
}

// onConnect is `on_connect`: makes the session reachable by `SendString`, then
// learns its id.
func (s *Supervisor) onConnect(ctx context.Context, client *emby.Client) {
	if err := client.PostCapabilities(ctx); err != nil {
		s.logger().Warn("session socket: capabilities refused (" + devsync.KindText(err) + ")")
		return
	}
	user := s.Session.CurrentUser()
	if user == nil {
		return
	}
	list, err := client.Sessions(ctx, user.UserID)
	if err != nil {
		s.logger().Warn("session socket: sessions unavailable (" + devsync.KindText(err) + ")")
		return
	}
	id, ok := devsync.OwnSessionID(list, client.DeviceID())
	if !ok {
		s.logger().Warn("session socket: own session not listed")
		s.Sync.OwnSession(nil)
		return
	}
	s.Sync.OwnSession(&id)
}

// errorKind is `error_kind`: enough to diagnose, never the URL.
func errorKind(err error, resp *http.Response) string {
	if resp != nil {
		if resp.StatusCode == http.StatusUnauthorized {
			return "jeton refusé"
		}
		return "réponse HTTP"
	}
	var (
		urlErr     *url.Error
		verifyErr  *tls.CertificateVerificationError
		unknownCA  x509.UnknownAuthorityError
		hostErr    x509.HostnameError
		certErr    x509.CertificateInvalidError
		recordErr  tls.RecordHeaderError
		alertErr   tls.AlertError
		netErr     net.Error
		parseFails = func() bool {
			// A malformed URL fails before any connection attempt.
			return errors.As(err, &urlErr) && urlErr.Op == "parse"
		}
	)
	switch {
	case parseFails():
		return "adresse"
	case errors.As(err, &verifyErr), errors.As(err, &unknownCA), errors.As(err, &hostErr),
		errors.As(err, &certErr), errors.As(err, &recordErr), errors.As(err, &alertErr):
		return "TLS"
	case errors.As(err, &netErr):
		return "réseau"
	default:
		return "protocole"
	}
}

// listen reads frames until the socket closes, the session changes or ctx ends.
func (s *Supervisor) listen(ctx context.Context, conn *websocket.Conn, u string) {
	conn.SetReadLimit(maxMessage)
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()

	type frame struct {
		text string
		ok   bool
	}
	frames := make(chan frame)
	go func() {
		defer s.Recover()
		for {
			typ, data, err := conn.Read(ctx)
			if err != nil {
				select {
				case frames <- frame{}:
				case <-ctx.Done():
				}
				return
			}
			if typ != websocket.MessageText {
				continue
			}
			select {
			case frames <- frame{text: string(data), ok: true}:
			case <-ctx.Done():
				return
			}
		}
	}()

	keepAlive := time.NewTicker(KeepAlive)
	defer keepAlive.Stop()
	check := time.NewTicker(sessionCheck)
	defer check.Stop()
	var due <-chan time.Time
	var settle *time.Timer
	defer func() {
		if settle != nil {
			settle.Stop()
		}
	}()
	sendAlive := func() bool {
		return conn.Write(ctx, websocket.MessageText, []byte(`{"MessageType":"KeepAlive"}`)) == nil
	}
	// tokio's interval ticks at once: the first keep-alive goes out on connect.
	alive := sendAlive()
	closing := false
	for alive {
		select {
		case f := <-frames:
			if !f.ok {
				alive = false
			} else if sync, ok := SendStringOf(f.text); ok {
				s.Sync.Incoming(sync)
			} else if IsLibraryChange(f.text) && s.Events.HomeShown() {
				if settle == nil {
					settle = time.NewTimer(Settle)
				} else {
					settle.Reset(Settle)
				}
				due = settle.C
			}
		case <-keepAlive.C:
			alive = sendAlive()
		case <-check.C:
			// Signed out or another session: this socket is no longer ours.
			if _, now, ok := s.sessionURL(); !ok || now != u {
				// Close frame sent off this loop: a new session reconnects at once.
				go func() {
					defer s.Recover()
					_ = conn.Close(websocket.StatusNormalClosure, "")
				}()
				closing = true
				alive = false
			}
		case <-due:
			due = nil
			s.Emit(LibraryChangedEvent, nil)
		case <-ctx.Done():
			alive = false
		}
	}
	if !closing {
		cancel()
		_ = conn.CloseNow()
	}
	// A change seen just before the socket dropped is still announced.
	if due != nil {
		s.Emit(LibraryChangedEvent, nil)
	}
}
