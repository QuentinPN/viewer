package emby

// Strict decoding of Emby answers: the serde rules of internal/strictjson, where a
// field is required when tagged `emby:"required"` (a non-`Option` without
// `#[serde(default)]`) and `emby:"option"` reads null as the zero value.

import "viewer/internal/strictjson"

var embyRules = strictjson.Rules{Tag: "emby"}

// decodeStrict decodes data into v, a non-nil pointer.
func decodeStrict(data []byte, v any) error { return embyRules.Decode(data, v) }
