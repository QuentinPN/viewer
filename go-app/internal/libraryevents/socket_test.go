package libraryevents

import (
	"context"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"

	"github.com/coder/websocket"

	"viewer/internal/emby"
)

type fakeSession struct {
	mu     sync.Mutex
	client *emby.Client
}

func (f *fakeSession) Client() (*emby.Client, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.client == nil {
		return nil, emby.ErrNotSignedIn
	}
	return f.client, nil
}

func (f *fakeSession) CurrentUser() *emby.SignedInUser {
	return &emby.SignedInUser{UserID: "u1"}
}

func (f *fakeSession) signOut() {
	f.mu.Lock()
	f.client = nil
	f.mu.Unlock()
}

type fakeSync struct {
	incoming chan string
	own      chan *string
}

func (f *fakeSync) Incoming(raw string)   { f.incoming <- raw }
func (f *fakeSync) OwnSession(id *string) { f.own <- id }

// The supervisor against a local Emby stand-in: the first keep-alive at once,
// capabilities then sessions, the own session learnt, a SendString relayed, a
// library change announced once settled (home shown), and the socket closed by
// the client when the session ends.
func Test_go_socket_carries_relays_and_library_changes(t *testing.T) {
	frames := make(chan string, 8)
	serverSide := make(chan *websocket.Conn, 1)
	closed := make(chan websocket.StatusCode, 1)
	var capabilities sync.WaitGroup
	capabilities.Add(1)
	mux := http.NewServeMux()
	mux.HandleFunc("/embywebsocket", func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Query().Get("api_key") != "tok" || r.URL.Query().Get("deviceId") != "dev-1" {
			http.Error(w, "jeton", http.StatusUnauthorized)
			return
		}
		c, err := websocket.Accept(w, r, nil)
		if err != nil {
			return
		}
		serverSide <- c
		for {
			_, data, err := c.Read(context.Background())
			if err != nil {
				closed <- websocket.CloseStatus(err)
				return
			}
			frames <- string(data)
		}
	})
	mux.HandleFunc("/emby/Sessions/Capabilities/Full", func(w http.ResponseWriter, r *http.Request) {
		capabilities.Done()
		w.WriteHeader(http.StatusNoContent)
	})
	mux.HandleFunc("/emby/Sessions", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`[{"Id":"own1","DeviceId":"dev-1","Client":"Viewer","SupportsRemoteControl":true}]`))
	})
	srv := httptest.NewServer(mux)
	defer srv.Close()

	session := &fakeSession{client: client(t, srv.URL, true)}
	sync := &fakeSync{incoming: make(chan string, 4), own: make(chan *string, 4)}
	events := &Events{}
	events.Start()
	emitted := make(chan string, 4)
	sup := &Supervisor{
		Session: session, Sync: sync, Events: events,
		Emit: func(name string, payload any) {
			if payload != nil {
				t.Errorf("charge utile %v", payload)
			}
			emitted <- name
		},
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan struct{})
	go func() { sup.Run(ctx); close(done) }()

	wait := func(what string) time.Duration { return 5 * time.Second }
	select {
	case id := <-sync.own:
		if id != nil {
			t.Fatalf("première session %v", *id)
		}
	case <-time.After(wait("own nil")):
		t.Fatal("OwnSession(nil) absent")
	}
	select {
	case f := <-frames:
		if f != `{"MessageType":"KeepAlive"}` {
			t.Fatalf("premier message %s", f)
		}
	case <-time.After(wait("keepalive")):
		t.Fatal("pas de KeepAlive à la connexion")
	}
	select {
	case id := <-sync.own:
		if id == nil || *id != "own1" {
			t.Fatalf("session propre %v", id)
		}
	case <-time.After(wait("own")):
		t.Fatal("session propre non apprise")
	}
	capabilities.Wait()

	c := <-serverSide
	write := func(text string) {
		if err := c.Write(context.Background(), websocket.MessageText, []byte(text)); err != nil {
			t.Fatal(err)
		}
	}
	write(`{"MessageType":"GeneralCommand","Data":{"Name":"SendString","Arguments":{"String":"hello"}}}`)
	select {
	case got := <-sync.incoming:
		if got != "hello" {
			t.Fatal(got)
		}
	case <-time.After(wait("relay")):
		t.Fatal("SendString non relayé")
	}
	start := time.Now()
	write(`{"MessageType":"LibraryChanged","Data":{"ItemsAdded":["1"]}}`)
	select {
	case name := <-emitted:
		if name != LibraryChangedEvent {
			t.Fatal(name)
		}
		if d := time.Since(start); d < Settle-100*time.Millisecond {
			t.Fatalf("annoncé après %v, avant l'apaisement", d)
		}
	case <-time.After(Settle + wait("settle")):
		t.Fatal("changement non annoncé")
	}

	// Signed out: the client closes its socket within a session check.
	session.signOut()
	select {
	case code := <-closed:
		if code != websocket.StatusNormalClosure && code != -1 {
			t.Fatalf("fermeture %v", code)
		}
	case <-time.After(wait("close")):
		t.Fatal("socket gardé après la fin de session")
	}
	cancel()
	select {
	case <-done:
	case <-time.After(wait("run")):
		t.Fatal("Run ne s'arrête pas")
	}
}

// Home not shown: a library change is not announced.
func Test_go_library_change_needs_the_home(t *testing.T) {
	serverSide := make(chan *websocket.Conn, 1)
	mux := http.NewServeMux()
	mux.HandleFunc("/embywebsocket", func(w http.ResponseWriter, r *http.Request) {
		c, err := websocket.Accept(w, r, nil)
		if err != nil {
			return
		}
		serverSide <- c
		for {
			if _, _, err := c.Read(context.Background()); err != nil {
				return
			}
		}
	})
	mux.HandleFunc("/emby/", func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(http.StatusNoContent) })
	srv := httptest.NewServer(mux)
	defer srv.Close()
	emitted := make(chan string, 1)
	sup := &Supervisor{
		Session: &fakeSession{client: client(t, srv.URL, true)},
		Sync:    &fakeSync{incoming: make(chan string, 4), own: make(chan *string, 4)},
		Events:  &Events{},
		Emit:    func(name string, _ any) { emitted <- name },
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go sup.Run(ctx)
	c := <-serverSide
	_ = c.Write(context.Background(), websocket.MessageText, []byte(`{"MessageType":"LibraryChanged","Data":{"ItemsAdded":["1"]}}`))
	select {
	case name := <-emitted:
		t.Fatalf("%s émis sans accueil", name)
	case <-time.After(Settle + 500*time.Millisecond):
	}
}

func Test_go_error_kinds_never_need_the_url(t *testing.T) {
	if got := errorKind(nil, &http.Response{StatusCode: 401}); got != "jeton refusé" {
		t.Fatal(got)
	}
	if got := errorKind(nil, &http.Response{StatusCode: 404}); got != "réponse HTTP" {
		t.Fatal(got)
	}
	_, resp, err := websocket.Dial(context.Background(), "ws://127.0.0.1:1/embywebsocket?api_key=secret", nil)
	if got := errorKind(err, resp); got != "réseau" {
		t.Fatalf("%s (%v)", got, err)
	}
}

func Test_go_the_socket_dial_refuses_redirects(t *testing.T) {
	var targetHits int
	var mu sync.Mutex
	target := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {
		mu.Lock()
		targetHits++
		mu.Unlock()
	}))
	defer target.Close()
	redirector := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, target.URL+"/?api_key=secret", http.StatusFound)
	}))
	defer redirector.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	conn, resp, err := websocket.Dial(ctx, "ws"+redirector.URL[len("http"):]+"/embywebsocket?api_key=secret",
		&websocket.DialOptions{HTTPClient: noRedirects})
	if err == nil {
		_ = conn.CloseNow()
		t.Fatal("le dial a suivi la redirection")
	}
	if resp != nil && resp.Body != nil {
		_ = resp.Body.Close()
	}
	mu.Lock()
	defer mu.Unlock()
	if targetHits != 0 {
		t.Fatalf("la cible de la redirection a reçu %d requêtes", targetHits)
	}
}
