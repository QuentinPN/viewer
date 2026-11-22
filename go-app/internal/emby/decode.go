package emby

// Strict decoding of Emby answers, the rules of serde_json + `#[derive(Deserialize)]`
// that client.rs relies on (research/http-client.md). encoding/json v1 matches keys
// without case, reads null as the zero value and accepts missing fields; serde does
// none of that. Rules here:
//   - keys match the `json` tag exactly; unknown keys are ignored;
//   - a key twice in one object is refused (`duplicate field`);
//   - a field tagged `emby:"required"` (non-`Option` without `#[serde(default)]`) must
//     be present; any other missing field keeps its zero value (the serde default);
//   - null is nil for a pointer or a json.RawMessage (`Option<T>`, `Option<Value>`),
//     and the zero value for a field tagged `emby:"option"` (an `Option<Vec>` or
//     `Option<HashMap>` kept as a plain slice or map); it is refused for any other type;
//   - types with their own UnmarshalJSON decode themselves;
//   - trailing data after the value is refused.
// Not reproduced: serde also accepts a struct written as a JSON array (fields by
// position); Emby never answers that way.

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"reflect"
	"strings"
)

var (
	rawMessageType  = reflect.TypeFor[json.RawMessage]()
	unmarshalerType = reflect.TypeFor[json.Unmarshaler]()
)

// decodeStrict decodes data into v, a non-nil pointer.
func decodeStrict(data []byte, v any) error {
	rv := reflect.ValueOf(v)
	if rv.Kind() != reflect.Pointer || rv.IsNil() {
		return errors.New("decodeStrict: pointeur attendu")
	}
	if !json.Valid(data) {
		// Let encoding/json describe the syntax error.
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
	if t == rawMessageType {
		if isNull(raw) {
			rv.SetBytes(nil)
		} else {
			rv.SetBytes(bytes.Clone(raw))
		}
		return nil
	}
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
	case reflect.Map:
		if t.Key().Kind() != reflect.String {
			return json.Unmarshal(raw, rv.Addr().Interface())
		}
		// A map keeps the last of repeated keys, as serde's HashMap.
		var entries map[string]json.RawMessage
		if err := json.Unmarshal(raw, &entries); err != nil {
			return fmt.Errorf("%w%s", err, at(path))
		}
		out := reflect.MakeMapWithSize(t, len(entries))
		for k, item := range entries {
			val := reflect.New(t.Elem()).Elem()
			if err := decodeValue(bytes.TrimSpace(item), val, path+"."+k); err != nil {
				return err
			}
			out.SetMapIndex(reflect.ValueOf(k).Convert(t.Key()), val)
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
	index    int
	required bool
	// option: null is the zero value (`Option` mapped to a non-pointer type).
	option bool
}

func structFields(t reflect.Type) map[string]fieldInfo {
	fields := make(map[string]fieldInfo, t.NumField())
	for i := range t.NumField() {
		f := t.Field(i)
		if !f.IsExported() {
			continue
		}
		name, _, _ := strings.Cut(f.Tag.Get("json"), ",")
		if name == "-" {
			continue
		}
		if name == "" {
			name = f.Name
		}
		tag := f.Tag.Get("emby")
		fields[name] = fieldInfo{index: i, required: tag == "required", option: tag == "option"}
	}
	return fields
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
	fields := structFields(t)
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
		trimmed := bytes.TrimSpace(val)
		if f.option && isNull(trimmed) {
			continue
		}
		if err := decodeValue(bytes.TrimSpace(val), out.Field(f.index), path+"."+key); err != nil {
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
