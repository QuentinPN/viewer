package devsync

import (
	"encoding/json"
	"reflect"
	"testing"

	"viewer/internal/emby"
)

// Port of the tests of src-tauri/src/sync/mod.rs.

func sessions(t *testing.T) []emby.SessionInfoDto {
	t.Helper()
	// Shape of `GET /Sessions?ControllableByUserId=` on Emby 4.10.2.0 (2026-11-14).
	raw := `[
		{"Id":"own1","DeviceId":"dev-pc","DeviceName":"PC","Client":"Viewer","SupportsRemoteControl":true},
		{"Id":"tv1","DeviceId":"dev-tv","DeviceName":"Shield salon","Client":"Viewer TV","SupportsRemoteControl":true},
		{"Id":"tv2","DeviceId":"dev-tv2","DeviceName":"Chambre","Client":"Viewer TV","SupportsRemoteControl":false},
		{"Id":"web","DeviceId":"dev-web","DeviceName":"Firefox","Client":"Emby Web","SupportsRemoteControl":true},
		{"Id":"bad id","DeviceId":"dev-x","DeviceName":"X","Client":"Viewer TV","SupportsRemoteControl":true}
	]`
	var s []emby.SessionInfoDto
	if err := json.Unmarshal([]byte(raw), &s); err != nil {
		t.Fatal(err)
	}
	return s
}

func Test_devices_are_remote_controllable_tv_sessions(t *testing.T) {
	got := TVDevices(sessions(t), "dev-pc")
	want := emby.List[SyncDevice]{{SessionID: "tv1", Name: "Shield salon"}}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("got %+v, want %+v", got, want)
	}
	// Our own session never lists itself, even under the TV client name.
	if got := TVDevices(sessions(t), "dev-tv"); len(got) != 0 {
		t.Fatalf("own session listed: %+v", got)
	}
}

func Test_own_session_is_found_by_device_id(t *testing.T) {
	if id, ok := OwnSessionID(sessions(t), "dev-pc"); !ok || id != "own1" {
		t.Fatalf("got %q %v", id, ok)
	}
	if id, ok := OwnSessionID(sessions(t), "nope"); ok {
		t.Fatalf("got %q", id)
	}
}

// ---------------------------------------------------------- Go-only tests

func Test_go_a_tv_without_a_name_is_called_tv(t *testing.T) {
	blank, name := " \t ", "Salon"
	client := TVClient
	s := []emby.SessionInfoDto{
		{ID: "a", Client: &client, SupportsRemoteControl: true},
		{ID: "b", Client: &client, SupportsRemoteControl: true, DeviceName: &blank},
		{ID: "c", Client: &client, SupportsRemoteControl: true, DeviceName: &name},
	}
	got := TVDevices(s, "dev-pc")
	want := emby.List[SyncDevice]{{"a", "TV"}, {"b", "TV"}, {"c", "Salon"}}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("got %+v", got)
	}
	// A session without a device id is never our own.
	if _, ok := OwnSessionID(s, ""); ok {
		t.Fatal("a session without DeviceId matched")
	}
}

func Test_go_no_tv_is_written_as_an_empty_list(t *testing.T) {
	b, err := json.Marshal(TVDevices(nil, "dev-pc"))
	if err != nil || string(b) != "[]" {
		t.Fatalf("%s %v", b, err)
	}
}
