// Package libraryevents is the session socket, port of
// src-tauri/src/emby/library_events.rs. While signed in, one WebSocket to the Emby
// server: it declares the session's capabilities on each connect, carries the
// linked playback's `SendString` messages and, while the home is shown,
// `LibraryChanged`, so a title added on the server reaches « Ajoutés récemment »
// at once. The UI only receives an event without payload; the socket URL carries
// the token and is never logged.
//
// It lives outside internal/emby because it feeds internal/devsync, which
// imports emby (MAP.md, « Unité sync-core »).
package libraryevents

import (
	"encoding/json"
	"sync/atomic"
	"time"

	"viewer/internal/strictjson"
)

// LibraryChangedEvent is emitted (no payload) once a burst of library changes
// has settled.
const LibraryChangedEvent = "emby://library-changed"

const (
	// Settle: a scan sends changes in bursts, posters often in a later
	// `ItemsUpdated`: the home is read again once nothing came for this long.
	Settle = 2 * time.Second
	// KeepAlive is sent so idle sockets are not closed by the server or a proxy.
	KeepAlive = 30 * time.Second
	// sessionCheck: how often the socket checks the session it was opened for is
	// still the one.
	sessionCheck = time.Second
	// maxMessage is tungstenite's default `max_message_size` (64 MiB).
	maxMessage = 64 << 20
)

// Backoff is the delay before reconnect attempt `attempt` (0-based): 2 s, 5 s,
// 15 s, then 30 s.
func Backoff(attempt uint32) time.Duration {
	switch attempt {
	case 0:
		return 2 * time.Second
	case 1:
		return 5 * time.Second
	case 2:
		return 15 * time.Second
	default:
		return 30 * time.Second
	}
}

// socketMessage is `SocketMessage`: `MessageType` required, `Data` an
// `Option<Value>` (missing or null is None).
type socketMessage struct {
	MessageType string          `json:"MessageType" serde:"required"`
	Data        json.RawMessage `json:"Data"`
}

var rules = strictjson.Rules{Tag: "serde"}

func decodeMessage(text string) (socketMessage, bool) {
	var msg socketMessage
	if err := rules.Decode([]byte(text), &msg); err != nil {
		return socketMessage{}, false
	}
	return msg, true
}

// IsLibraryChange is `is_library_change`: true for a `LibraryChanged` frame that
// adds, updates or removes items. Anything else (user data, sessions, malformed
// text) is ignored.
func IsLibraryChange(text string) bool {
	msg, ok := decodeMessage(text)
	if !ok || msg.MessageType != "LibraryChanged" {
		return false
	}
	added, updated, removed := libraryChange(msg.Data)
	return added || updated || removed
}

// libraryChange is `from_value::<LibraryChange>(d).unwrap_or_default()`: the
// three lists default to empty, and any error (Data not an object, a list that
// is null or not an array) gives the default, so no change. Data is already a
// serde_json::Value in Rust, whose map keeps the last of duplicate keys: a plain
// map decode does the same.
func libraryChange(data json.RawMessage) (added, updated, removed bool) {
	if data == nil {
		return false, false, false
	}
	var fields map[string]json.RawMessage
	if err := json.Unmarshal(data, &fields); err != nil || fields == nil {
		return false, false, false
	}
	nonEmpty := make([]bool, 3)
	for i, key := range []string{"ItemsAdded", "ItemsUpdated", "ItemsRemoved"} {
		raw, ok := fields[key]
		if !ok {
			continue
		}
		var list []json.RawMessage
		if strictjson.IsNull(raw) || json.Unmarshal(raw, &list) != nil {
			return false, false, false
		}
		nonEmpty[i] = len(list) > 0
	}
	return nonEmpty[0], nonEmpty[1], nonEmpty[2]
}

// SendStringOf is `send_string_of`: the `String` of a `SendString` general
// command, the relay of linked playback; false for anything else.
func SendStringOf(text string) (string, bool) {
	msg, ok := decodeMessage(text)
	if !ok || msg.MessageType != "GeneralCommand" || msg.Data == nil {
		return "", false
	}
	var data map[string]json.RawMessage
	if json.Unmarshal(msg.Data, &data) != nil {
		return "", false
	}
	if name, ok := asStr(data["Name"]); !ok || name != "SendString" {
		return "", false
	}
	var arguments map[string]json.RawMessage
	if raw, ok := data["Arguments"]; !ok || json.Unmarshal(raw, &arguments) != nil {
		return "", false
	}
	return asStr(arguments["String"])
}

// asStr is serde_json's `Value::as_str`: a JSON string, nothing else.
func asStr(raw json.RawMessage) (string, bool) {
	if len(raw) == 0 || raw[0] != '"' {
		return "", false
	}
	var s string
	if json.Unmarshal(raw, &s) != nil {
		return "", false
	}
	return s, true
}

// Events is `LibraryEvents`: whether the home is shown, since `LibraryChanged`
// only matters then. The zero value is ready (home not shown).
type Events struct {
	homeShown atomic.Bool
}

// Start is `emby_library_events_start`.
func (e *Events) Start() { e.homeShown.Store(true) }

// Stop is `emby_library_events_stop`.
func (e *Events) Stop() { e.homeShown.Store(false) }

// HomeShown reports whether the home listens for library changes.
func (e *Events) HomeShown() bool { return e.homeShown.Load() }
