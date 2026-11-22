package emby

// The service fixture of tests.rs (`Fixture`, `fixture`, `reopen`, `signed_in_browser`
// and the Gate 2.1 helpers), shared by the session_*_test.go files.

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

// fixture is `Fixture`: a service, its credential store and its session file.
type fixture struct {
	service *EmbyService
	store   *MemoryCredentialStore
	path    string
}

// newFixture is `fixture()`: a new store and a session.json in a fresh directory.
func newFixture(t *testing.T) fixture {
	t.Helper()
	return reopen(&MemoryCredentialStore{}, filepath.Join(t.TempDir(), "session.json"))
}

// reopen is `reopen`: same store and session file, new service: simulates an app
// restart.
func reopen(store *MemoryCredentialStore, path string) fixture {
	return fixture{service: NewEmbyService(store, path, `PC "test", bureau`, "0.1.0"), store: store, path: path}
}

// signedInBrowser is `signed_in_browser`: a browse_like server and a service signed
// in on it.
func signedInBrowser(t *testing.T) (*fakeEmby, fixture, *atomic.Bool) {
	t.Helper()
	revoked := new(atomic.Bool)
	server := startFakeEmby(t, fakeBrowseLike(revoked))
	fx := newFixture(t)
	mustSignIn(t, fx, server.URL)
	return server, fx, revoked
}

// storedToken is `stored_token`: the token of the fixture account, nil without one.
func storedToken(t *testing.T, fx fixture) *AccessToken {
	t.Helper()
	return storedTokenFor(t, fx, fakeServerID, fakeUserID)
}

// storedTokenFor is `stored_token_for`.
func storedTokenFor(t *testing.T, fx fixture, serverID, userID string) *AccessToken {
	t.Helper()
	token, err := fx.store.Load(CredentialAccount(serverID, userID))
	if err != nil {
		t.Fatal(err)
	}
	return token
}

// mustRestore is `run(fx.service.restore()).unwrap()`.
func mustRestore(t *testing.T, fx fixture) SessionStatus {
	t.Helper()
	status, err := fx.service.Restore(bg)
	if err != nil {
		t.Fatal(err)
	}
	return status
}

// authenticated is `authenticated`.
func authenticated(t *testing.T, status SessionStatus) SignedInUser {
	t.Helper()
	if status.State != StateAuthenticated || status.User == nil {
		t.Fatalf("attendu authenticated, reçu %+v", status)
	}
	return *status.User
}

// reauthKind is `reauth_kind`: the error kind of an AuthenticationRequired status.
func reauthKind(t *testing.T, status SessionStatus) ErrorKind {
	t.Helper()
	if status.State != StateAuthenticationRequired || status.Server == nil || status.Err == nil {
		t.Fatalf("attendu authenticationRequired, reçu %+v", status)
	}
	if status.Server.UserName != "testeur" {
		t.Fatalf("utilisateur %q", status.Server.UserName)
	}
	return status.Err.Kind
}

// unreachableKind is `unreachable_kind`.
func unreachableKind(t *testing.T, status SessionStatus) ErrorKind {
	t.Helper()
	if status.State != StateServerUnreachable || status.Server == nil || status.Err == nil {
		t.Fatalf("attendu serverUnreachable, reçu %+v", status)
	}
	if status.Server.ID != fakeServerID {
		t.Fatalf("serveur %q", status.Server.ID)
	}
	return status.Err.Kind
}

// sessionFile is `session_file`: session.json as saved.
func sessionFile(t *testing.T, fx fixture) SessionFile {
	t.Helper()
	b, err := os.ReadFile(fx.path)
	if err != nil {
		t.Fatal(err)
	}
	var file SessionFile
	if err := decodeStrict(b, &file); err != nil {
		t.Fatal(err)
	}
	return file
}

// retarget is `retarget`: points the saved session at another address and restarts
// the service, e.g. a server that is off at startup.
func retarget(t *testing.T, fx fixture, url string) fixture {
	t.Helper()
	file := sessionFile(t, fx)
	file.Session.ServerURL = url
	if err := file.Save(fx.path); err != nil {
		t.Fatal(err)
	}
	return reopen(fx.store, fx.path)
}

// shortTimeouts is `short_timeouts`.
func shortTimeouts(fx fixture) fixture {
	fx.service = fx.service.WithTimeouts(500*time.Millisecond, 300*time.Millisecond)
	return fx
}

// userAnswer is `user_answer`: emby_like whose `/Users/{Id}` answer is chosen by the
// test.
func userAnswer(status int, body string) fakeRoute {
	base := fakeEmbyLike(204)
	return func(r fakeRequest) (int, string) {
		if r.Path == "/emby/Users/"+fakeUserID {
			return status, body
		}
		return base(r)
	}
}

// rotatingTokens is `rotating_tokens`: a new token at each sign-in (`tokA`, then
// `tokB`), both accepted, so a test can hold a client of a previous session.
func rotatingTokens() fakeRoute {
	var issued atomic.Int64
	return func(r fakeRequest) (int, string) {
		switch {
		case r.Method == "GET" && r.Path == "/emby/System/Info/Public":
			return 200, fakeFixture("public_info")
		case r.Method == "POST" && r.Path == "/emby/Users/AuthenticateByName":
			token := "tokB"
			if issued.Add(1) == 1 {
				token = "tokA"
			}
			return 200, strings.ReplaceAll(fakeFixture("auth_result"), fakeToken, token)
		case r.Method == "POST" && r.Path == "/emby/Sessions/Logout":
			return 204, ""
		default:
			return 200, fakeFixture("user")
		}
	}
}

// shelfTile is one `(server id, access)` of shelf_of.
type shelfTile struct {
	ID     string
	Access ShelfAccess
}

// shelfOf is `shelf_of`: each shelf tile, in order.
func shelfOf(fx fixture) []shelfTile {
	var tiles []shelfTile
	for _, t := range fx.service.Shelf() {
		tiles = append(tiles, shelfTile{t.Server.ID, t.Access})
	}
	return tiles
}

// secondServerUser is the user of secondServer.
const secondServerUser = "0b1a2c3d4e5f60718293a4b5c6d7e8f9"

// secondServer is `second_server`: a second server with its own id and user,
// accepting the same password.
func secondServer(t *testing.T) *fakeEmby {
	t.Helper()
	return startFakeEmby(t, func(r fakeRequest) (int, string) {
		var body string
		switch r.Path {
		case "/emby/System/Info/Public":
			body = strings.ReplaceAll(fakeFixture("public_info"), fakeServerID, "secondsrv")
		case "/emby/Users/AuthenticateByName":
			body = strings.ReplaceAll(strings.ReplaceAll(fakeFixture("auth_result"), fakeServerID, "secondsrv"), fakeUserID, secondServerUser)
		}
		if body == "" {
			return 204, ""
		}
		return 200, body
	})
}

// mustSignIn signs fx in on url with the fixture account.
func mustSignIn(t *testing.T, fx fixture, url string) {
	t.Helper()
	if _, err := fx.service.SignIn(bg, url, "testeur", fakePassword); err != nil {
		t.Fatal(err)
	}
}

// wantErr is `matches!(err, EmbyError::<want>)`: the kind of want (errors.Is).
func wantErr(t *testing.T, err error, want EmbyError) {
	t.Helper()
	if !errors.Is(err, want) {
		t.Fatalf("erreur %v (%T), attendu %s", err, err, want.Kind)
	}
}
