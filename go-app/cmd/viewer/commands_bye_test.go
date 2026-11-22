package main

import (
	"bytes"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"

	"viewer/internal/devsync"
	"viewer/internal/player"
)

// startByeEmby is a local fake Emby that records, in order, what it receives:
// "load" / "bye" for the sync envelopes sent to the TV, "logout" for the sign-out.
func startByeEmby(t *testing.T) (*httptest.Server, func() string) {
	t.Helper()
	public, auth, user := e2eFixture(t, "public_info"), e2eFixture(t, "auth_result"), e2eFixture(t, "user")
	var mu sync.Mutex
	var seen []string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		w.Header().Set("Content-Type", "application/json")
		record := func(s string) {
			mu.Lock()
			seen = append(seen, s)
			mu.Unlock()
		}
		switch {
		case r.URL.Path == "/emby/System/Info/Public":
			_, _ = io.WriteString(w, public)
		case r.URL.Path == "/emby/Users/AuthenticateByName":
			_, _ = io.WriteString(w, auth)
		case r.URL.Path == "/emby/Users/"+e2eUserID:
			_, _ = io.WriteString(w, user)
		case r.URL.Path == "/emby/Sessions/Logout":
			record("logout")
			w.WriteHeader(http.StatusNoContent)
		case strings.HasSuffix(r.URL.Path, "/Command"):
			var cmd struct{ Arguments struct{ String string } }
			_ = json.Unmarshal(body, &cmd)
			if env, err := devsync.Decode(cmd.Arguments.String); err == nil && env.Body != nil {
				record(env.Body.Kind())
			}
			w.WriteHeader(http.StatusNoContent)
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	t.Cleanup(srv.Close)
	return srv, func() string {
		mu.Lock()
		defer mu.Unlock()
		return strings.Join(seen, ",")
	}
}

// steadyPlayer is a playing engine at a fixed position (the real one needs libmpv).
type steadyPlayer struct{}

func (steadyPlayer) PositionSeconds() (float64, bool)          { return 12, true }
func (steadyPlayer) SelectedStreams() (audio, subtitle *int32) { return nil, nil }
func (steadyPlayer) Command(player.PlayerCommand) error        { return nil }

// linkedApp signs in on the fake server and links a TV, so that Stop has a bye to send.
func linkedApp(t *testing.T) (invoker func(cmd string) string, seen func() string) {
	t.Helper()
	srv, seen := startByeEmby(t)
	a, b, _, _ := testApp(t, true)
	args, _ := json.Marshal(map[string]string{"url": srv.URL, "username": "quentin", "password": e2ePassword})
	if _, err := invoke(t, b, "emby_sign_in", string(args)); err != "" {
		t.Fatal(err)
	}
	a.Sync = devsync.NewService(t.Context(), devsync.ServiceConfig{
		Session: a.Emby,
		Engine:  func() devsync.Player { return steadyPlayer{} },
		Emit:    func(string, any) {},
	})
	own := "own1"
	a.Sync.OwnSession(&own)
	a.Sync.Playing("item1", "src1", true)
	a.Sync.PlayerEvent(player.FileLoaded{})
	if err := a.Sync.Invite(t.Context(), devsync.Peer{SessionID: "tv1", Name: "Shield"}); err != nil {
		t.Fatal(err)
	}
	return func(cmd string) string {
		if _, err := invoke(t, b, cmd, ""); err != "" {
			t.Fatal(err)
		}
		return seen()
	}, seen
}

func Test_go_sign_out_says_bye_to_the_tv_before_revoking(t *testing.T) {
	run, _ := linkedApp(t)
	if got := run("emby_sign_out"); got != "load,bye,logout" {
		t.Fatal(got)
	}
}

func Test_go_changing_server_says_bye_to_the_tv(t *testing.T) {
	run, _ := linkedApp(t)
	if got := run("emby_change_server"); got != "load,bye" {
		t.Fatal(got)
	}
}

func Test_go_artwork_cache_clear_is_logged(t *testing.T) {
	var buf bytes.Buffer
	prev := slog.Default()
	slog.SetDefault(slog.New(slog.NewTextHandler(&buf, nil)))
	defer slog.SetDefault(prev)
	_, b, _, _ := testApp(t, true)
	if _, err := invoke(t, b, "artwork_cache_clear", ""); err != "" {
		t.Fatal(err)
	}
	if !strings.Contains(buf.String(), "artwork cache cleared: 0 images, 0 bytes") {
		t.Fatal(buf.String())
	}
}
