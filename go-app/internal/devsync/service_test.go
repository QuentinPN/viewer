package devsync

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"viewer/internal/emby"
	"viewer/internal/player"
)

// service.rs has no Rust test: these Test_go_* drive the glue against a local
// Emby stand-in and a fake engine.

type fakeSession struct{ client *emby.Client }

func (f fakeSession) Client() (*emby.Client, error) {
	if f.client == nil {
		return nil, emby.ErrNotSignedIn
	}
	return f.client, nil
}

func (f fakeSession) CurrentUser() *emby.SignedInUser {
	if f.client == nil {
		return nil
	}
	return &emby.SignedInUser{UserID: "u1"}
}

type fakeEngine struct {
	mu       sync.Mutex
	commands []player.PlayerCommand
}

func (*fakeEngine) PositionSeconds() (float64, bool) { return 12.3456, true }
func (*fakeEngine) SelectedStreams() (*int32, *int32) {
	a, s := int32(1), int32(3)
	return &a, &s
}
func (e *fakeEngine) Command(c player.PlayerCommand) error {
	e.mu.Lock()
	e.commands = append(e.commands, c)
	e.mu.Unlock()
	return nil
}

type relay struct {
	mu   sync.Mutex
	sent []Envelope
	to   []string
}

func standIn(t *testing.T) (*relay, *emby.Client) {
	t.Helper()
	r := &relay{}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		switch {
		case req.Method == http.MethodGet && req.URL.Path == "/emby/Sessions":
			w.Header().Set("Content-Type", "application/json")
			_, _ = io.WriteString(w, `[{"Id":"tv1","DeviceId":"dev-tv","DeviceName":"Shield salon","Client":"Viewer TV","SupportsRemoteControl":true}]`)
		case req.Method == http.MethodPost && strings.HasSuffix(req.URL.Path, "/Command"):
			var body struct {
				Name      string
				Arguments struct{ String string }
			}
			_ = json.NewDecoder(req.Body).Decode(&body)
			env, err := Decode(body.Arguments.String)
			if err != nil || body.Name != "SendString" {
				t.Errorf("relais invalide : %v %q", err, body.Name)
			}
			r.mu.Lock()
			r.sent = append(r.sent, env)
			r.to = append(r.to, strings.TrimSuffix(strings.TrimPrefix(req.URL.Path, "/emby/Sessions/"), "/Command"))
			r.mu.Unlock()
			w.WriteHeader(http.StatusNoContent)
		default:
			http.NotFound(w, req)
		}
	}))
	t.Cleanup(srv.Close)
	u, err := emby.ParseServerURL(srv.URL)
	if err != nil {
		t.Fatal(err)
	}
	c, err := emby.NewClient(u, emby.DeviceIdentity{Name: "pc", ID: "dev-pc", Version: "1.0.0"})
	if err != nil {
		t.Fatal(err)
	}
	return r, c.WithToken(emby.NewAccessToken("tok"))
}

func (r *relay) kinds() []string {
	r.mu.Lock()
	defer r.mu.Unlock()
	out := make([]string, len(r.sent))
	for i, e := range r.sent {
		if e.Body != nil {
			out[i] = e.Body.Kind()
		}
	}
	return out
}

type events struct {
	mu   sync.Mutex
	list []string
}

func (e *events) emit(name string, payload any) {
	b, err := json.Marshal(payload)
	if err != nil {
		panic(err)
	}
	e.mu.Lock()
	e.list = append(e.list, name+" "+string(b))
	e.mu.Unlock()
}

func (e *events) all() []string {
	e.mu.Lock()
	defer e.mu.Unlock()
	return append([]string(nil), e.list...)
}

func newTestService(t *testing.T, client *emby.Client, engine Player) (*Service, *events, context.CancelFunc) {
	t.Helper()
	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(cancel)
	ev := &events{}
	svc := NewService(ctx, ServiceConfig{
		Session: fakeSession{client: client},
		Engine:  func() Player { return engine },
		Emit:    ev.emit,
		NewLink: func() string { return "link0001" },
	})
	return svc, ev, cancel
}

func errText(err error) string {
	if err == nil {
		return ""
	}
	b, _ := json.Marshal(err)
	return string(b)
}

func Test_go_invite_refusals_are_sync_errors(t *testing.T) {
	_, client := standIn(t)
	engine := &fakeEngine{}
	svc, _, _ := newTestService(t, client, engine)
	ctx := context.Background()
	peer := Peer{SessionID: "tv1", Name: "Shield salon"}

	if got := errText(svc.Invite(ctx, peer)); got != `{"kind":"sync","message":"lecture liée impossible : session Emby pas encore identifiée, réessaie dans un instant"}` {
		t.Fatal(got)
	}
	own := "own1"
	svc.OwnSession(&own)
	if got := errText(svc.Invite(ctx, peer)); !strings.Contains(got, "aucune lecture en cours") {
		t.Fatal(got)
	}
	svc.Playing("item1", "src1", true)
	svc.PlayerEvent(player.FileLoaded{})
	if err := svc.Invite(ctx, peer); err != nil {
		t.Fatal(err)
	}
	if got := errText(svc.Invite(ctx, peer)); !strings.Contains(got, "une TV suit déjà cette lecture") {
		t.Fatal(got)
	}
}

func Test_go_invite_relays_a_load_then_stop_says_bye_in_order(t *testing.T) {
	r, client := standIn(t)
	engine := &fakeEngine{}
	svc, ev, _ := newTestService(t, client, engine)
	ctx := context.Background()

	if st, _ := json.Marshal(svc.Status(ctx)); string(st) != `{"phase":"idle"}` {
		t.Fatal(string(st))
	}
	own := "own1"
	svc.OwnSession(&own)
	svc.Playing("item1", "src1", true)
	svc.PlayerEvent(player.FileLoaded{})
	svc.PlayerEvent(player.TimePos{Seconds: 1}) // dropped: positions are read from the engine
	if err := svc.Invite(ctx, Peer{SessionID: "tv1", Name: "Shield salon"}); err != nil {
		t.Fatal(err)
	}
	if st, _ := json.Marshal(svc.Status(ctx)); string(st) != `{"phase":"waiting","device":"Shield salon"}` {
		t.Fatal(string(st))
	}
	if !svc.Stop(2 * time.Second) {
		t.Fatal("bye non confirmé")
	}
	// Stop is acknowledged once every earlier envelope went out, in order.
	if got := strings.Join(r.kinds(), ","); got != "load,bye" {
		t.Fatal(got)
	}
	r.mu.Lock()
	load, bye := r.sent[0], r.sent[1]
	to := r.to
	r.mu.Unlock()
	if load.From != "own1" || load.Link != "link0001" || load.Seq != 0 || bye.Seq != 1 || to[0] != "tv1" {
		t.Fatalf("%+v %+v %v", load, bye, to)
	}
	l := load.Body.(Load)
	if l.ItemID != "item1" || l.PositionMs != 12346 || l.AudioStreamIndex == nil || *l.AudioStreamIndex != 1 {
		t.Fatalf("%+v", l)
	}
	if b := bye.Body.(Bye); b.Reason != ByeStopped {
		t.Fatal(b.Reason)
	}
	got := ev.all()
	if len(got) < 2 || got[0] != `sync://status {"phase":"waiting","device":"Shield salon"}` || got[len(got)-1] != `sync://status {"phase":"idle"}` {
		t.Fatal(got)
	}
	// Stop ends the link, not the service (sign-out, server change): a new
	// invitation still goes out.
	if err := svc.Invite(ctx, Peer{SessionID: "tv1", Name: "Shield salon"}); err != nil {
		t.Fatal(err)
	}
	if st, _ := json.Marshal(svc.Status(ctx)); string(st) != `{"phase":"waiting","device":"Shield salon"}` {
		t.Fatal(string(st))
	}
}

// A remux or transcode keeps the TV's own stream indexes.
func Test_go_streams_only_go_out_in_direct_play(t *testing.T) {
	r, client := standIn(t)
	svc, _, _ := newTestService(t, client, &fakeEngine{})
	own := "own1"
	svc.OwnSession(&own)
	svc.Playing("item1", "src1", false)
	svc.PlayerEvent(player.FileLoaded{})
	if err := svc.Invite(context.Background(), Peer{SessionID: "tv1", Name: "TV"}); err != nil {
		t.Fatal(err)
	}
	svc.Stop(2 * time.Second)
	r.mu.Lock()
	defer r.mu.Unlock()
	if l := r.sent[0].Body.(Load); l.AudioStreamIndex != nil || l.SubtitleStreamIndex != nil {
		t.Fatalf("%+v", l)
	}
}

// A `join` from a listed TV opens a link named after the server's list.
func Test_go_join_takes_the_name_from_the_server(t *testing.T) {
	r, client := standIn(t)
	svc, _, _ := newTestService(t, client, &fakeEngine{})
	ctx := context.Background()
	own := "own1"
	svc.OwnSession(&own)
	svc.Playing("item1", "src1", true)
	svc.PlayerEvent(player.FileLoaded{})
	svc.Incoming(Envelope{From: "tv1", Link: "tvlink01", Seq: 0, Body: Join{}}.Encode())
	deadline := time.Now().Add(3 * time.Second)
	for svc.Status(ctx).Phase == PhaseIdle && time.Now().Before(deadline) {
		time.Sleep(20 * time.Millisecond)
	}
	if st := svc.Status(ctx); st.Device != "Shield salon" {
		t.Fatalf("%+v", st)
	}
	svc.Stop(2 * time.Second)
	if got := r.kinds(); len(got) == 0 || got[0] != "load" {
		t.Fatal(got)
	}
}

// Another session: a link of the old one ends.
func Test_go_a_new_own_session_ends_the_link(t *testing.T) {
	r, client := standIn(t)
	svc, _, _ := newTestService(t, client, &fakeEngine{})
	ctx := context.Background()
	own := "own1"
	svc.OwnSession(&own)
	svc.Playing("item1", "src1", true)
	svc.PlayerEvent(player.FileLoaded{})
	if err := svc.Invite(ctx, Peer{SessionID: "tv1", Name: "TV"}); err != nil {
		t.Fatal(err)
	}
	svc.OwnSession(nil)
	if st := svc.Status(ctx); st.Phase != PhaseIdle {
		t.Fatalf("%+v", st)
	}
	svc.Stop(2 * time.Second)
	if got := strings.Join(r.kinds(), ","); got != "load,bye" {
		t.Fatal(got)
	}
}

func Test_go_a_stopped_service_answers_idle(t *testing.T) {
	svc, _, cancel := newTestService(t, nil, nil)
	cancel()
	time.Sleep(50 * time.Millisecond)
	if st := svc.Status(context.Background()); st.Phase != PhaseIdle {
		t.Fatalf("%+v", st)
	}
	if svc.Stop(100 * time.Millisecond) {
		t.Fatal("Stop confirmé sans service")
	}
	if got := errText(svc.Invite(context.Background(), Peer{SessionID: "tv1"})); got != `{"kind":"sync","message":"lecture liée impossible : service arrêté"}` {
		t.Fatal(got)
	}
}

func Test_go_devices_need_a_session(t *testing.T) {
	if _, err := Devices(context.Background(), fakeSession{}); errText(err) != errText(emby.ErrNotSignedIn) {
		t.Fatal(err)
	}
	_, client := standIn(t)
	list, err := Devices(context.Background(), fakeSession{client: client})
	if err != nil || len(list) != 1 || list[0].Name != "Shield salon" {
		t.Fatal(list, err)
	}
}

func Test_go_link_ids_are_simple_uuids(t *testing.T) {
	id := newLinkID()
	if len(id) != 32 || !IsID(id, 32) || id[12] != '4' || !strings.ContainsRune("89ab", rune(id[16])) {
		t.Fatal(id)
	}
	if id == newLinkID() {
		t.Fatal("deux fois le même lien")
	}
}

// What a Stremio playback (stremio_play) leaves to the linked playback: a Shutdown
// forgets the Emby item, so the next file, which no Playing announced, is never
// offered to a TV under that item.
func Test_go_a_file_after_a_shutdown_is_not_the_emby_item(t *testing.T) {
	r, client := standIn(t)
	svc, _, _ := newTestService(t, client, &fakeEngine{})
	ctx := context.Background()
	peer := Peer{SessionID: "tv1", Name: "Shield salon"}
	own := "own1"
	svc.OwnSession(&own)
	svc.Playing("item1", "src1", true)
	svc.PlayerEvent(player.FileLoaded{})
	svc.PlayerEvent(player.Shutdown{})
	svc.PlayerEvent(player.EndFile{Reason: player.EndStopped})
	svc.PlayerEvent(player.FileLoaded{})
	svc.PlayerEvent(player.Paused{Paused: true})
	if got := errText(svc.Invite(ctx, peer)); !strings.Contains(got, "aucune lecture en cours") {
		t.Fatal(got)
	}
	if !svc.Stop(2 * time.Second) {
		t.Fatal("stop")
	}
	if got := r.kinds(); len(got) != 0 {
		t.Fatal(got)
	}
}
