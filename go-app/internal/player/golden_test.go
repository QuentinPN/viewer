package player

// Golden tests of the player unit (ADR 0128): events.player_event.*,
// events.end_reason.all and errors.player_error.all_kinds, built by Go code and
// compared token by token (key order kept, numbers by value: serde writes 6000.0
// where encoding/json writes 6000). The comparer is a copy of the one in
// internal/emby/golden_test.go: test helpers of another package cannot be
// imported (see MAP.md, unit player).

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math/big"
	"os"
	"path/filepath"
	"testing"
)

const goldenDir = "../../testdata/golden"

func readGolden(t *testing.T, name string) []byte {
	t.Helper()
	b, err := os.ReadFile(filepath.Join(goldenDir, name))
	if err != nil {
		t.Fatalf("golden %s: %v", name, err)
	}
	return b
}

func assertTypedGolden(t *testing.T, name string, v any) {
	t.Helper()
	got, err := json.Marshal(v)
	if err != nil {
		t.Fatalf("%s: marshal: %v", name, err)
	}
	if err := orderedEqual(readGolden(t, name), got); err != nil {
		t.Errorf("%s: %v\ngo: %s", name, err, got)
	}
}

func ptr[T any](v T) *T { return &v }

func TestGolden_events_player_event(t *testing.T) {
	for name, ev := range map[string]PlayerEvent{
		"buffering":          Buffering{Active: true},
		"duration":           Duration{Seconds: 6000},
		"end_file_eof":       EndFile{Reason: EndEOF},
		"end_file_error":     EndFile{Reason: EndError, Error: ptr("loading failed")},
		"end_file_stopped":   EndFile{Reason: EndStopped},
		"file_loaded":        FileLoaded{},
		"paused":             Paused{Paused: true},
		"seeked":             Seeked{},
		"shutdown":           Shutdown{},
		"time_pos":           TimePos{Seconds: 1831.25},
		"video_reconfigured": VideoReconfigured{},
		"tracks": Tracks{Tracks: List[Track]{
			{ID: 1, Kind: KindVideo, Codec: ptr("hevc"), Selected: true, Default: true},
			{ID: 1, Kind: KindAudio, Title: ptr("Français 5.1"), Lang: ptr("fre"), Codec: ptr("ac3"), Selected: true, Default: true},
			{ID: 2, Kind: KindSub, Title: ptr("Forcés"), Lang: ptr("fre"), Codec: ptr("subrip"), External: true},
		}},
	} {
		// Through the interface, as the sink hands it to the emitter.
		assertTypedGolden(t, "events.player_event."+name+".json", []PlayerEvent{ev}[0])
	}
}

func TestGolden_events_end_reason_all(t *testing.T) {
	assertTypedGolden(t, "events.end_reason.all.json", [5]EndReason{EndEOF, EndStopped, EndQuit, EndError, EndOther})
}

func TestGolden_errors_player_error_all_kinds(t *testing.T) {
	assertTypedGolden(t, "errors.player_error.all_kinds.json", []*PlayerError{
		newError(LibraryUnavailable, "libmpv-2.dll"),
		newError(UnsupportedAPI, "1.0"),
		invalidSource("schéma non pris en charge"),
		newError(CertificateStore, "accès refusé"),
		mpvError("loadfile", "unsupported format"),
		ErrNotOpen,
	})
}

// token is one JSON token; numbers keep their text.
type token struct {
	delim  json.Delim
	isKey  bool
	value  any
	isNum  bool
	numTxt string
}

// frame tracks whether the next string inside an object is a key.
type frame struct {
	object bool
	expect bool // expecting a key
}

func tokens(data []byte) ([]token, error) {
	dec := json.NewDecoder(bytes.NewReader(data))
	dec.UseNumber()
	var out []token
	var stack []frame
	for {
		tk, err := dec.Token()
		if errors.Is(err, io.EOF) {
			return out, nil
		}
		if err != nil {
			return nil, err
		}
		isKey := false
		if n := len(stack); n > 0 && stack[n-1].object {
			if stack[n-1].expect {
				if _, ok := tk.(json.Delim); !ok {
					isKey = true
				}
			}
		}
		switch v := tk.(type) {
		case json.Delim:
			switch v {
			case '{':
				markValue(stack)
				stack = append(stack, frame{object: true, expect: true})
			case '[':
				markValue(stack)
				stack = append(stack, frame{})
			default:
				stack = stack[:len(stack)-1]
			}
			out = append(out, token{delim: v})
		case json.Number:
			markValue(stack)
			out = append(out, token{isNum: true, numTxt: string(v)})
		default:
			if isKey {
				stack[len(stack)-1].expect = false
			} else {
				markValue(stack)
			}
			out = append(out, token{isKey: isKey, value: v})
		}
	}
}

// markValue records that a value was read in the current object: a key comes next.
func markValue(stack []frame) {
	if n := len(stack); n > 0 && stack[n-1].object {
		stack[n-1].expect = true
	}
}

func sameNumber(a, b string) bool {
	if a == b {
		return true
	}
	x, ok1 := new(big.Float).SetPrec(200).SetString(a)
	y, ok2 := new(big.Float).SetPrec(200).SetString(b)
	return ok1 && ok2 && x.Cmp(y) == 0
}

// orderedEqual compares two JSON texts token by token: key order matters.
func orderedEqual(want, got []byte) error {
	w, err := tokens(want)
	if err != nil {
		return fmt.Errorf("golden: %w", err)
	}
	g, err := tokens(got)
	if err != nil {
		return fmt.Errorf("go: %w", err)
	}
	for i := 0; i < len(w) || i < len(g); i++ {
		if i >= len(w) || i >= len(g) {
			return fmt.Errorf("length differs at token %d (golden %d tokens, go %d)", i, len(w), len(g))
		}
		a, b := w[i], g[i]
		switch {
		case a.isNum || b.isNum:
			if !(a.isNum && b.isNum && sameNumber(a.numTxt, b.numTxt)) {
				return fmt.Errorf("token %d: golden %v%s, go %v%s", i, a.value, a.numTxt, b.value, b.numTxt)
			}
		case a.delim != 0 || b.delim != 0:
			if a.delim != b.delim {
				return fmt.Errorf("token %d: golden %q, go %q (%v)", i, a.delim, b.delim, b.value)
			}
		default:
			if a.isKey != b.isKey || a.value != b.value {
				return fmt.Errorf("token %d: golden %#v, go %#v", i, a.value, b.value)
			}
		}
	}
	return nil
}
