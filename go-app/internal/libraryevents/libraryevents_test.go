package libraryevents

import (
	"testing"

	"viewer/internal/emby"
)

// Ports of the tests of src-tauri/src/emby/library_events.rs, same names.

func Test_added_items_are_a_change(t *testing.T) {
	text := `{"MessageType":"LibraryChanged","Data":{"ItemsAdded":["1","2"],"ItemsUpdated":[],"ItemsRemoved":[],"FoldersAddedTo":["9"]}}`
	if !IsLibraryChange(text) {
		t.Fatal("ajout non vu")
	}
}

func Test_updated_or_removed_items_are_a_change(t *testing.T) {
	if !IsLibraryChange(`{"MessageType":"LibraryChanged","Data":{"ItemsUpdated":["1"]}}`) {
		t.Fatal("mise à jour non vue")
	}
	if !IsLibraryChange(`{"MessageType":"LibraryChanged","Data":{"ItemsRemoved":["1"]}}`) {
		t.Fatal("suppression non vue")
	}
}

func Test_empty_change_is_ignored(t *testing.T) {
	if IsLibraryChange(`{"MessageType":"LibraryChanged","Data":{"FoldersAddedTo":["9"]}}`) {
		t.Fatal("dossier seul compté")
	}
	if IsLibraryChange(`{"MessageType":"LibraryChanged"}`) {
		t.Fatal("sans Data compté")
	}
}

func Test_other_messages_are_ignored(t *testing.T) {
	// Frame as sent by Emby 4.10.2 on a favourite change (2026-11-13).
	userData := `{"MessageType":"UserDataChanged","MessageId":"582e","Data":{"UserId":"5f82","UserDataList":[{"IsFavorite":true,"ItemId":"68397"}]}}`
	for _, text := range []string{userData, "not json", `{"MessageType":"ForceKeepAlive","Data":60}`} {
		if IsLibraryChange(text) {
			t.Fatalf("%s compté", text)
		}
	}
}

func client(t *testing.T, server string, token bool) *emby.Client {
	t.Helper()
	u, err := emby.ParseServerURL(server)
	if err != nil {
		t.Fatal(err)
	}
	c, err := emby.NewClient(u, emby.DeviceIdentity{Name: "pc", ID: "dev-1", Version: "1.0.0"})
	if err != nil {
		t.Fatal(err)
	}
	if token {
		c = c.WithToken(emby.NewAccessToken("tok"))
	}
	return c
}

func Test_socket_url_follows_the_server_scheme(t *testing.T) {
	for server, want := range map[string]string{
		"http://192.168.1.14:8096":  "ws://192.168.1.14:8096/embywebsocket?api_key=tok&deviceId=dev-1",
		"https://emby.example.com/": "wss://emby.example.com/embywebsocket?api_key=tok&deviceId=dev-1",
	} {
		got, err := client(t, server, true).LibrarySocketURL()
		if err != nil || got != want {
			t.Fatalf("%s : %q, %v", server, got, err)
		}
	}
}

func Test_socket_url_needs_a_session(t *testing.T) {
	if _, err := client(t, "http://192.168.1.14:8096", false).LibrarySocketURL(); err == nil {
		t.Fatal("adresse sans jeton")
	}
}

func Test_send_string_frames_carry_the_relayed_text(t *testing.T) {
	frame := `{"MessageType":"GeneralCommand","Data":{"Name":"SendString","Arguments":{"String":"{\"tv\":\"sync\"}"}}}`
	if got, ok := SendStringOf(frame); !ok || got != `{"tv":"sync"}` {
		t.Fatalf("%q %v", got, ok)
	}
	for _, text := range []string{
		`{"MessageType":"GeneralCommand","Data":{"Name":"DisplayMessage","Arguments":{"String":"x"}}}`,
		`{"MessageType":"GeneralCommand","Data":{"Name":"SendString"}}`,
		`{"MessageType":"LibraryChanged","Data":{}}`,
		"not json",
	} {
		if got, ok := SendStringOf(text); ok {
			t.Fatalf("%s : %q", text, got)
		}
	}
}

func Test_backoff_grows_then_caps(t *testing.T) {
	want := []int{2, 5, 15, 30, 30, 30}
	for a, w := range want {
		if got := Backoff(uint32(a)).Seconds(); got != float64(w) {
			t.Fatalf("essai %d : %v s", a, got)
		}
	}
}

// serde rules the Rust decoder applies and encoding/json would not.
func Test_go_frames_follow_serde_rules(t *testing.T) {
	refused := []string{
		// Key case is exact.
		`{"messageType":"LibraryChanged","Data":{"ItemsAdded":["1"]}}`,
		// A duplicate field of SocketMessage is refused.
		`{"MessageType":"LibraryChanged","MessageType":"LibraryChanged","Data":{"ItemsAdded":["1"]}}`,
		// MessageType is required, and never null.
		`{"Data":{"ItemsAdded":["1"]}}`,
		`{"MessageType":null,"Data":{"ItemsAdded":["1"]}}`,
		// A null or non-array list makes the whole change the default (empty).
		`{"MessageType":"LibraryChanged","Data":{"ItemsAdded":null,"ItemsUpdated":["1"]}}`,
		`{"MessageType":"LibraryChanged","Data":{"ItemsAdded":"1"}}`,
		// Data not an object.
		`{"MessageType":"LibraryChanged","Data":[1]}`,
		// Trailing data.
		`{"MessageType":"LibraryChanged","Data":{"ItemsAdded":["1"]}} x`,
	}
	for _, text := range refused {
		if IsLibraryChange(text) {
			t.Errorf("accepté : %s", text)
		}
	}
	// Data is a serde_json Value: its duplicate keys keep the last one.
	if !IsLibraryChange(`{"MessageType":"LibraryChanged","Data":{"ItemsAdded":[],"ItemsAdded":["1"]}}`) {
		t.Error("dernière clé de Data non retenue")
	}
	if _, ok := SendStringOf(`{"MessageType":"GeneralCommand","Data":{"Name":"SendString","Arguments":{"String":5}}}`); ok {
		t.Error("String non textuelle acceptée")
	}
	if _, ok := SendStringOf(`{"MessageType":"GeneralCommand","Data":null}`); ok {
		t.Error("Data null accepté")
	}
}

func Test_go_home_flag(t *testing.T) {
	var e Events
	if e.HomeShown() {
		t.Fatal("affiché au départ")
	}
	e.Start()
	if !e.HomeShown() {
		t.Fatal("Start sans effet")
	}
	e.Stop()
	if e.HomeShown() {
		t.Fatal("Stop sans effet")
	}
}
