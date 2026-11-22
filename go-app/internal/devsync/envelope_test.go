package devsync

import (
	"bytes"
	"encoding/json"
	"errors"
	"os"
	"reflect"
	"strings"
	"testing"
)

// The vectors shared by the desktop (Rust, now Go) and the Android TV app
// (SyncVectorsTest.kt). Read in place, not copied: the three sides must test
// against one file.
const vectorsPath = "../../../docs/sync/vectors.json"

type vectorFile struct {
	MaxBytes uint64 `json:"maxBytes"`
	Valid    []struct {
		Name   string         `json:"name"`
		Raw    string         `json:"raw"`
		Kind   string         `json:"kind"`
		Fields map[string]any `json:"fields"`
		From   string         `json:"from"`
		Link   string         `json:"link"`
		Seq    uint64         `json:"seq"`
	} `json:"valid"`
	Invalid []struct {
		Name   string `json:"name"`
		Raw    string `json:"raw"`
		Reason string `json:"reason"`
	} `json:"invalid"`
	Accept []struct {
		Name  string `json:"name"`
		State *struct {
			Peer    string  `json:"peer"`
			Link    string  `json:"link"`
			LastSeq *uint64 `json:"lastSeq"`
		} `json:"state"`
		Msg struct {
			From string `json:"from"`
			Link string `json:"link"`
			Seq  uint64 `json:"seq"`
			Kind string `json:"kind"`
		} `json:"msg"`
		Accept any `json:"accept"`
	} `json:"accept"`
}

func vectors(t *testing.T) vectorFile {
	t.Helper()
	b, err := os.ReadFile(vectorsPath)
	if err != nil {
		t.Fatalf("vectors: %v", err)
	}
	dec := json.NewDecoder(bytes.NewReader(b))
	dec.UseNumber()
	var v vectorFile
	if err := dec.Decode(&v); err != nil {
		t.Fatalf("vectors: %v", err)
	}
	return v
}

// fieldsValue is Body.Fields as a JSON value (numbers as json.Number), the Go
// side of `Value::Object(env.body.fields())`.
func fieldsValue(t *testing.T, b Body) map[string]any {
	t.Helper()
	m := map[string]any{}
	for _, f := range b.Fields() {
		m[f.Key] = f.Value
	}
	raw, err := json.Marshal(m)
	if err != nil {
		t.Fatal(err)
	}
	dec := json.NewDecoder(bytes.NewReader(raw))
	dec.UseNumber()
	var out map[string]any
	if err := dec.Decode(&out); err != nil {
		t.Fatal(err)
	}
	return out
}

func rejectOf(err error) (Reject, bool) {
	var r Reject
	ok := errors.As(err, &r)
	return r, ok
}

func Test_max_bytes_matches_the_vectors(t *testing.T) {
	if got := vectors(t).MaxBytes; got != MaxBytes {
		t.Fatalf("maxBytes %d, want %d", got, MaxBytes)
	}
}

func Test_valid_vectors_decode_and_round_trip(t *testing.T) {
	cases := vectors(t).Valid
	if len(cases) == 0 {
		t.Fatal("no valid vector")
	}
	for _, c := range cases {
		env, err := Decode(c.Raw)
		if err != nil {
			t.Errorf("%s: rejected (%v)", c.Name, err)
			continue
		}
		if env.Body.Kind() != c.Kind {
			t.Errorf("%s: kind %q, want %q", c.Name, env.Body.Kind(), c.Kind)
		}
		if env.From != c.From || env.Link != c.Link || uint64(env.Seq) != c.Seq {
			t.Errorf("%s: from/link/seq %q/%q/%d", c.Name, env.From, env.Link, env.Seq)
		}
		want := c.Fields
		if want == nil {
			want = map[string]any{}
		}
		if got := fieldsValue(t, env.Body); !reflect.DeepEqual(got, want) {
			t.Errorf("%s: fields %v, want %v", c.Name, got, want)
		}
		back, err := Decode(env.Encode())
		if err != nil || !reflect.DeepEqual(back, env) {
			t.Errorf("%s: round trip %+v (%v), want %+v", c.Name, back, err, env)
		}
	}
}

func Test_invalid_vectors_are_rejected_for_their_reason(t *testing.T) {
	cases := vectors(t).Invalid
	if len(cases) == 0 {
		t.Fatal("no invalid vector")
	}
	for _, c := range cases {
		env, err := Decode(c.Raw)
		r, ok := rejectOf(err)
		if !ok || r.Code() != c.Reason {
			t.Errorf("%s: got %+v (%v), want reject %s", c.Name, env, err, c.Reason)
		}
	}
}

func Test_accept_vectors(t *testing.T) {
	cases := vectors(t).Accept
	if len(cases) == 0 {
		t.Fatal("no accept vector")
	}
	for _, c := range cases {
		var view *LinkView
		if s := c.State; s != nil {
			view = &LinkView{Peer: s.Peer, Link: s.Link}
			if s.LastSeq != nil {
				n := uint32(*s.LastSeq)
				view.LastSeq = &n
			}
		}
		got := Accepts(view, c.Msg.From, c.Msg.Link, uint32(c.Msg.Seq), c.Msg.Kind)
		var want Accept
		switch a := c.Accept.(type) {
		case bool:
			if a {
				want = AcceptYes
			} else {
				want = AcceptNo
			}
		case string:
			if a != "busy" {
				t.Fatalf("%s: unexpected %q", c.Name, a)
			}
			want = AcceptBusy
		default:
			t.Fatalf("%s: unexpected %v", c.Name, a)
		}
		if got != want {
			t.Errorf("%s: got %d, want %d", c.Name, got, want)
		}
	}
}

func Test_encoded_envelopes_fit_the_limit(t *testing.T) {
	ms := strings.Repeat("m", 64)
	audio, sub := int32(999), int32(-1)
	env := Envelope{
		From: strings.Repeat("s", 64),
		Link: strings.Repeat("l", 64),
		Seq:  uint32(maxSeq),
		Body: Load{
			ItemID:              strings.Repeat("i", 64),
			MediaSourceID:       &ms,
			PositionMs:          MaxPositionMs,
			AudioStreamIndex:    &audio,
			SubtitleStreamIndex: &sub,
		},
	}
	if n := len(env.Encode()); n > MaxBytes {
		t.Fatalf("encoded %d bytes", n)
	}
}

// ---------------------------------------------------------- Go-only tests

// serde_json is built without `preserve_order` (Cargo.lock: no indexmap
// dependency), so its Map is a BTreeMap and `Value::to_string` writes the keys
// sorted, compact, without spaces. The expected texts here and in the escape
// test below were checked on 2026-11-22 against envelope.rs run with serde_json
// 1.0.154 (research/serde-json-envelope.md).
func Test_go_encode_writes_sorted_keys_like_serde_json(t *testing.T) {
	ms := "mediasource_68397"
	audio, sub := int32(1), int32(3)
	drift := int64(-42)
	cases := []struct {
		body Body
		want string
	}{
		{Join{}, `{"from":"sess-a1","kind":"join","link":"link-0001","seq":7,"tv":"sync","v":1}`},
		{
			Load{ItemID: "68397", MediaSourceID: &ms, PositionMs: 754000, AudioStreamIndex: &audio, SubtitleStreamIndex: &sub},
			`{"audioStreamIndex":1,"from":"sess-a1","itemId":"68397","kind":"load","link":"link-0001","mediaSourceId":"mediasource_68397","positionMs":754000,"seq":7,"subtitleStreamIndex":3,"tv":"sync","v":1}`,
		},
		{Load{ItemID: "1", PositionMs: 0}, `{"from":"sess-a1","itemId":"1","kind":"load","link":"link-0001","positionMs":0,"seq":7,"tv":"sync","v":1}`},
		{State{ItemID: "68397", PositionMs: 1, Paused: true}, `{"buffering":false,"from":"sess-a1","itemId":"68397","kind":"state","link":"link-0001","paused":true,"positionMs":1,"seq":7,"tv":"sync","v":1}`},
		{Cmd{Action: CmdSeek, PositionMs: 128000}, `{"action":"seek","from":"sess-a1","kind":"cmd","link":"link-0001","positionMs":128000,"seq":7,"tv":"sync","v":1}`},
		{Cmd{Action: CmdPause}, `{"action":"pause","from":"sess-a1","kind":"cmd","link":"link-0001","seq":7,"tv":"sync","v":1}`},
		{Ping{T: 123456789, DriftMs: &drift}, `{"driftMs":-42,"from":"sess-a1","kind":"ping","link":"link-0001","seq":7,"t":123456789,"tv":"sync","v":1}`},
		{Pong{T: 18446744073709551615}, `{"from":"sess-a1","kind":"pong","link":"link-0001","seq":7,"t":18446744073709551615,"tv":"sync","v":1}`},
		{Buffering{On: true}, `{"from":"sess-a1","kind":"buffering","link":"link-0001","on":true,"seq":7,"tv":"sync","v":1}`},
		{Bye{Reason: ByeBusy}, `{"from":"sess-a1","kind":"bye","link":"link-0001","reason":"busy","seq":7,"tv":"sync","v":1}`},
	}
	for _, c := range cases {
		got := Envelope{From: "sess-a1", Link: "link-0001", Seq: 7, Body: c.body}.Encode()
		if got != c.want {
			t.Errorf("%s:\ngot  %s\nwant %s", c.body.Kind(), got, c.want)
		}
	}
}

// serde_json escapes `"`, `\` and the C0 controls only (\b \t \n \f \r short,
// the others \u00xx in lowercase); `<`, `>`, `&`, U+2028 and DEL stay raw.
func Test_go_encode_escapes_strings_like_serde_json(t *testing.T) {
	env := Envelope{From: "a\"b\\c<>&\u2028\x7f\x01\x1f\b\t\n\f\ré", Link: "link-0001", Body: Join{}}
	want := `{"from":"a\"b\\c<>&` + "\u2028\x7f" + `\u0001\u001f\b\t\n\f\ré","kind":"join","link":"link-0001","seq":0,"tv":"sync","v":1}`
	if got := env.Encode(); got != want {
		t.Errorf("got  %s\nwant %s", got, want)
	}
	// A Go string can hold bytes that are not UTF-8; a Rust String cannot. Each
	// bad byte is written U+FFFD, like encoding/json.
	bad := Envelope{From: "a\xffb", Link: "link-0001", Body: Join{}}
	if got := bad.Encode(); !strings.HasPrefix(got, `{"from":"a`+"\uFFFD"+`b"`) {
		t.Errorf("invalid UTF-8: %s", got)
	}
}

func nest(n int) string { return strings.Repeat("[", n) + strings.Repeat("]", n) }

const head = `{"tv":"sync","v":1,"from":"sess-a1","link":"link-0001","seq":7,`

// Decoding follows serde_json 1.0.154 (src-tauri/Cargo.lock) where encoding/json
// differs: depth limit, surrogate escapes, number classes and range. Every
// expected code was checked on 2026-11-22 against envelope.rs run with that
// crate, except "invalid UTF-8", which a Rust &str cannot hold.
var decodeEdgeCases = []struct {
	name string
	raw  string
	want string // reject code, or "" for accepted
}{
	{"top-level null", `null`, "json"},
	{"top-level string", `"x"`, "json"},
	{"top-level number", `1`, "json"},
	{"trailing data", head + `"kind":"join"} x`, "json"},
	{"trailing object", head + `"kind":"join"}{}`, "json"},
	{"trailing whitespace", head + `"kind":"join"}` + " \t\r\n", ""},
	{"leading whitespace", " \n" + head + `"kind":"join"}`, ""},
	{"invalid UTF-8", head + `"kind":"join","x":"` + "\xff" + `"}`, "json"},
	{"depth 127 accepted", head + `"kind":"join","x":` + nest(126) + `}`, ""},
	{"depth 128 rejected", head + `"kind":"join","x":` + nest(127) + `}`, "json"},
	{"lone trailing surrogate", head + `"kind":"join","x":"\udc00"}`, "json"},
	{"lone leading surrogate", head + `"kind":"join","x":"\ud800"}`, "json"},
	{"leading surrogate then text", head + `"kind":"join","x":"\ud800x"}`, "json"},
	{"two leading surrogates", head + `"kind":"join","x":"\ud800\ud800"}`, "json"},
	{"surrogate in a key", head + `"kind":"join","\udfff":1}`, "json"},
	{"surrogate pair", head + `"kind":"join","x":"\ud83d\ude00"}`, ""},
	{"escaped backslash before u", head + `"kind":"join","x":"\\udc00"}`, ""},
	{"float out of range", head + `"kind":"join","x":1e999}`, "json"},
	{"negative float out of range", head + `"kind":"join","x":-1e999}`, "json"},
	{"long integer out of range", head + `"kind":"join","x":` + strings.Repeat("9", 400) + `}`, "json"},
	{"float underflow", head + `"kind":"join","x":1e-999}`, ""},
	{"zero with a huge exponent", head + `"kind":"join","x":0e999}`, ""},
	{"duplicate key, last wins", head + `"kind":"join","kind":"dance"}`, "kind"},
	{"duplicate key, last wins (valid)", head + `"kind":"dance","kind":"join"}`, ""},
	{"v as float", `{"tv":"sync","v":1.0,"from":"sess-a1","link":"link-0001","seq":7,"kind":"join"}`, "version"},
	{"v as string", `{"tv":"sync","v":"1","from":"sess-a1","link":"link-0001","seq":7,"kind":"join"}`, "version"},
	{"v with exponent", `{"tv":"sync","v":1e0,"from":"sess-a1","link":"link-0001","seq":7,"kind":"join"}`, "version"},
	{"seq at the limit", `{"tv":"sync","v":1,"from":"sess-a1","link":"link-0001","seq":2147483647,"kind":"join"}`, ""},
	{"seq over the limit", `{"tv":"sync","v":1,"from":"sess-a1","link":"link-0001","seq":2147483648,"kind":"join"}`, "field"},
	{"seq negative zero", `{"tv":"sync","v":1,"from":"sess-a1","link":"link-0001","seq":-0,"kind":"join"}`, "field"},
	{"position negative zero", head + `"kind":"ready","itemId":"a","positionMs":-0}`, "field"},
	{"position as float", head + `"kind":"ready","itemId":"a","positionMs":5.0}`, "field"},
	{"t at u64 max", head + `"kind":"pong","t":18446744073709551615}`, ""},
	{"t over u64", head + `"kind":"pong","t":18446744073709551616}`, "field"},
	{"t negative", head + `"kind":"pong","t":-1}`, "field"},
	{"drift at i64 min", head + `"kind":"ping","t":1,"driftMs":-9223372036854775808}`, ""},
	{"drift under i64", head + `"kind":"ping","t":1,"driftMs":-9223372036854775809}`, "field"},
	{"drift over i64", head + `"kind":"ping","t":1,"driftMs":9223372036854775808}`, "field"},
	{"drift null", head + `"kind":"ping","t":1,"driftMs":null}`, "field"},
	{"media source null", head + `"kind":"load","itemId":"1","positionMs":1,"mediaSourceId":null}`, "field"},
	{"subtitle -1", head + `"kind":"load","itemId":"1","positionMs":1,"subtitleStreamIndex":-1}`, ""},
	{"subtitle -2", head + `"kind":"load","itemId":"1","positionMs":1,"subtitleStreamIndex":-2}`, "field"},
	{"kind not a string", head + `"kind":1}`, "field"},
	{"kind missing", head[:len(head)-1] + `}`, "field"},
	{"from too long", `{"tv":"sync","v":1,"from":"` + strings.Repeat("a", 65) + `","link":"link-0001","seq":7,"kind":"join"}`, "field"},
	{"link of 7", `{"tv":"sync","v":1,"from":"a","link":"link-00","seq":7,"kind":"join"}`, "field"},
	{"link of 8", `{"tv":"sync","v":1,"from":"a","link":"link-000","seq":7,"kind":"join"}`, ""},
	{"from with a non-ASCII letter", `{"tv":"sync","v":1,"from":"é","link":"link-0001","seq":7,"kind":"join"}`, "field"},
	{"escaped id", `{"tv":"sync","v":1,"from":"\u0061","link":"link-0001","seq":7,"kind":"join"}`, ""},
	{"exactly 1024 bytes", padTo(1024), ""},
	{"1025 bytes", padTo(1025), "size"},
	{"version checked before fields", `{"tv":"sync","v":2}`, "version"},
	{"fields checked before kind", `{"tv":"sync","v":1,"kind":"dance"}`, "field"},
}

func Test_go_decode_follows_serde_json_on_edge_cases(t *testing.T) {
	for _, c := range decodeEdgeCases {
		env, err := Decode(c.raw)
		got := ""
		if err != nil {
			r, ok := rejectOf(err)
			if !ok {
				t.Errorf("%s: error %v is not a Reject", c.name, err)
				continue
			}
			got = r.Code()
		}
		if got != c.want {
			t.Errorf("%s: got %q (%+v), want %q", c.name, got, env, c.want)
		}
	}
}

// padTo is a valid `join` of exactly n bytes.
func padTo(n int) string {
	base := head + `"kind":"join","pad":""}`
	return head + `"kind":"join","pad":"` + strings.Repeat("x", n-len(base)) + `"}`
}

func Test_go_label_names_the_bye_reason(t *testing.T) {
	if got := Label(Bye{Reason: ByeTimeout}); got != "bye timeout" {
		t.Errorf("bye: %q", got)
	}
	if got := Label(Cmd{Action: CmdNext}); got != "cmd" {
		t.Errorf("cmd: %q", got)
	}
}

func Test_go_is_id_counts_bytes(t *testing.T) {
	cases := []struct {
		s    string
		min  int
		want bool
	}{
		{"", 1, false},
		{"", 0, true},
		{"a", 1, true},
		{strings.Repeat("a", 64), 1, true},
		{strings.Repeat("a", 65), 1, false},
		{"A-z_09", 1, true},
		{"a b", 1, false},
		{"a.b", 1, false},
		{"é", 1, false},
		{"link-000", 8, true},
		{"link-00", 8, false},
	}
	for _, c := range cases {
		if got := IsID(c.s, c.min); got != c.want {
			t.Errorf("IsID(%q, %d) = %v", c.s, c.min, got)
		}
	}
}

func Test_go_reject_codes(t *testing.T) {
	want := map[Reject]string{RejectJSON: "json", RejectSize: "size", RejectVersion: "version", RejectField: "field", RejectKind: "kind"}
	for r, code := range want {
		if r.Code() != code {
			t.Errorf("%d: %q", r, r.Code())
		}
		if !strings.Contains(r.Error(), code) {
			t.Errorf("%d: %q", r, r.Error())
		}
	}
}
