// Package strictjson decodes JSON with the rules of serde_json + `#[derive(Deserialize)]`
// (research/http-client.md), for the Emby answers, the Request answers, the settings
// files and the command arguments. encoding/json v1 matches keys without case, reads
// null as the zero value and accepts missing fields; serde does none of that. Rules:
//   - keys match the `json` tag exactly; unknown keys are ignored;
//   - a key twice in one object is refused (`duplicate field`);
//   - a field is required when tagged `required` (Rules.Tag), or, with
//     RequiredByDefault, when it is not a pointer and not tagged `default`
//     (`#[serde(default)]`); any other missing field keeps its zero value;
//   - null is nil for a pointer or a json.RawMessage (`Option<T>`, `Option<Value>`),
//     and the zero value for a field tagged `option` (an `Option<Vec>` or
//     `Option<HashMap>` kept as a plain slice or map); it is refused for any other type;
//   - a field tagged `skip` (`skip_deserializing`) is never read: its key is ignored
//     like an unknown one;
//   - an embedded struct without a `json` tag is flattened (`#[serde(flatten)]`);
//   - types with their own UnmarshalJSON decode themselves;
//   - trailing data after the value is refused.
//
// Not reproduced: serde also accepts a struct written as a JSON array (fields by
// position); nothing here answers that way.
package strictjson

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"reflect"
	"strings"
)

// Rules tells where the options of a field are written and which fields are required.
type Rules struct {
	// Tag is the struct tag key carrying `required`, `option`, `default` or `skip`.
	Tag string
	// RequiredByDefault makes every non-pointer field required unless tagged `default`.
	RequiredByDefault bool
}

var (
	rawMessageType  = reflect.TypeFor[json.RawMessage]()
	unmarshalerType = reflect.TypeFor[json.Unmarshaler]()
)

// Decode decodes data into v, a non-nil pointer.
func (r Rules) Decode(data []byte, v any) error {
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
	return r.decodeValue(bytes.TrimSpace(data), rv.Elem(), "")
}

// IsNull tells whether raw, a trimmed JSON value, is null.
func IsNull(raw []byte) bool { return bytes.Equal(raw, []byte("null")) }

func at(path string) string {
	if path == "" {
		return ""
	}
	return " (" + path + ")"
}

func (r Rules) decodeValue(raw []byte, rv reflect.Value, path string) error {
	t := rv.Type()
	if t == rawMessageType {
		if IsNull(raw) {
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
		if IsNull(raw) {
			rv.SetZero()
			return nil
		}
		elem := reflect.New(t.Elem())
		if err := r.decodeValue(raw, elem.Elem(), path); err != nil {
			return err
		}
		rv.Set(elem)
		return nil
	}
	if IsNull(raw) {
		return fmt.Errorf("invalid type: null, expected %s%s", t, at(path))
	}
	switch t.Kind() {
	case reflect.Struct:
		return r.decodeStruct(raw, rv, path)
	case reflect.Slice:
		var items []json.RawMessage
		if err := json.Unmarshal(raw, &items); err != nil {
			return fmt.Errorf("%w%s", err, at(path))
		}
		out := reflect.MakeSlice(t, len(items), len(items))
		for i, item := range items {
			if err := r.decodeValue(bytes.TrimSpace(item), out.Index(i), fmt.Sprintf("%s[%d]", path, i)); err != nil {
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
			if err := r.decodeValue(bytes.TrimSpace(item), val, path+"."+k); err != nil {
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
	index    []int
	required bool
	// option: null is the zero value (`Option` mapped to a non-pointer type).
	option bool
}

// structFields maps each JSON key of t to its field, embedded structs flattened.
// skip fields are left out: their key is ignored.
func (r Rules) structFields(t reflect.Type, prefix []int, fields map[string]fieldInfo) {
	for i := range t.NumField() {
		f := t.Field(i)
		tagName, _, _ := strings.Cut(f.Tag.Get("json"), ",")
		index := append(append([]int(nil), prefix...), i)
		if f.Anonymous && tagName == "" && f.Type.Kind() == reflect.Struct {
			r.structFields(f.Type, index, fields)
			continue
		}
		if !f.IsExported() || tagName == "-" {
			continue
		}
		tag := f.Tag.Get(r.Tag)
		if tag == "skip" {
			continue
		}
		name := tagName
		if name == "" {
			name = f.Name
		}
		required := tag == "required"
		if r.RequiredByDefault {
			required = f.Type.Kind() != reflect.Pointer && tag != "default"
		}
		fields[name] = fieldInfo{index: index, required: required, option: tag == "option"}
	}
}

func (r Rules) decodeStruct(raw []byte, rv reflect.Value, path string) error {
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
	r.structFields(t, nil, fields)
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
		if f.option && IsNull(trimmed) {
			continue
		}
		if err := r.decodeValue(trimmed, out.FieldByIndex(f.index), path+"."+key); err != nil {
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
