package devsync

import (
	"strings"

	"viewer/internal/emby"
)

// TVClient is the `Client` of the Android TV app's sessions.
const TVClient = "Viewer TV"

// SyncDevice is a TV the playback can be sent to. The name comes from the
// server's list.
type SyncDevice struct {
	SessionID string `json:"sessionId"`
	Name      string `json:"name"`
}

// TVDevices lists the open TV sessions of the user, other than this device's.
// The result is written `[]` when empty, like the Rust Vec.
func TVDevices(sessions []emby.SessionInfoDto, ownDeviceID string) emby.List[SyncDevice] {
	var out emby.List[SyncDevice]
	for _, s := range sessions {
		if !is(s.Client, TVClient) || !s.SupportsRemoteControl || is(s.DeviceID, ownDeviceID) || !IsID(s.ID, 1) {
			continue
		}
		name := "TV"
		if s.DeviceName != nil && strings.TrimSpace(*s.DeviceName) != "" {
			name = *s.DeviceName
		}
		out = append(out, SyncDevice{SessionID: s.ID, Name: name})
	}
	return out
}

// OwnSessionID is the id of this device's own session, the `from` of what it
// sends; ok is false when the server does not list it.
func OwnSessionID(sessions []emby.SessionInfoDto, ownDeviceID string) (id string, ok bool) {
	for _, s := range sessions {
		if is(s.DeviceID, ownDeviceID) && IsID(s.ID, 1) {
			return s.ID, true
		}
	}
	return "", false
}

// is is Rust's `opt.as_deref() == Some(want)`: a value comparison, never nil.
func is(p *string, want string) bool { return p != nil && *p == want }
