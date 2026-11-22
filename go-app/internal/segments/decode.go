package segments

// Strict reading of the two answers, as serde_json and `#[derive(Deserialize)]` read
// them in providers.rs: exact-case keys, unknown keys ignored, a known key twice
// refused, null refused where the Rust type is not an Option, a `f64` from any JSON
// number in range, an `i64` from an integer literal only. A lax read would turn an
// unreadable (transient) answer into a definitive one, which is cached.
// Not reproduced: serde also reads a struct written as a JSON array (fields by
// position) and refuses invalid UTF-8 in strings; neither service answers that way.

import (
	"bytes"
	"encoding/json"
	"strconv"
	"strings"
)

func isNull(raw json.RawMessage) bool { return bytes.Equal(bytes.TrimSpace(raw), []byte("null")) }

// object reads one JSON object and returns its known fields; false when raw is not an
// object or holds a known key twice.
func object(raw []byte, known ...string) (map[string]json.RawMessage, bool) {
	if !json.Valid(raw) {
		return nil, false
	}
	dec := json.NewDecoder(bytes.NewReader(raw))
	tok, err := dec.Token()
	if d, ok := tok.(json.Delim); err != nil || !ok || d != '{' {
		return nil, false
	}
	out := map[string]json.RawMessage{}
	for dec.More() {
		keyTok, err := dec.Token()
		if err != nil {
			return nil, false
		}
		key, _ := keyTok.(string)
		var val json.RawMessage
		if err := dec.Decode(&val); err != nil {
			return nil, false
		}
		isKnown := false
		for _, k := range known {
			isKnown = isKnown || k == key
		}
		if !isKnown {
			continue
		}
		if _, twice := out[key]; twice {
			return nil, false
		}
		out[key] = val
	}
	return out, true
}

// array reads a JSON array (a `Vec`: null refused).
func array(raw json.RawMessage) ([]json.RawMessage, bool) {
	var items []json.RawMessage
	if isNull(raw) || json.Unmarshal(raw, &items) != nil {
		return nil, false
	}
	return items, true
}

// numberText is the literal of a JSON number, false for any other value.
func numberText(raw json.RawMessage) (string, bool) {
	t := string(bytes.TrimSpace(raw))
	if t == "" || (t[0] != '-' && (t[0] < '0' || t[0] > '9')) {
		return "", false
	}
	return t, true
}

// f64 is a required `f64`.
func f64(raw json.RawMessage) (float64, bool) {
	t, ok := numberText(raw)
	if !ok {
		return 0, false
	}
	v, err := strconv.ParseFloat(t, 64)
	return v, err == nil
}

// optI64 is an `Option<i64>` with `#[serde(default)]`: missing or null is none.
func optI64(fields map[string]json.RawMessage, name string) (*int64, bool) {
	raw, ok := fields[name]
	if !ok || isNull(raw) {
		return nil, true
	}
	t, ok := numberText(raw)
	if !ok || strings.ContainsAny(t, ".eE") {
		return nil, false
	}
	v, err := strconv.ParseInt(t, 10, 64)
	if err != nil {
		return nil, false
	}
	return &v, true
}

// boolDefault is a `bool` with `#[serde(default)]`: missing is false, null refused.
func boolDefault(fields map[string]json.RawMessage, name string) (bool, bool) {
	raw, ok := fields[name]
	if !ok {
		return false, true
	}
	switch string(bytes.TrimSpace(raw)) {
	case "true":
		return true, true
	case "false":
		return false, true
	}
	return false, false
}
