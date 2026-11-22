package devsync

// The envelope on the wire, byte for byte like envelope.rs with serde_json
// 1.0.154 (src-tauri/Cargo.lock), built without `preserve_order` and without
// `arbitrary_precision`:
//   - encode: serde_json's Map is a BTreeMap, so the keys are written sorted,
//     compact, with serde_json's string escapes (not encoding/json's, which
//     also escapes <, >, & and U+2028);
//   - decode: encoding/json checks the syntax, then serdeReadable rejects what
//     serde_json also refuses (nesting of 128, lone surrogate escapes, numbers
//     out of the f64 range), and the numbers are classed from their literal
//     text the way serde_json classes them (u64, negative i64, else f64).

import (
	"bytes"
	"encoding/json"
	"errors"
	"math"
	"slices"
	"strconv"
	"strings"
	"unicode/utf8"
)

// Encode writes the envelope as it goes on the wire. Body must not be nil.
func (e Envelope) Encode() string {
	m := map[string]any{
		"tv": "sync",
		"v":    uint64(1),
		"from": e.From,
		"link": e.Link,
		"seq":  e.Seq,
		"kind": e.Body.Kind(),
	}
	// Map::extend: a body field replaces a header field of the same name.
	for _, f := range e.Body.Fields() {
		m[f.Key] = f.Value
	}
	keys := make([]string, 0, len(m))
	for k := range m {
		keys = append(keys, k)
	}
	slices.Sort(keys) // BTreeMap<String, _>: byte order of the UTF-8 keys
	var b strings.Builder
	b.WriteByte('{')
	for i, k := range keys {
		if i > 0 {
			b.WriteByte(',')
		}
		writeString(&b, k)
		b.WriteByte(':')
		writeValue(&b, m[k])
	}
	b.WriteByte('}')
	return b.String()
}

func writeValue(b *strings.Builder, v any) {
	switch x := v.(type) {
	case string:
		writeString(b, x)
	case bool:
		b.WriteString(strconv.FormatBool(x))
	case uint32:
		b.WriteString(strconv.FormatUint(uint64(x), 10))
	case uint64:
		b.WriteString(strconv.FormatUint(x, 10))
	case int32:
		b.WriteString(strconv.FormatInt(int64(x), 10))
	case int64:
		b.WriteString(strconv.FormatInt(x, 10))
	default:
		// Fields only hold the types above (see Field).
		b.WriteString("null")
	}
}

const hexDigits = "0123456789abcdef"

// writeString is serde_json's format_escaped_str: `"`, `\` and the C0 controls
// are escaped (\b \t \n \f \r short, the others \u00xx), everything else is
// written as is. A byte that is not UTF-8 (a Rust String cannot hold one) is
// written U+FFFD, like encoding/json.
func writeString(b *strings.Builder, s string) {
	b.WriteByte('"')
	for i := 0; i < len(s); {
		c := s[i]
		if c < utf8.RuneSelf {
			switch c {
			case '"':
				b.WriteString(`\"`)
			case '\\':
				b.WriteString(`\\`)
			case '\b':
				b.WriteString(`\b`)
			case '\f':
				b.WriteString(`\f`)
			case '\n':
				b.WriteString(`\n`)
			case '\r':
				b.WriteString(`\r`)
			case '\t':
				b.WriteString(`\t`)
			default:
				if c < 0x20 {
					b.WriteString(`\u00`)
					b.WriteByte(hexDigits[c>>4])
					b.WriteByte(hexDigits[c&0xf])
				} else {
					b.WriteByte(c)
				}
			}
			i++
			continue
		}
		r, size := utf8.DecodeRuneInString(s[i:])
		if r == utf8.RuneError && size == 1 {
			b.WriteRune(utf8.RuneError)
		} else {
			b.WriteString(s[i : i+size])
		}
		i += size
	}
	b.WriteByte('"')
}

// Decode reads one envelope. The error is always a Reject.
func Decode(raw string) (Envelope, error) {
	if len(raw) > MaxBytes {
		return Envelope{}, RejectSize
	}
	// A Rust &str is always UTF-8; encoding/json would replace bad bytes.
	if !utf8.ValidString(raw) {
		return Envelope{}, RejectJSON
	}
	var m map[string]json.RawMessage
	if err := json.Unmarshal([]byte(raw), &m); err != nil || m == nil || !serdeReadable(raw) {
		return Envelope{}, RejectJSON
	}
	if s, ok := asStr(m["tv"]); !ok || s != "sync" {
		return Envelope{}, RejectVersion
	}
	if v, ok := asU64(m["v"]); !ok || v != 1 {
		return Envelope{}, RejectVersion
	}
	from, err := requiredID(m, "from", 1)
	if err != nil {
		return Envelope{}, err
	}
	link, err := requiredID(m, "link", 8)
	if err != nil {
		return Envelope{}, err
	}
	seq, ok := asU64(m["seq"])
	if !ok || seq > maxSeq {
		return Envelope{}, RejectField
	}
	kind, ok := asStr(m["kind"])
	if !ok {
		return Envelope{}, RejectField
	}
	body, err := decodeBody(m, kind)
	if err != nil {
		return Envelope{}, err
	}
	return Envelope{From: from, Link: link, Seq: uint32(seq), Body: body}, nil
}

func decodeBody(m map[string]json.RawMessage, kind string) (Body, error) {
	item := func() (string, error) { return requiredID(m, "itemId", 1) }
	position := func() (uint32, error) {
		n, err := optInt(m, "positionMs", 0, int64(MaxPositionMs))
		if err != nil {
			return 0, err
		}
		if n == nil {
			return 0, RejectField
		}
		return uint32(*n), nil
	}
	track := func(key string) (*int32, error) {
		n, err := optInt(m, key, -1, 999)
		if err != nil || n == nil {
			return nil, err
		}
		t := int32(*n)
		return &t, nil
	}
	switch kind {
	case "join":
		return Join{}, nil
	case "load":
		var l Load
		var err error
		if l.ItemID, err = item(); err != nil {
			return nil, err
		}
		if l.MediaSourceID, err = optID(m, "mediaSourceId", 1); err != nil {
			return nil, err
		}
		if l.PositionMs, err = position(); err != nil {
			return nil, err
		}
		if l.AudioStreamIndex, err = track("audioStreamIndex"); err != nil {
			return nil, err
		}
		if l.SubtitleStreamIndex, err = track("subtitleStreamIndex"); err != nil {
			return nil, err
		}
		return l, nil
	case "ready":
		it, err := item()
		if err != nil {
			return nil, err
		}
		p, err := position()
		if err != nil {
			return nil, err
		}
		return Ready{ItemID: it, PositionMs: p}, nil
	case "state":
		it, err := item()
		if err != nil {
			return nil, err
		}
		p, err := position()
		if err != nil {
			return nil, err
		}
		paused, ok := asBool(m["paused"])
		if !ok {
			return nil, RejectField
		}
		buffering, ok := asBool(m["buffering"])
		if !ok {
			return nil, RejectField
		}
		return State{ItemID: it, PositionMs: p, Paused: paused, Buffering: buffering}, nil
	case "cmd":
		action, _ := asStr(m["action"])
		switch a := CmdAction(action); a {
		case CmdPause, CmdPlay, CmdNext, CmdPrevious:
			return Cmd{Action: a}, nil
		case CmdSeek:
			p, err := position()
			if err != nil {
				return nil, err
			}
			return Cmd{Action: CmdSeek, PositionMs: p}, nil
		}
		return nil, RejectField
	case "buffering":
		on, ok := asBool(m["on"])
		if !ok {
			return nil, RejectField
		}
		return Buffering{On: on}, nil
	case "ping":
		t, ok := asU64(m["t"])
		if !ok {
			return nil, RejectField
		}
		drift, err := optInt(m, "driftMs", math.MinInt64, math.MaxInt64)
		if err != nil {
			return nil, err
		}
		return Ping{T: t, DriftMs: drift}, nil
	case "pong":
		t, ok := asU64(m["t"])
		if !ok {
			return nil, RejectField
		}
		return Pong{T: t}, nil
	case "bye":
		reason, ok := asStr(m["reason"])
		if ok {
			for _, r := range allByeReasons {
				if string(r) == reason {
					return Bye{Reason: r}, nil
				}
			}
		}
		return nil, RejectField
	}
	return nil, RejectKind
}

// optID: nil when absent, RejectField when present but not an id of min to 64.
func optID(m map[string]json.RawMessage, key string, min int) (*string, error) {
	raw, present := m[key]
	if !present {
		return nil, nil
	}
	s, ok := asStr(raw)
	if !ok || !IsID(s, min) {
		return nil, RejectField
	}
	return &s, nil
}

func requiredID(m map[string]json.RawMessage, key string, min int) (string, error) {
	s, err := optID(m, key, min)
	if err != nil {
		return "", err
	}
	if s == nil {
		return "", RejectField
	}
	return *s, nil
}

// optInt: nil when absent, RejectField when present but not an i64 in [lo, hi].
func optInt(m map[string]json.RawMessage, key string, lo, hi int64) (*int64, error) {
	raw, present := m[key]
	if !present {
		return nil, nil
	}
	n, ok := asI64(raw)
	if !ok || n < lo || n > hi {
		return nil, RejectField
	}
	return &n, nil
}

func asStr(raw json.RawMessage) (string, bool) {
	raw = bytes.TrimSpace(raw)
	if len(raw) == 0 || raw[0] != '"' {
		return "", false
	}
	var s string
	if json.Unmarshal(raw, &s) != nil {
		return "", false
	}
	return s, true
}

func asBool(raw json.RawMessage) (bool, bool) {
	switch string(bytes.TrimSpace(raw)) {
	case "true":
		return true, true
	case "false":
		return false, true
	}
	return false, false
}

// intLiteral splits a JSON integer literal (no fraction, no exponent).
func intLiteral(raw json.RawMessage) (neg bool, digits string, ok bool) {
	s := string(bytes.TrimSpace(raw))
	if strings.HasPrefix(s, "-") {
		neg, s = true, s[1:]
	}
	if s == "" {
		return false, "", false
	}
	for i := 0; i < len(s); i++ {
		if s[i] < '0' || s[i] > '9' {
			return false, "", false
		}
	}
	return neg, s, true
}

// asU64 is serde_json's Value::as_u64: a non-negative integer literal that fits u64.
func asU64(raw json.RawMessage) (uint64, bool) {
	neg, digits, ok := intLiteral(raw)
	if !ok || neg {
		return 0, false
	}
	n, err := strconv.ParseUint(digits, 10, 64)
	return n, err == nil
}

// asI64 is serde_json's Value::as_i64: an integer literal that fits i64. `-0`
// is read by serde_json as the float -0.0, so it is not one.
func asI64(raw json.RawMessage) (int64, bool) {
	neg, digits, ok := intLiteral(raw)
	if !ok || (neg && digits == "0") {
		return 0, false
	}
	if neg {
		digits = "-" + digits
	}
	n, err := strconv.ParseInt(digits, 10, 64)
	return n, err == nil
}

// serdeDepth is serde_json's recursion limit: the 128th nested array or object
// is refused.
const serdeDepth = 128

// serdeReadable reports whether serde_json also reads s, which encoding/json
// already found well-formed: nesting under serdeDepth, every \u surrogate
// escape paired, no number out of the f64 range.
func serdeReadable(s string) bool {
	depth := 0
	for i := 0; i < len(s); i++ {
		switch c := s[i]; {
		case c == '{' || c == '[':
			depth++
			if depth >= serdeDepth {
				return false
			}
		case c == '}' || c == ']':
			depth--
		case c == '"':
			end, ok := scanString(s, i)
			if !ok {
				return false
			}
			i = end
		case c == '-' || (c >= '0' && c <= '9'):
			j := i
			for j < len(s) && strings.IndexByte("+-.eE0123456789", s[j]) >= 0 {
				j++
			}
			if outOfRange(s[i:j]) {
				return false
			}
			i = j - 1
		}
	}
	return true
}

// scanString walks the string that opens at s[start] and returns the index of
// its closing quote; ok is false on a lone surrogate escape.
func scanString(s string, start int) (end int, ok bool) {
	for i := start + 1; i < len(s); i++ {
		switch s[i] {
		case '"':
			return i, true
		case '\\':
			i++
			if i >= len(s) || s[i] != 'u' {
				continue
			}
			n, ok := hex4(s, i+1)
			if !ok || (n >= 0xDC00 && n <= 0xDFFF) {
				return 0, false
			}
			i += 4
			if n < 0xD800 || n > 0xDBFF {
				continue
			}
			// A leading surrogate needs a trailing one right after.
			if i+2 >= len(s) || s[i+1] != '\\' || s[i+2] != 'u' {
				return 0, false
			}
			n2, ok := hex4(s, i+3)
			if !ok || n2 < 0xDC00 || n2 > 0xDFFF {
				return 0, false
			}
			i += 6
		}
	}
	return 0, false
}

func hex4(s string, at int) (uint64, bool) {
	if at+4 > len(s) {
		return 0, false
	}
	n, err := strconv.ParseUint(s[at:at+4], 16, 16)
	return n, err == nil
}

// outOfRange: serde_json refuses a number it reads as an f64 that overflows.
// Integer literals that fit u64 or i64 are never floats.
func outOfRange(lit string) bool {
	if _, ok := asU64(json.RawMessage(lit)); ok {
		return false
	}
	if _, ok := asI64(json.RawMessage(lit)); ok {
		return false
	}
	f, err := strconv.ParseFloat(lit, 64)
	return errors.Is(err, strconv.ErrRange) && math.IsInf(f, 0)
}
