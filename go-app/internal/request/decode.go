package request

// Strict decoding of the server's answers, the settings file and the command
// arguments: the serde rules of internal/strictjson, where a non-pointer field is
// required (a non-`Option` field of serde) unless tagged `ur:"default"`
// (`#[serde(default)]`), and `ur:"skip"` is `skip_deserializing`.

import (
	"bytes"
	"encoding/json"
	"fmt"
	"strings"

	"viewer/internal/strictjson"
)

var urRules = strictjson.Rules{Tag: "ur", RequiredByDefault: true}

// decodeStrict decodes data into v, a non-nil pointer.
func decodeStrict(data []byte, v any) error { return urRules.Decode(data, v) }

// decodeEnum reads a unit variant of a serde enum: one of names, exactly.
func decodeEnum(b []byte, dst *string, what string, names ...string) error {
	var s string
	if strictjson.IsNull(bytes.TrimSpace(b)) {
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
