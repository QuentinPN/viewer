package devsync

import "encoding/json"

// Leader side of a linked playback (ADR 0124): the computer's state machine.
// Pure: time is a monotonic millisecond count given by the caller, and every
// outcome is an Effect the IO glue carries out.

// Ms is a monotonic millisecond count.
type Ms = uint64

const (
	// ReplyTimeout is the silence of the peer after which a request is given up.
	ReplyTimeout Ms = 10_000
	// ReadyTimeout: a `load` the TV answered (it pings) leaves it this long to be `ready`.
	ReadyTimeout Ms = 30_000
	// Silence: no `ping` for this long, the link is lost.
	Silence Ms = 5_000
	// BufferingLimit: the TV buffering for this long, the computer plays on alone.
	BufferingLimit Ms = 15_000
	// StateEvery: `state` goes out at least this often.
	StateEvery Ms = 1_000
)

// Snapshot is the local playback, read from the player when an input is handled.
type Snapshot struct {
	ItemID              string
	MediaSourceID       *string
	PositionMs          uint32
	Paused              bool
	Buffering           bool
	AudioStreamIndex    *int32
	SubtitleStreamIndex *int32
}

// Peer is a TV of the server's session list; the name never comes from a message.
type Peer struct {
	SessionID string
	Name      string
}

// Input is what the leader handles: one of Invite, Received, Tick, LocalChanged,
// FileLoaded, LocalStopped and Unlink.
type Input interface{ isInput() }

// Invite is « Envelope aussi sur la TV » with a new random link id.
type Invite struct {
	Peer Peer
	Link string
}

// Received is a valid envelope; PeerName is set when `from` is a listed TV.
type Received struct {
	Env      Envelope
	PeerName *string
}

// Tick is the periodic clock.
type Tick struct{}

// LocalChanged: pause, resume, seek or buffering changed locally. Resumed:
// playback went from paused to playing.
type LocalChanged struct {
	Resumed bool
}

// FileLoaded: a file finished loading (new episode, quality change…).
type FileLoaded struct{}

// LocalStopped: the local playback stopped (Retour, close, sign-out).
type LocalStopped struct{}

// Unlink is « Délier » or « Continuer sans la TV ».
type Unlink struct{}

func (Invite) isInput()       {}
func (Received) isInput()     {}
func (Tick) isInput()         {}
func (LocalChanged) isInput() {}
func (FileLoaded) isInput()   {}
func (LocalStopped) isInput() {}
func (Unlink) isInput()       {}

// Effect is an outcome the glue carries out: one of Send, PlayerAction,
// UIAction, Notice and StatusChanged (the variants of the Rust enum `Effect`).
type Effect interface{ isEffect() }

// Send is an envelope to send; the glue adds `from` and `seq`.
type Send struct {
	To   string
	Link string
	Body Body
}

// PlayerActionKind is what the local player does.
type PlayerActionKind int

// The player actions.
const (
	PlayerPause PlayerActionKind = iota
	PlayerPlay
	PlayerSeek
)

// PlayerAction is done on the local player. PositionMs is only set for PlayerSeek.
type PlayerAction struct {
	Kind       PlayerActionKind
	PositionMs uint32
}

// UIAction is done by the UI with its own logic, as if the user asked. It is
// the payload of `sync://action`, a bare string.
type UIAction string

// The UI actions.
const (
	UINext     UIAction = "next"
	UIPrevious UIAction = "previous"
	UIStop     UIAction = "stop"
)

// NoticeKind is the kind of a Notice.
type NoticeKind string

// The notices.
const (
	// NoticeFollowing: « <appareil> suit ta lecture. »
	NoticeFollowing NoticeKind = "following"
	// NoticeLost: « Lien perdu avec <appareil>. La lecture continue ici. »
	NoticeLost NoticeKind = "lost"
	// NoticeNoAnswer: « <appareil> ne répond pas. »
	NoticeNoAnswer NoticeKind = "noAnswer"
	// NoticeBusy: « <appareil> suit déjà une autre lecture. »
	NoticeBusy NoticeKind = "busy"
)

// Notice is the payload of `sync://notice`: `{kind, device}`.
type Notice struct {
	Kind   NoticeKind `json:"kind"`
	Device string     `json:"device"`
}

// StatusChanged says Leader.Status changed.
type StatusChanged struct{}

func (Send) isEffect()          {}
func (PlayerAction) isEffect()  {}
func (UIAction) isEffect()      {}
func (Notice) isEffect()        {}
func (StatusChanged) isEffect() {}

// Phase is the phase of a Status.
type Phase string

// The phases.
const (
	PhaseIdle Phase = "idle"
	// PhaseWaiting: « En attente de la TV… », loading or buffering on the TV.
	PhaseWaiting Phase = "waiting"
	PhaseLinked  Phase = "linked"
)

// Status is the payload of `sync://status` and the result of `sync_status`.
// Device is empty when idle; DriftMs is only read when linked.
type Status struct {
	Phase   Phase
	Device  string
	DriftMs *int64
}

// MarshalJSON writes the serde shape `#[serde(tag = "phase")]`: `{phase}` when
// idle, `{phase, device}` when waiting, `{phase, device, driftMs}` when linked,
// with driftMs null when unknown.
func (s Status) MarshalJSON() ([]byte, error) {
	switch s.Phase {
	case PhaseWaiting:
		return json.Marshal(struct {
			Phase  Phase  `json:"phase"`
			Device string `json:"device"`
		}{s.Phase, s.Device})
	case PhaseLinked:
		return json.Marshal(struct {
			Phase   Phase  `json:"phase"`
			Device  string `json:"device"`
			DriftMs *int64 `json:"driftMs"`
		}{s.Phase, s.Device, s.DriftMs})
	default:
		return json.Marshal(struct {
			Phase Phase `json:"phase"`
		}{PhaseIdle})
	}
}

// Leader is the computer's side of a linked playback. The zero value is idle.
type Leader struct {
	link *link
}

// phase of a link: loading (`load` sent, waiting for `ready`) or linked.
type phase struct {
	loading bool
	since   Ms
	// heard: the TV answered meanwhile.
	heard bool
	first bool
}

type link struct {
	peer      Peer
	id        string
	lastSeq   *uint32
	itemID    string
	phase     phase
	lastHeard Ms
	lastState Ms
	driftMs   *int64
	// hold: the playback was paused for the link and resumes when it is done waiting.
	hold             bool
	tvBufferingSince *Ms
	// announced: the « suit ta lecture » notice was already shown (TV-started links).
	announced bool
}

// elapsed is Rust's `now.saturating_sub(since)`.
func elapsed(now, since Ms) Ms {
	if now < since {
		return 0
	}
	return now - since
}

// Status is the leader's status.
func (ld *Leader) Status() Status {
	l := ld.link
	switch {
	case l == nil:
		return Status{Phase: PhaseIdle}
	case l.phase.loading || l.tvBufferingSince != nil:
		return Status{Phase: PhaseWaiting, Device: l.peer.Name}
	default:
		return Status{Phase: PhaseLinked, Device: l.peer.Name, DriftMs: copyI64(l.driftMs)}
	}
}

// IsActive reports whether a link is open or opening.
func (ld *Leader) IsActive() bool { return ld.link != nil }

// Handle handles one input at now; snap is the local playback, nil when
// nothing plays. No effect is a nil slice.
func (ld *Leader) Handle(now Ms, snap *Snapshot, input Input) []Effect {
	switch in := input.(type) {
	case Invite:
		if snap != nil && ld.link == nil {
			return ld.start(now, snap, in.Peer, in.Link, false)
		}
		return nil
	case Received:
		return ld.received(now, snap, in.Env, in.PeerName)
	case Tick:
		return ld.tick(now, snap)
	case LocalChanged:
		l := ld.link
		if l == nil {
			return nil
		}
		var fx []Effect
		if in.Resumed && l.hold {
			// The user played on: nothing resumes later, nothing waits.
			l.hold = false
			if l.tvBufferingSince != nil {
				l.tvBufferingSince = nil
				fx = append(fx, StatusChanged{})
			}
		}
		return append(fx, ld.stateNow(now, snap)...)
	case FileLoaded:
		l := ld.link
		if l == nil || snap == nil {
			return nil
		}
		if l.itemID == snap.ItemID {
			return ld.stateNow(now, snap)
		}
		// Next episode: a new `load` on the same link; the TV already accepted it.
		l.itemID = snap.ItemID
		l.phase = phase{loading: true, since: now, heard: true, first: false}
		l.tvBufferingSince = nil
		var fx []Effect
		if !snap.Paused {
			l.hold = true
			fx = append(fx, PlayerAction{Kind: PlayerPause})
		}
		return append(fx, send(l, loadOf(snap)), StatusChanged{})
	case LocalStopped:
		return ld.end(ByeStopped, nil, true)
	case Unlink:
		return ld.end(ByeLeft, nil, true)
	}
	return nil
}

func (ld *Leader) start(now Ms, s *Snapshot, p Peer, id string, joined bool) []Effect {
	var fx []Effect
	if joined {
		fx = append(fx, Notice{Kind: NoticeFollowing, Device: p.Name})
	}
	l := &link{
		peer:      p,
		id:        id,
		itemID:    s.ItemID,
		phase:     phase{loading: true, since: now, heard: joined, first: true},
		lastHeard: now,
		lastState: now,
		hold:      !s.Paused,
		announced: joined,
	}
	if l.hold {
		fx = append(fx, PlayerAction{Kind: PlayerPause})
	}
	fx = append(fx, send(l, loadOf(s)), StatusChanged{})
	ld.link = l
	return fx
}

func (ld *Leader) received(now Ms, snap *Snapshot, env Envelope, peerName *string) []Effect {
	var view *LinkView
	if l := ld.link; l != nil {
		view = &LinkView{Peer: l.peer.SessionID, Link: l.id, LastSeq: l.lastSeq}
	}
	switch Accepts(view, env.From, env.Link, env.Seq, env.Body.Kind()) {
	case AcceptNo:
		return nil
	case AcceptBusy:
		return []Effect{Send{To: env.From, Link: env.Link, Body: Bye{Reason: ByeBusy}}}
	}
	l := ld.link
	if l == nil {
		// Outside a link only a listed TV's `join` is read.
		if _, isJoin := env.Body.(Join); !isJoin || peerName == nil {
			return nil
		}
		if snap == nil {
			return []Effect{Send{To: env.From, Link: env.Link, Body: Bye{Reason: ByeIdle}}}
		}
		fx := ld.start(now, snap, Peer{SessionID: env.From, Name: *peerName}, env.Link, true)
		seq := env.Seq
		ld.link.lastSeq = &seq
		return fx
	}
	seq := env.Seq
	l.lastSeq = &seq
	l.lastHeard = now
	if l.phase.loading {
		l.phase.heard = true
	}
	linked := !l.phase.loading
	switch b := env.Body.(type) {
	case Ping:
		fx := []Effect{send(l, Pong{T: b.T})}
		if b.DriftMs != nil && (l.driftMs == nil || *l.driftMs != *b.DriftMs) {
			l.driftMs = copyI64(b.DriftMs)
			fx = append(fx, StatusChanged{})
		}
		return fx
	case Ready:
		if !l.phase.loading || b.ItemID != l.itemID {
			return nil
		}
		first := l.phase.first
		l.phase = phase{}
		var fx []Effect
		resume := l.hold
		l.hold = false
		if resume {
			fx = append(fx, PlayerAction{Kind: PlayerPlay})
		}
		if snap != nil {
			fx = append(fx, send(l, stateOf(snap, !resume && snap.Paused)))
			l.lastState = now
		}
		if first && !l.announced {
			l.announced = true
			fx = append(fx, Notice{Kind: NoticeFollowing, Device: l.peer.Name})
		}
		return append(fx, StatusChanged{})
	case Cmd:
		// Commands during a load are ignored; the next `state` says what holds.
		if !linked {
			return nil
		}
		switch b.Action {
		case CmdPause:
			l.hold = false
			return []Effect{PlayerAction{Kind: PlayerPause}}
		case CmdPlay:
			l.hold = false
			return []Effect{PlayerAction{Kind: PlayerPlay}}
		case CmdSeek:
			return []Effect{PlayerAction{Kind: PlayerSeek, PositionMs: b.PositionMs}}
		case CmdNext:
			return []Effect{UINext}
		case CmdPrevious:
			return []Effect{UIPrevious}
		}
		return nil
	case Buffering:
		if !linked {
			return nil
		}
		if b.On {
			if l.tvBufferingSince != nil {
				return nil
			}
			since := now
			l.tvBufferingSince = &since
			var fx []Effect
			if snap != nil && !snap.Paused {
				l.hold = true
				fx = append(fx, PlayerAction{Kind: PlayerPause})
			}
			return append(fx, StatusChanged{})
		}
		if l.tvBufferingSince == nil {
			return nil
		}
		l.tvBufferingSince = nil
		var fx []Effect
		if l.hold {
			l.hold = false
			fx = append(fx, PlayerAction{Kind: PlayerPlay})
		}
		return append(fx, StatusChanged{})
	case Bye:
		var notice *Notice
		switch b.Reason {
		case ByeBusy:
			notice = &Notice{Kind: NoticeBusy, Device: l.peer.Name}
		case ByeTimeout:
			notice = &Notice{Kind: NoticeNoAnswer, Device: l.peer.Name}
		}
		fx := ld.end(b.Reason, notice, false)
		if b.Reason == ByeStopped {
			fx = append(fx, UIStop)
		}
		return fx
	}
	return nil
}

func (ld *Leader) tick(now Ms, snap *Snapshot) []Effect {
	l := ld.link
	if l == nil {
		return nil
	}
	noAnswer := &Notice{Kind: NoticeNoAnswer, Device: l.peer.Name}
	if l.phase.loading {
		limit := ReplyTimeout
		if l.phase.heard {
			limit = ReadyTimeout
		}
		if elapsed(now, l.phase.since) >= limit {
			return ld.end(ByeTimeout, noAnswer, true)
		}
		return nil
	}
	if elapsed(now, l.lastHeard) >= Silence {
		// Nothing is sent: the peer is gone or unreachable.
		return ld.end(ByeTimeout, &Notice{Kind: NoticeLost, Device: l.peer.Name}, false)
	}
	if l.tvBufferingSince != nil && elapsed(now, *l.tvBufferingSince) >= BufferingLimit {
		return ld.end(ByeTimeout, noAnswer, true)
	}
	if elapsed(now, l.lastState) >= StateEvery {
		return ld.stateNow(now, snap)
	}
	return nil
}

// stateNow is a `state` now, when linked and something plays.
func (ld *Leader) stateNow(now Ms, snap *Snapshot) []Effect {
	l := ld.link
	if l == nil || snap == nil || l.phase.loading {
		return nil
	}
	l.lastState = now
	return []Effect{send(l, stateOf(snap, snap.Paused))}
}

// end ends the link; tell sends `bye reason` to the peer. A playback paused for
// the link resumes, unless it stops.
func (ld *Leader) end(reason ByeReason, notice *Notice, tell bool) []Effect {
	l := ld.link
	if l == nil {
		return nil
	}
	ld.link = nil
	var fx []Effect
	if tell {
		fx = append(fx, send(l, Bye{Reason: reason}))
	}
	if l.hold && reason != ByeStopped {
		fx = append(fx, PlayerAction{Kind: PlayerPlay})
	}
	if notice != nil {
		fx = append(fx, *notice)
	}
	return append(fx, StatusChanged{})
}

func send(l *link, body Body) Effect {
	return Send{To: l.peer.SessionID, Link: l.id, Body: body}
}

func loadOf(s *Snapshot) Load {
	return Load{
		ItemID:              s.ItemID,
		MediaSourceID:       copyStr(s.MediaSourceID),
		PositionMs:          s.PositionMs,
		AudioStreamIndex:    copyI32(s.AudioStreamIndex),
		SubtitleStreamIndex: copyI32(s.SubtitleStreamIndex),
	}
}

func stateOf(s *Snapshot, paused bool) Body {
	return State{ItemID: s.ItemID, PositionMs: s.PositionMs, Paused: paused, Buffering: s.Buffering}
}

// The copies keep Rust's value semantics: an effect or a status never shares a
// pointer with the snapshot, the message or the leader.

func copyStr(p *string) *string {
	if p == nil {
		return nil
	}
	v := *p
	return &v
}

func copyI32(p *int32) *int32 {
	if p == nil {
		return nil
	}
	v := *p
	return &v
}

func copyI64(p *int64) *int64 {
	if p == nil {
		return nil
	}
	v := *p
	return &v
}
