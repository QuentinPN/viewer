package devsync

// IO glue of the leader (ADR 0124), port of src-tauri/src/sync/service.rs: one
// goroutine owns the state machine, reads the player, carries out its effects.
// Envelopes go out in order through a second goroutine, so a slow server never
// delays a `pong`. Only kind, seq and reject reasons are logged, never an envelope.

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"log/slog"
	"math"
	"sync"
	"time"

	"viewer/internal/emby"
	"viewer/internal/player"
)

const (
	// StatusEvent carries Status after each change.
	StatusEvent = "sync://status"
	// NoticeEvent carries a Notice the UI shows once.
	NoticeEvent = "sync://notice"
	// ActionEvent carries a UIAction the player view carries out (next, previous, stop).
	ActionEvent = "sync://action"

	// tick: timers of the state machine are checked this often.
	tick = 250 * time.Millisecond
)

// Player is what the glue reads from and drives on the engine (MpvEngine).
type Player interface {
	PositionSeconds() (float64, bool)
	SelectedStreams() (audio, subtitle *int32)
	Command(command player.PlayerCommand) error
}

// Session is the part of EmbyService the glue uses: the open session's client
// and user.
type Session interface {
	Client() (*emby.Client, error)
	CurrentUser() *emby.SignedInUser
}

// Devices is `devices`: the TVs of the open session, read from the server.
func Devices(ctx context.Context, s Session) (emby.List[SyncDevice], error) {
	client, err := s.Client()
	if err != nil {
		return nil, err
	}
	user := s.CurrentUser()
	if user == nil {
		return nil, emby.ErrNotSignedIn
	}
	sessions, err := client.Sessions(ctx, user.UserID)
	if err != nil {
		return nil, err
	}
	return TVDevices(sessions, client.DeviceID()), nil
}

// ServiceConfig are the dependencies of a Service.
type ServiceConfig struct {
	Session Session
	// Engine returns the player without ever waiting (Rust `try_lock`: a busy
	// player skips one read); nil when nothing plays.
	Engine func() Player
	// Emit sends the three sync events to the UI.
	Emit func(name string, payload any)
	// Log is the logger; nil is slog.Default().
	Log *slog.Logger
	// NewLink makes a link id; nil is a random UUID v4 in simple form.
	NewLink func() string
	// Recover is deferred first in each goroutine (diagnostics RecoverAndRepanic:
	// the panic reaches the log, then the crash goes on); nil does nothing.
	Recover func()
}

// Service is `SyncHandle`: the handle to the linked playback goroutine. Sending
// never blocks; every method may be called from any goroutine, the player's
// event sink included.
type Service struct {
	in  *mailbox[msg]
	ctx context.Context
}

type msgKind int

const (
	msgPlayer msgKind = iota
	msgPlaying
	msgIncoming
	msgJoin
	msgOwnSession
	msgInvite
	msgUnlink
	msgStop
	msgStatus
)

type msg struct {
	kind     msgKind
	event    player.PlayerEvent
	current  current
	raw      string
	env      Envelope
	name     *string
	own      *string
	peer     Peer
	reply    chan error
	ack      func()
	statusTo chan Status
}

// current is the playback `emby_play` started.
type current struct {
	itemID        string
	mediaSourceID string
	// direct: mpv's FFmpeg indexes are Emby's stream indexes.
	direct bool
}

type outKind int

const (
	outSend outKind = iota
	outFlush
)

type out struct {
	kind outKind
	to   string
	text string
	ack  func()
}

// NewService is `SyncHandle::spawn`: both goroutines run until ctx ends.
func NewService(ctx context.Context, cfg ServiceConfig) *Service {
	if cfg.Log == nil {
		cfg.Log = slog.Default()
	}
	if cfg.NewLink == nil {
		cfg.NewLink = newLinkID
	}
	if cfg.Engine == nil {
		cfg.Engine = func() Player { return nil }
	}
	if cfg.Recover == nil {
		cfg.Recover = func() {}
	}
	s := &Service{in: newMailbox[msg](), ctx: ctx}
	outbox := newMailbox[out]()
	g := &glue{cfg: cfg, start: time.Now(), out: outbox, svc: s}
	go func() {
		defer cfg.Recover()
		sendLoop(ctx, cfg, outbox)
	}()
	go func() {
		defer cfg.Recover()
		g.run(ctx)
	}()
	return s
}

// PlayerEvent feeds a player event; positions, durations and track lists are
// read from the engine when needed, so they are dropped here.
func (s *Service) PlayerEvent(event player.PlayerEvent) {
	switch event.(type) {
	case player.TimePos, player.Duration, player.Tracks:
		return
	}
	s.in.push(msg{kind: msgPlayer, event: event})
}

// Playing is called by `emby_play` before the file is loaded.
func (s *Service) Playing(itemID, mediaSourceID string, direct bool) {
	s.in.push(msg{kind: msgPlaying, current: current{itemID: itemID, mediaSourceID: mediaSourceID, direct: direct}})
}

// Incoming is the `String` of a `SendString` the socket received.
func (s *Service) Incoming(raw string) { s.in.push(msg{kind: msgIncoming, raw: raw}) }

// OwnSession sets this device's session id; nil when unknown.
func (s *Service) OwnSession(id *string) {
	s.in.push(msg{kind: msgOwnSession, own: copyStr(id)})
}

// Invite sends the current playback to peer; the outcome arrives as
// StatusEvent and NoticeEvent.
func (s *Service) Invite(ctx context.Context, peer Peer) error {
	reply := make(chan error, 1)
	if !s.in.push(msg{kind: msgInvite, peer: peer, reply: reply}) {
		return emby.NewSync("service arrêté")
	}
	select {
	case err := <-reply:
		return err
	case <-ctx.Done():
		return emby.ErrCancelled
	case <-s.ctx.Done():
		return emby.NewSync("service arrêté")
	}
}

// Unlink ends the link; the playback goes on here.
func (s *Service) Unlink() { s.in.push(msg{kind: msgUnlink}) }

// Status is the link's status; Idle once the service is stopped.
func (s *Service) Status(ctx context.Context) Status {
	reply := make(chan Status, 1)
	if !s.in.push(msg{kind: msgStatus, statusTo: reply}) {
		return Status{Phase: PhaseIdle}
	}
	select {
	case st := <-reply:
		return st
	case <-ctx.Done():
	case <-s.ctx.Done():
	}
	return Status{Phase: PhaseIdle}
}

// Stop ends a link with `bye stopped` and waits, at most timeout, until it was
// sent (sign-out, server change, app close). Reports whether it was.
func (s *Service) Stop(timeout time.Duration) bool {
	acked := make(chan struct{})
	var once sync.Once
	if !s.in.push(msg{kind: msgStop, ack: func() { once.Do(func() { close(acked) }) }}) {
		return false
	}
	t := time.NewTimer(timeout)
	defer t.Stop()
	select {
	case <-acked:
		return true
	case <-t.C:
		return false
	}
}

// StopBlocking is `stop_blocking`; Go has a single Stop.
func (s *Service) StopBlocking(timeout time.Duration) bool { return s.Stop(timeout) }

// newLinkID is `uuid::Uuid::new_v4().simple()`: 32 lowercase hex digits.
func newLinkID() string {
	var b [16]byte
	_, _ = rand.Read(b[:]) // crypto/rand.Read never fails (Go 1.24+)
	b[6] = b[6]&0x0f | 0x40
	b[8] = b[8]&0x3f | 0x80
	return hex.EncodeToString(b[:])
}

type glue struct {
	cfg     ServiceConfig
	svc     *Service
	leader  Leader
	start   time.Time
	seq     uint32
	own     *string
	current *current
	loaded  bool
	paused  bool
	buffer  bool
	out     *mailbox[out]
}

func (g *glue) run(ctx context.Context) {
	ticker := time.NewTicker(tick)
	defer ticker.Stop()
	defer g.svc.in.close()
	defer g.out.close()
	for {
		select {
		case <-ctx.Done():
			return
		case <-g.svc.in.ready():
			for _, m := range g.svc.in.drain() {
				g.on(ctx, m)
			}
		case <-ticker.C:
			// A Go ticker drops missed ticks, like MissedTickBehavior::Skip.
			if g.leader.IsActive() {
				g.handle(Tick{})
			}
		}
	}
}

func (g *glue) now() Ms { return Ms(time.Since(g.start).Milliseconds()) }

// snapshot is the local playback with mpv's position now, nil when nothing plays.
func (g *glue) snapshot() *Snapshot {
	if g.current == nil || !g.loaded {
		return nil
	}
	engine := g.cfg.Engine()
	if engine == nil {
		return nil
	}
	seconds, ok := engine.PositionSeconds()
	if !ok {
		return nil
	}
	var audio, subtitle *int32
	if g.current.direct {
		audio, subtitle = engine.SelectedStreams()
	}
	// A remux or transcode numbers its streams otherwise: the TV keeps its own.
	ms := math.Round(seconds * 1000)
	var pos uint32
	switch {
	case math.IsNaN(ms) || ms <= 0:
		pos = 0 // Rust's `as u32` saturates, and NaN is 0
	case ms >= float64(MaxPositionMs):
		pos = MaxPositionMs
	default:
		pos = uint32(ms)
	}
	source := g.current.mediaSourceID
	return &Snapshot{
		ItemID:              g.current.itemID,
		MediaSourceID:       &source,
		PositionMs:          pos,
		Paused:              g.paused,
		Buffering:           g.buffer,
		AudioStreamIndex:    audio,
		SubtitleStreamIndex: subtitle,
	}
}

// handle runs one input; the player is only read when a link may need it.
func (g *glue) handle(input Input) {
	needs := g.leader.IsActive()
	switch input.(type) {
	case Invite, Received:
		needs = true
	}
	var snap *Snapshot
	if needs {
		snap = g.snapshot()
	}
	g.apply(g.leader.Handle(g.now(), snap, input))
}

func (g *glue) on(ctx context.Context, m msg) {
	switch m.kind {
	case msgPlayer:
		switch ev := m.event.(type) {
		case player.FileLoaded:
			g.loaded, g.paused, g.buffer = true, false, false
			g.handle(FileLoaded{})
		case player.Paused:
			resumed := g.paused && !ev.Paused
			g.paused = ev.Paused
			g.handle(LocalChanged{Resumed: resumed})
		case player.Buffering:
			g.buffer = ev.Active
			g.handle(LocalChanged{Resumed: false})
		case player.Seeked:
			g.handle(LocalChanged{Resumed: false})
		case player.EndFile:
			g.loaded = false
		case player.Shutdown:
			g.loaded = false
			g.current = nil
			g.handle(LocalStopped{})
		}
	case msgPlaying:
		c := m.current
		g.current = &c
		g.loaded = false
	case msgIncoming:
		env, err := Decode(m.raw)
		if err != nil {
			code := "json"
			var r Reject
			if errors.As(err, &r) {
				code = r.Code()
			}
			g.cfg.Log.Debug("sync: message rejected (" + code + ")")
			return
		}
		g.cfg.Log.Debug("sync: received " + Label(env.Body) + " #" + itoa(env.Seq))
		// A link opens on a listed TV only; its name comes from the server, read
		// off this goroutine so timers and player events never wait on it.
		if _, join := env.Body.(Join); join && !g.leader.IsActive() {
			go func() {
				defer g.cfg.Recover()
				name := deviceName(ctx, g.cfg, env.From)
				g.svc.in.push(msg{kind: msgJoin, env: env, name: name})
			}()
			return
		}
		g.handle(Received{Env: env})
	case msgJoin:
		g.handle(Received{Env: m.env, PeerName: m.name})
	case msgOwnSession:
		// Another account or server: a link of the old session ends here
		// (best-effort `bye stopped`, still sent as the old session).
		if !sameStr(m.own, g.own) && g.leader.IsActive() {
			g.handle(LocalStopped{})
		}
		g.own = m.own
	case msgInvite:
		var err error
		switch {
		case g.own == nil:
			err = emby.NewSync("session Emby pas encore identifiée, réessaie dans un instant")
		case g.leader.IsActive():
			err = emby.NewSync("une TV suit déjà cette lecture")
		case g.snapshot() == nil:
			err = emby.NewSync("aucune lecture en cours")
		default:
			g.handle(Invite{Peer: m.peer, Link: g.cfg.NewLink()})
		}
		m.reply <- err
	case msgUnlink:
		g.handle(Unlink{})
	case msgStop:
		g.handle(LocalStopped{})
		g.out.push(out{kind: outFlush, ack: m.ack})
	case msgStatus:
		m.statusTo <- g.leader.Status()
	}
}

func (g *glue) apply(fx []Effect) {
	for _, effect := range fx {
		switch e := effect.(type) {
		case Send:
			if g.own == nil {
				g.cfg.Log.Warn("sync: " + e.Body.Kind() + " not sent, own session unknown")
				continue
			}
			env := Envelope{From: *g.own, Link: e.Link, Seq: g.seq, Body: e.Body}
			g.cfg.Log.Debug("sync: send " + Label(env.Body) + " #" + itoa(env.Seq))
			g.seq = min(g.seq+1, uint32(maxSeq))
			g.out.push(out{kind: outSend, to: e.To, text: env.Encode()})
		case PlayerAction:
			var command player.PlayerCommand
			switch e.Kind {
			case PlayerPause:
				command = player.PlayerCommand{Type: player.CmdSetPaused, Paused: true}
			case PlayerPlay:
				command = player.PlayerCommand{Type: player.CmdSetPaused, Paused: false}
			case PlayerSeek:
				command = player.PlayerCommand{Type: player.CmdSeekAbsolute, Seconds: float64(e.PositionMs) / 1000}
			}
			if engine := g.cfg.Engine(); engine != nil {
				if err := engine.Command(command); err != nil {
					g.cfg.Log.Warn("sync: player command failed: " + err.Error())
				}
			}
		case UIAction:
			g.cfg.Emit(ActionEvent, e)
		case Notice:
			g.cfg.Emit(NoticeEvent, e)
		case StatusChanged:
			g.cfg.Emit(StatusEvent, g.leader.Status())
		}
	}
}

func sendLoop(ctx context.Context, cfg ServiceConfig, box *mailbox[out]) {
	for {
		select {
		case <-ctx.Done():
			return
		case <-box.ready():
			for _, o := range box.drain() {
				switch o.kind {
				case outSend:
					client, err := cfg.Session.Client()
					if err == nil {
						err = client.SendString(ctx, o.to, o.text)
					}
					if err != nil {
						cfg.Log.Warn("sync: relay failed (" + KindText(err) + ")")
					}
				case outFlush:
					o.ack()
				}
			}
		}
	}
}

// deviceName is the name of a listed TV, nil when it is not listed.
func deviceName(ctx context.Context, cfg ServiceConfig, sessionID string) *string {
	list, err := Devices(ctx, cfg.Session)
	if err != nil {
		cfg.Log.Warn("sync: device list unavailable (" + KindText(err) + ")")
		return nil
	}
	for _, d := range list {
		if d.SessionID == sessionID {
			name := d.Name
			return &name
		}
	}
	return nil
}

// KindText is `e.kind()` for the logs: never the message.
func KindText(err error) string {
	if k, ok := emby.KindOf(err); ok {
		return string(k)
	}
	return "unknown"
}

func sameStr(a, b *string) bool {
	if a == nil || b == nil {
		return a == nil && b == nil
	}
	return *a == *b
}

func itoa(v uint32) string {
	var b [10]byte
	i := len(b)
	for {
		i--
		b[i] = byte('0' + v%10)
		v /= 10
		if v == 0 {
			return string(b[i:])
		}
	}
}

// mailbox is an unbounded queue, the Rust `mpsc::unbounded_channel`: push never
// blocks (the player's event sink must not wait), and the reader drains it.
type mailbox[T any] struct {
	mu     sync.Mutex
	items  []T
	signal chan struct{}
	closed bool
}

func newMailbox[T any]() *mailbox[T] { return &mailbox[T]{signal: make(chan struct{}, 1)} }

// push queues v; false once the reader is gone.
func (m *mailbox[T]) push(v T) bool {
	m.mu.Lock()
	if m.closed {
		m.mu.Unlock()
		return false
	}
	m.items = append(m.items, v)
	m.mu.Unlock()
	select {
	case m.signal <- struct{}{}:
	default:
	}
	return true
}

func (m *mailbox[T]) ready() <-chan struct{} { return m.signal }

func (m *mailbox[T]) drain() []T {
	m.mu.Lock()
	defer m.mu.Unlock()
	items := m.items
	m.items = nil
	return items
}

func (m *mailbox[T]) close() {
	m.mu.Lock()
	m.closed = true
	m.items = nil
	m.mu.Unlock()
}
