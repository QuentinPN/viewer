package emby

// Golden helpers. Two comparison modes, per testdata/golden/index.json:
//   - typed values: same keys in the same order, same values; numbers compare by
//     value, because serde writes `0.0` where encoding/json writes `0`;
//   - tables built with json! in golden.rs: same JSON value, key order ignored.
// Values are marshaled with encoding/json v1, the encoder Wails v3.1.0 uses for
// bound-method results (pkg/application/bindings.go).

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math/big"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
)

const goldenDir = "../../testdata/golden"
const fixturesDir = "../../testdata/fixtures"

func readGolden(t *testing.T, name string) []byte {
	t.Helper()
	b, err := os.ReadFile(filepath.Join(goldenDir, name))
	if err != nil {
		t.Fatalf("golden %s: %v", name, err)
	}
	return b
}

func readFixture(t *testing.T, name string) []byte {
	t.Helper()
	b, err := os.ReadFile(filepath.Join(fixturesDir, name))
	if err != nil {
		t.Fatalf("fixture %s: %v", name, err)
	}
	return b
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

// normalize turns decoded JSON into comparable values (numbers as big.Float text).
func normalize(v any) any {
	switch x := v.(type) {
	case map[string]any:
		for k, e := range x {
			x[k] = normalize(e)
		}
		return x
	case []any:
		for i, e := range x {
			x[i] = normalize(e)
		}
		return x
	case json.Number:
		f, _ := new(big.Float).SetPrec(200).SetString(string(x))
		return f.Text('g', 60)
	default:
		return v
	}
}

func decodeAny(data []byte) (any, error) {
	dec := json.NewDecoder(bytes.NewReader(data))
	dec.UseNumber()
	var v any
	if err := dec.Decode(&v); err != nil {
		return nil, err
	}
	return normalize(v), nil
}

func marshal(t *testing.T, v any) []byte {
	t.Helper()
	b, err := json.Marshal(v)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	return b
}

// assertTypedGolden: the Go value serializes like the typed Rust value (key order kept).
func assertTypedGolden(t *testing.T, name string, v any) {
	t.Helper()
	got := marshal(t, v)
	if err := orderedEqual(readGolden(t, name), got); err != nil {
		t.Errorf("%s: %v\ngo: %s", name, err, got)
	}
}

// assertTableGolden: the Go value is the same JSON value as the json! table.
func assertTableGolden(t *testing.T, name string, v any) {
	t.Helper()
	got := marshal(t, v)
	assertSameJSON(t, name, readGolden(t, name), got)
}

func assertSameJSON(t *testing.T, name string, want, got []byte) {
	t.Helper()
	w, err := decodeAny(want)
	if err != nil {
		t.Fatalf("%s golden: %v", name, err)
	}
	g, err := decodeAny(got)
	if err != nil {
		t.Fatalf("%s go: %v", name, err)
	}
	if !reflect.DeepEqual(w, g) {
		t.Errorf("%s differs\ngolden: %s\ngo:     %s", name, compact(want), got)
	}
}

func compact(b []byte) string {
	var buf bytes.Buffer
	if err := json.Compact(&buf, b); err != nil {
		return string(b)
	}
	return buf.String()
}

// roundTripGolden decodes a typed golden into ptr (unknown keys refused), then
// checks that it serializes back with the same keys, order and values. It proves the
// Go type's shape (names, order, null vs present); not the code that fills it.
func roundTripGolden(t *testing.T, name string, ptr any) {
	t.Helper()
	data := readGolden(t, name)
	dec := json.NewDecoder(bytes.NewReader(data))
	dec.DisallowUnknownFields()
	if err := dec.Decode(ptr); err != nil {
		t.Fatalf("%s: decode into %T: %v", name, ptr, err)
	}
	got := marshal(t, reflect.ValueOf(ptr).Elem().Interface())
	if err := orderedEqual(data, got); err != nil {
		t.Errorf("%s: %v\ngo: %s", name, err, got)
	}
}

func TestGoldenHelpersCompareOrderAndNumbers(t *testing.T) {
	if err := orderedEqual([]byte(`{"a":0.0,"b":[1,2]}`), []byte(`{"a":0,"b":[1,2]}`)); err != nil {
		t.Fatal(err)
	}
	if orderedEqual([]byte(`{"a":1,"b":2}`), []byte(`{"b":2,"a":1}`)) == nil {
		t.Fatal("key order must matter")
	}
	if orderedEqual([]byte(`{"a":null}`), []byte(`{}`)) == nil {
		t.Fatal("null and absent must differ")
	}
	if orderedEqual([]byte(`["a"]`), []byte(`[{"a":1}]`)) == nil {
		t.Fatal("a string value is not a key")
	}
	if !strings.Contains(compact([]byte("{ \"a\" : 1 }")), `{"a":1}`) {
		t.Fatal("compact")
	}
}
