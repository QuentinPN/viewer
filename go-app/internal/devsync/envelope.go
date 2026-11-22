// Package devsync is the linked playback computer ↔ TV (ADR 0123, 0124), ported
// from src-tauri/src/sync: the computer leads, the TV follows, and messages go
// through the Emby session relay (`SendString`).
//
// The package is named devsync, not sync, so that it never shadows the standard
// library's sync package.
package devsync

// Linked playback envelope (ADR 0124): one JSON object of at most 1024 bytes,
// relayed by Emby `SendString`. Decoding checks every bound of the ADR; a
// rejected message is dropped without answer, only its reason is logged.

// MaxBytes is the largest envelope, in bytes.
const MaxBytes = 1024

// MaxPositionMs is the largest position an envelope carries (24 h).
const MaxPositionMs uint32 = 86_400_000

const maxSeq uint64 = (1 << 31) - 1

// Reject is why a message was rejected; the codes are those of
// `docs/sync/vectors.json`. It is the error Decode returns.
type Reject int

// The reasons of a rejection, in the order of envelope.rs.
const (
	RejectJSON Reject = iota
	RejectSize
	RejectVersion
	RejectField
	RejectKind
)

// Code is the reason as the vectors name it.
func (r Reject) Code() string {
	switch r {
	case RejectJSON:
		return "json"
	case RejectSize:
		return "size"
	case RejectVersion:
		return "version"
	case RejectField:
		return "field"
	case RejectKind:
		return "kind"
	}
	return "unknown"
}

// Error makes a Reject an error: "sync envelope rejected (<code>)".
func (r Reject) Error() string { return "sync envelope rejected (" + r.Code() + ")" }

// CmdAction is the action of a `cmd`.
type CmdAction string

// The actions of a `cmd`.
const (
	CmdPause    CmdAction = "pause"
	CmdPlay     CmdAction = "play"
	CmdSeek     CmdAction = "seek"
	CmdNext     CmdAction = "next"
	CmdPrevious CmdAction = "previous"
)

// ByeReason is the reason of a `bye`.
type ByeReason string

// The reasons of a `bye`.
const (
	ByeStopped ByeReason = "stopped"
	ByeLeft    ByeReason = "left"
	ByeIdle    ByeReason = "idle"
	ByeBusy    ByeReason = "busy"
	ByeTimeout ByeReason = "timeout"
)

var allByeReasons = [...]ByeReason{ByeStopped, ByeLeft, ByeIdle, ByeBusy, ByeTimeout}

// Field is one field of a body, as it goes on the wire. Value is a string, a
// bool, or one of uint32, int32, int64 and uint64.
type Field struct {
	Key   string
	Value any
}

// Body is what an envelope carries: one of Join, Load, Ready, State, Cmd,
// Buffering, Ping, Pong and Bye (the variants of the Rust enum `Body`). Bodies
// are values, never nil and never pointers: a `&Bye{}` satisfies the interface
// but the leader's type switch ignores it.
type Body interface {
	// Kind is the `kind` on the wire.
	Kind() string
	// Fields are the fields of the kind, in the order of envelope.rs.
	Fields() []Field
	isBody()
}

// Label is the kind, with the reason of a `bye`: what the logs keep of a message.
func Label(b Body) string {
	if bye, ok := b.(Bye); ok {
		return "bye " + string(bye.Reason)
	}
	return b.Kind()
}

// Join asks a computer to lead the TV's playback.
type Join struct{}

// Load is what `load` opens on the TV: no URL, the TV negotiates its own stream.
type Load struct {
	ItemID              string
	MediaSourceID       *string
	PositionMs          uint32
	AudioStreamIndex    *int32
	SubtitleStreamIndex *int32
}

// Ready says the TV opened the title at a position.
type Ready struct {
	ItemID     string
	PositionMs uint32
}

// State is the leader's playback.
type State struct {
	ItemID     string
	PositionMs uint32
	Paused     bool
	Buffering  bool
}

// Cmd is a command of the TV. PositionMs is only set for CmdSeek.
type Cmd struct {
	Action     CmdAction
	PositionMs uint32
}

// Buffering says the TV started or stopped buffering.
type Buffering struct {
	On bool
}

// Ping is the TV's heartbeat, with the drift it measured.
type Ping struct {
	T       uint64
	DriftMs *int64
}

// Pong answers a Ping.
type Pong struct {
	T uint64
}

// Bye ends the link.
type Bye struct {
	Reason ByeReason
}

func (Join) isBody()      {}
func (Load) isBody()      {}
func (Ready) isBody()     {}
func (State) isBody()     {}
func (Cmd) isBody()       {}
func (Buffering) isBody() {}
func (Ping) isBody()      {}
func (Pong) isBody()      {}
func (Bye) isBody()       {}

func (Join) Kind() string      { return "join" }
func (Load) Kind() string      { return "load" }
func (Ready) Kind() string     { return "ready" }
func (State) Kind() string     { return "state" }
func (Cmd) Kind() string       { return "cmd" }
func (Buffering) Kind() string { return "buffering" }
func (Ping) Kind() string      { return "ping" }
func (Pong) Kind() string      { return "pong" }
func (Bye) Kind() string       { return "bye" }

func (Join) Fields() []Field { return nil }

func (l Load) Fields() []Field {
	f := []Field{{"itemId", l.ItemID}}
	if l.MediaSourceID != nil {
		f = append(f, Field{"mediaSourceId", *l.MediaSourceID})
	}
	f = append(f, Field{"positionMs", l.PositionMs})
	if l.AudioStreamIndex != nil {
		f = append(f, Field{"audioStreamIndex", *l.AudioStreamIndex})
	}
	if l.SubtitleStreamIndex != nil {
		f = append(f, Field{"subtitleStreamIndex", *l.SubtitleStreamIndex})
	}
	return f
}

func (r Ready) Fields() []Field {
	return []Field{{"itemId", r.ItemID}, {"positionMs", r.PositionMs}}
}

func (s State) Fields() []Field {
	return []Field{
		{"itemId", s.ItemID},
		{"positionMs", s.PositionMs},
		{"paused", s.Paused},
		{"buffering", s.Buffering},
	}
}

func (c Cmd) Fields() []Field {
	if c.Action == CmdSeek {
		return []Field{{"positionMs", c.PositionMs}, {"action", string(c.Action)}}
	}
	return []Field{{"action", string(c.Action)}}
}

func (b Buffering) Fields() []Field { return []Field{{"on", b.On}} }

func (p Ping) Fields() []Field {
	f := []Field{{"t", p.T}}
	if p.DriftMs != nil {
		f = append(f, Field{"driftMs", *p.DriftMs})
	}
	return f
}

func (p Pong) Fields() []Field { return []Field{{"t", p.T}} }

func (b Bye) Fields() []Field { return []Field{{"reason", string(b.Reason)}} }

// Envelope is one linked playback message.
type Envelope struct {
	From string
	Link string
	Seq  uint32
	Body Body
}

// IsID reports whether s is `[A-Za-z0-9_-]` of min to 64 bytes: session, link,
// item and source ids.
func IsID(s string, min int) bool {
	if len(s) < min || len(s) > 64 {
		return false
	}
	for i := 0; i < len(s); i++ {
		b := s[i]
		ok := (b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z') || (b >= '0' && b <= '9') || b == '-' || b == '_'
		if !ok {
			return false
		}
	}
	return true
}

// LinkView is the link as the filter sees it: the peer, the link id and the
// last `seq` read (nil before the first).
type LinkView struct {
	Peer    string
	Link    string
	LastSeq *uint32
}

// Accept is whether a valid message is read.
type Accept int

// The outcomes of Accepts.
const (
	AcceptNo Accept = iota
	AcceptYes
	// AcceptBusy: a `join` or `load` of another session while linked: answer `bye busy`.
	AcceptBusy
)

// Accepts is whether a valid message is read (ADR 0124 « Hors sujet »). link is
// nil outside a link.
func Accepts(link *LinkView, from, msgLink string, seq uint32, kind string) Accept {
	opens := kind == "join" || kind == "load"
	switch {
	case link == nil && opens:
		return AcceptYes
	case link == nil:
		return AcceptNo
	case from == link.Peer:
		if msgLink == link.Link && (link.LastSeq == nil || seq > *link.LastSeq) {
			return AcceptYes
		}
		return AcceptNo
	case opens:
		return AcceptBusy
	default:
		return AcceptNo
	}
}
