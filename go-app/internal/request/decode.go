package request

// Strict decoding with the rules of serde_json + `#[derive(Deserialize)]`, for the
// server's answers, the settings file and the command arguments.
//
// Local copy, adapted, of the unexported decodeStrict of internal/emby (decode.go):
// that one is not exported, and it has neither embedded structs (`#[serde(flatten)]`)
// nor `skip_deserializing` (MAP.md, unité request). Rules:
// TODO(dry): use emby.DecodeStrict once exported.
//   - keys match the `json` tag exactly; unknown keys are ignored;
//   - a key twice in one object is refused (`duplicate field`);
//   - a non-pointer field is required (a non-`Option` field of serde) unless tagged
//     `ur:"default"` (`#[serde(default)]`); a missing pointer is nil (`Option` → None);
//   - null is nil for a pointer; it is refused for any other type;
//   - a field tagged `ur:"skip"` (`skip_deserializing`) is never read: its key is
//     ignored like an unknown one;
//   - an embedded struct without a `json` tag is flattened (`#[serde(flatten)]`);
//   - types with their own UnmarshalJSON decode themselves;
//   - trailing data after the value is refused.

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"reflect"
	"strings"
)

var unmarshalerType = reflect.TypeFor[json.Unmarshaler]()

// decodeStrict decodes data into v, a non-nil pointer.
func decodeStrict(data []byte, v any) error {
	rv := reflect.ValueOf(v)
	if rv.Kind() != reflect.Pointer || rv.IsNil() {
		return errors.New("decodeStrict: pointeur attendu")
	}
	if !json.Valid(data) {
		var discard any
		if err := json.Unmarshal(data, &discard); err != nil {
			return err
		}
		return errors.New("JSON invalide")
	}
	return decodeValue(bytes.TrimSpace(data), rv.Elem(), "")
}

func isNull(raw []byte) bool { return bytes.Equal(raw, []byte("null")) }

func at(path string) string {
	if path == "" {
		return ""
	}
	return " (" + path + ")"
}

func decodeValue(raw []byte, rv reflect.Value, path string) error {
	t := rv.Type()
	if t.Kind() != reflect.Pointer && reflect.PointerTo(t).Implements(unmarshalerType) {
		return json.Unmarshal(raw, rv.Addr().Interface())
	}
	if t.Kind() == reflect.Pointer {
		if isNull(raw) {
			rv.SetZero()
			return nil
		}
		elem := reflect.New(t.Elem())
		if err := decodeValue(raw, elem.Elem(), path); err != nil {
			return err
		}
		rv.Set(elem)
		return nil
	}
	if isNull(raw) {
		return fmt.Errorf("invalid type: null, expected %s%s", t, at(path))
	}
	switch t.Kind() {
	case reflect.Struct:
		return decodeStruct(raw, rv, path)
	case reflect.Slice:
		var items []json.RawMessage
		if err := json.Unmarshal(raw, &items); err != nil {
			return fmt.Errorf("%w%s", err, at(path))
		}
		out := reflect.MakeSlice(t, len(items), len(items))
		for i, item := range items {
			if err := decodeValue(bytes.TrimSpace(item), out.Index(i), fmt.Sprintf("%s[%d]", path, i)); err != nil {
				return err
			}
		}
		rv.Set(out)
		return nil
	default:
		if err := json.Unmarshal(raw, rv.Addr().Interface()); err != nil {
			return fmt.Errorf("%w%s", err, at(path))
		}
		return nil
	}
}

type fieldInfo struct {
	index    []int
	required bool
}

// structFields maps each JSON key of t to its field, embedded structs flattened.
// skip fields are left out: their key is ignored.
func structFields(t reflect.Type, prefix []int, fields map[string]fieldInfo) {
	for i := range t.NumField() {
		f := t.Field(i)
		tagName, _, _ := strings.Cut(f.Tag.Get("json"), ",")
		index := append(append([]int(nil), prefix...), i)
		if f.Anonymous && tagName == "" && f.Type.Kind() == reflect.Struct {
			structFields(f.Type, index, fields)
			continue
		}
		if !f.IsExported() || tagName == "-" {
			continue
		}
		tag := f.Tag.Get("ur")
		if tag == "skip" {
			continue
		}
		name := tagName
		if name == "" {
			name = f.Name
		}
		required := f.Type.Kind() != reflect.Pointer && tag != "default"
		fields[name] = fieldInfo{index: index, required: required}
	}
}

func decodeStruct(raw []byte, rv reflect.Value, path string) error {
	t := rv.Type()
	dec := json.NewDecoder(bytes.NewReader(raw))
	tok, err := dec.Token()
	if err != nil {
		return err
	}
	if d, ok := tok.(json.Delim); !ok || d != '{' {
		return fmt.Errorf("invalid type: %s, expected struct %s%s", raw, t.Name(), at(path))
	}
	fields := make(map[string]fieldInfo)
	structFields(t, nil, fields)
	seen := make(map[string]bool, len(fields))
	out := reflect.New(t).Elem()
	for dec.More() {
		keyTok, err := dec.Token()
		if err != nil {
			return err
		}
		key, _ := keyTok.(string)
		var val json.RawMessage
		if err := dec.Decode(&val); err != nil {
			return err
		}
		f, known := fields[key]
		if !known {
			continue
		}
		if seen[key] {
			return fmt.Errorf("duplicate field `%s`%s", key, at(path))
		}
		seen[key] = true
		if err := decodeValue(bytes.TrimSpace(val), out.FieldByIndex(f.index), path+"."+key); err != nil {
			return err
		}
	}
	for name, f := range fields {
		if f.required && !seen[name] {
			return fmt.Errorf("missing field `%s`%s", name, at(path))
		}
	}
	rv.Set(out)
	return nil
}

// decodeEnum reads a unit variant of a serde enum: one of names, exactly.
func decodeEnum(b []byte, dst *string, what string, names ...string) error {
	var s string
	if isNull(bytes.TrimSpace(b)) {
		return fmt.Errorf("invalid type: null, expected %s", what)
	}
	if err := json.Unmarshal(b, &s); err != nil {
		return fmt.Errorf("%s : %w", what, err)
	}
	for _, n := range names {
		if s == n {
			*dst = s
			return nil
		}
	}
	return fmt.Errorf("unknown variant `%s`, expected one of %s", s, strings.Join(names, ", "))
}
