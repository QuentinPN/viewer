package emby

// tests.rs, Gate 2.1 (persistent session and reconnection), sessions set
// aside, shelf and forget_server, and linked playback calls:
// lines 4660–5487, through EmbyService.

import (
	"encoding/json"
	"errors"
	"os"
	"reflect"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// rcnToken is `stored_token(&fx).unwrap().expose()`.
func rcnToken(t *testing.T, fx fixture) string {
	t.Helper()
	tok := storedToken(t, fx)
	if tok == nil {
		t.Fatal("aucun jeton stocké")
	}
	return tok.Expose()
}

func rcnNoToken(auth string) bool { return !strings.Contains(auth, fakeToken) }

func Test_restore_with_server_offline_keeps_the_session_and_recovers_later(t *testing.T) {
	server := startFakeEmby(t, fakeEmbyLike(204))
	fx := newFixture(t)
	mustSignIn(t, fx, server.URL)

	fx = retarget(t, fx, closedPortURL(t))
	if k := unreachableKind(t, mustRestore(t, fx)); k != "unreachable" {
		t.Fatal(k)
	}
	// Nothing is wiped by a connectivity failure, even after several attempts.
	if k := unreachableKind(t, mustRestore(t, fx)); k != "unreachable" {
		t.Fatal(k)
	}
	if rcnToken(t, fx) != fakeToken {
		t.Fatal("jeton changé")
	}
	if sessionFile(t, fx).Session == nil || sessionFile(t, fx).Reauth != nil {
		t.Fatal("session.json modifié")
	}
	if fx.service.CurrentUser() != nil {
		t.Fatal("utilisateur courant")
	}

	// The server comes back: the same session opens without a password.
	fx = retarget(t, fx, server.URL)
	if u := authenticated(t, mustRestore(t, fx)); u.UserID != fakeUserID {
		t.Fatal(u.UserID)
	}
}

func Test_restore_on_timeout_or_server_errors_never_invalidates(t *testing.T) {
	cases := []struct {
		route fakeRoute
		kind  ErrorKind
	}{
		{userAnswer(500, ""), "server"},
		{userAnswer(503, ""), "server"},
		{userAnswer(200, "<html>proxy</html>"), "invalidResponse"},
		{func(r fakeRequest) (int, string) {
			if r.Path == "/emby/System/Info/Public" {
				return 502, ""
			}
			return 200, ""
		}, "server"},
	}
	for _, c := range cases {
		good := startFakeEmby(t, fakeEmbyLike(204))
		fx := newFixture(t)
		mustSignIn(t, fx, good.URL)
		bad := startFakeEmby(t, c.route)
		fx = retarget(t, fx, bad.URL)
		if k := unreachableKind(t, mustRestore(t, fx)); k != c.kind {
			t.Fatalf("%s, attendu %s", k, c.kind)
		}
		if rcnToken(t, fx) != fakeToken {
			t.Fatal(c.kind)
		}
		if sessionFile(t, fx).Session == nil {
			t.Fatal(c.kind)
		}
	}

	// A server that accepts the connection but never answers in time.
	good := startFakeEmby(t, fakeEmbyLike(204))
	fx := newFixture(t)
	mustSignIn(t, fx, good.URL)
	base := fakeEmbyLike(204)
	slow := startFakeEmby(t, func(r fakeRequest) (int, string) {
		if r.Path == "/emby/Users/"+fakeUserID {
			time.Sleep(800 * time.Millisecond)
		}
		return base(r)
	})
	fx = shortTimeouts(retarget(t, fx, slow.URL))
	if k := unreachableKind(t, mustRestore(t, fx)); k != "timeout" {
		t.Fatal(k)
	}
	if rcnToken(t, fx) != fakeToken || sessionFile(t, fx).Session == nil {
		t.Fatal("session perdue")
	}
}

func Test_restore_probes_without_the_token_and_refuses_another_server(t *testing.T) {
	good := startFakeEmby(t, fakeEmbyLike(204))
	fx := newFixture(t)
	mustSignIn(t, fx, good.URL)
	// Another Emby server now answers at the saved address.
	other := startFakeEmby(t, func(r fakeRequest) (int, string) {
		if r.Path == "/emby/System/Info/Public" {
			return 200, strings.ReplaceAll(fakeFixture("public_info"), fakeServerID, "otherid")
		}
		return 200, fakeFixture("user")
	})
	fx = retarget(t, fx, other.URL)
	if k := unreachableKind(t, mustRestore(t, fx)); k != "serverMismatch" {
		t.Fatal(k)
	}
	reqs := other.requests()
	if len(reqs) != 1 {
		t.Fatalf("seulement la sonde publique : %+v", reqs)
	}
	if strings.Contains(reqs[0].Auth, "Token=") {
		t.Fatal(reqs[0].Auth)
	}
	if rcnToken(t, fx) != fakeToken {
		t.Fatal("jeton changé")
	}

	// On the right server, the probe still goes without the token.
	fx = retarget(t, fx, good.URL)
	before := len(good.requests())
	authenticated(t, mustRestore(t, fx))
	probe := good.requests()[before]
	if probe.Path != "/emby/System/Info/Public" || strings.Contains(probe.Auth, "Token=") {
		t.Fatalf("%+v", probe)
	}
}

func Test_disabled_or_deleted_account_requires_reauthentication(t *testing.T) {
	disabled := `{"Name":"testeur","Id":"` + fakeUserID + `","Policy":{"IsAdministrator":false,"IsDisabled":true}}`
	for _, route := range []fakeRoute{
		userAnswer(200, disabled),
		userAnswer(404, `"User not found"`),
		userAnswer(403, ""),
	} {
		good := startFakeEmby(t, fakeEmbyLike(204))
		fx := newFixture(t)
		mustSignIn(t, fx, good.URL)
		server := startFakeEmby(t, route)
		fx = retarget(t, fx, server.URL)
		if k := reauthKind(t, mustRestore(t, fx)); k != "accountUnavailable" {
			t.Fatal(k)
		}
		if storedToken(t, fx) != nil {
			t.Fatal("jeton gardé")
		}
		file := sessionFile(t, fx)
		if file.Session != nil || file.Reauth == nil || file.Reauth.UserName != "testeur" {
			t.Fatalf("%+v", file)
		}
	}
}

func Test_a_token_removed_from_the_vault_asks_for_the_password(t *testing.T) {
	server := startFakeEmby(t, fakeEmbyLike(204))
	fx := newFixture(t)
	mustSignIn(t, fx, server.URL)
	if err := fx.store.Delete(CredentialAccount(fakeServerID, fakeUserID)); err != nil {
		t.Fatal(err)
	}
	fx = reopen(fx.store, fx.path)
	before := len(server.requests())
	if k := reauthKind(t, mustRestore(t, fx)); k != "sessionExpired" {
		t.Fatal(k)
	}
	if n := len(server.requests()); n != before {
		t.Fatalf("aucune requête sans jeton : %d", n-before)
	}
}

func Test_server_errors_while_browsing_keep_the_session(t *testing.T) {
	var mode atomic.Int32
	base := fakeBrowseLike(new(atomic.Bool))
	server := startFakeEmby(t, func(r fakeRequest) (int, string) {
		switch mode.Load() {
		case 1:
			return 503, ""
		case 2:
			return 0, "" // connection dropped
		}
		return base(r)
	})
	fx := newFixture(t)
	mustSignIn(t, fx, server.URL)
	if _, err := fx.service.Libraries(bg); err != nil {
		t.Fatal(err)
	}

	mode.Store(1)
	_, err := fx.service.Libraries(bg)
	var e EmbyError
	if !errors.As(err, &e) || e.Kind != KindServer || e.Status != 503 {
		t.Fatalf("%v", err)
	}
	mode.Store(2)
	_, err = fx.service.Items(bg, "5", 0, 50, ItemsOptions{})
	if k, _ := KindOf(err); k != KindUnreachable && k != KindInvalidResponse {
		t.Fatalf("%v", err)
	}
	if k := unreachableKind(t, mustRestore(t, fx)); k != "unreachable" {
		t.Fatal(k)
	}
	if fx.service.CurrentUser() == nil || rcnToken(t, fx) != fakeToken {
		t.Fatal("session perdue")
	}

	// Back online: the check succeeds and the known libraries are still accepted.
	mode.Store(0)
	authenticated(t, mustRestore(t, fx))
	before := len(server.requests())
	if _, err := fx.service.Items(bg, "5", 0, 50, ItemsOptions{}); err != nil {
		t.Fatal(err)
	}
	if n := len(server.requests()); n != before+1 {
		t.Fatalf("pas de nouvelle lecture de /Views : %d requêtes", n-before)
	}
}

func Test_a_late_401_from_a_previous_session_is_ignored(t *testing.T) {
	server := startFakeEmby(t, rotatingTokens())
	fx := newFixture(t)
	mustSignIn(t, fx, server.URL)
	old, err := fx.service.Client()
	if err != nil {
		t.Fatal(err)
	}
	mustSignIn(t, fx, server.URL)

	if late := fx.service.expireOn401(old, ErrSessionExpired); !errors.Is(late, ErrCancelled) {
		t.Fatalf("%v", late)
	}
	if fx.service.CurrentUser() == nil || rcnToken(t, fx) != "tokB" {
		t.Fatal("la session courante doit rester")
	}

	// After a sign-out, a late 401 of the closed session changes nothing either.
	current, err := fx.service.Client()
	if err != nil {
		t.Fatal(err)
	}
	if err := fx.service.SignOut(bg); err != nil {
		t.Fatal(err)
	}
	if late := fx.service.expireOn401(current, ErrSessionExpired); !errors.Is(late, ErrCancelled) {
		t.Fatalf("%v", late)
	}
}

func Test_concurrent_401s_end_the_session_once(t *testing.T) {
	server := startFakeEmby(t, rotatingTokens())
	fx := newFixture(t)
	mustSignIn(t, fx, server.URL)
	client, err := fx.service.Client()
	if err != nil {
		t.Fatal(err)
	}
	// Several home rows fail together with the same token.
	results := make([]ErrorKind, 4)
	var wg sync.WaitGroup
	for i := range results {
		wg.Add(1)
		go func() {
			defer wg.Done()
			results[i], _ = KindOf(fx.service.expireOn401(client, ErrSessionExpired))
		}()
	}
	wg.Wait()
	// Each failing request reports the expiry; the session ends once.
	for _, k := range results {
		if k != KindSessionExpired {
			t.Fatalf("%v", results)
		}
	}
	if fx.service.CurrentUser() != nil || storedToken(t, fx) != nil {
		t.Fatal("la session doit finir")
	}
}

func Test_change_server_during_a_check_cancels_it(t *testing.T) {
	good := startFakeEmby(t, fakeEmbyLike(204))
	fx := newFixture(t)
	mustSignIn(t, fx, good.URL)
	base := fakeEmbyLike(204)
	slow := startFakeEmby(t, func(r fakeRequest) (int, string) {
		time.Sleep(300 * time.Millisecond)
		return base(r)
	})
	fx = retarget(t, fx, slow.URL)
	done := make(chan error, 1)
	go func() {
		time.Sleep(100 * time.Millisecond)
		done <- fx.service.ChangeServer()
	}()
	_, err := fx.service.Restore(bg)
	if cerr := <-done; cerr != nil {
		t.Fatal(cerr)
	}
	if !errors.Is(err, ErrCancelled) {
		t.Fatalf("%v", err)
	}
	if fx.service.CurrentUser() != nil {
		t.Fatal("utilisateur courant")
	}
	if n := len(fx.service.SavedSessions()); n != 1 {
		t.Fatal(n)
	}
	if rcnToken(t, fx) != fakeToken {
		t.Fatal("jeton changé")
	}
}

func Test_change_server_sets_the_session_aside_and_returns_to_it(t *testing.T) {
	server := startFakeEmby(t, fakeEmbyLike(204))
	fx := newFixture(t)
	mustSignIn(t, fx, server.URL)
	before := len(server.requests())
	if err := fx.service.ChangeServer(); err != nil {
		t.Fatal(err)
	}

	if fx.service.CurrentUser() != nil {
		t.Fatal("utilisateur courant")
	}
	if n := len(server.requests()); n != before {
		t.Fatal("pas de déconnexion, rien d'envoyé")
	}
	if rcnToken(t, fx) != fakeToken {
		t.Fatal("jeton perdu")
	}
	fx = reopen(fx.store, fx.path)
	if s := mustRestore(t, fx); s.State != StateSignedOut {
		t.Fatalf("%+v", s)
	}
	saved := fx.service.SavedSessions()
	if len(saved) != 1 || saved[0].UserName != "testeur" || saved[0].Name != "ServeurTest" {
		t.Fatalf("%+v", saved)
	}
	js, err := json.Marshal(saved)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(js), fakeToken) {
		t.Fatal(string(js))
	}

	if _, err := fx.service.ResumeSaved(bg, "unknown", fakeUserID); !errors.Is(err, ErrNotFound) {
		t.Fatalf("%v", err)
	}
	status, err := fx.service.ResumeSaved(bg, fakeServerID, fakeUserID)
	if err != nil {
		t.Fatal(err)
	}
	if u := authenticated(t, status); u.UserID != fakeUserID {
		t.Fatal(u.UserID)
	}
	if len(fx.service.SavedSessions()) != 0 || sessionFile(t, fx).Session == nil {
		t.Fatal("la session doit revenir")
	}
}

func Test_set_aside_sessions_are_bounded_and_the_oldest_token_is_deleted(t *testing.T) {
	server := startFakeEmby(t, fakeEmbyLike(204))
	fx := newFixture(t)
	mustSignIn(t, fx, server.URL)
	file := sessionFile(t, fx)
	template := *file.Session
	for i := range MaxSaved {
		r := template
		r.ServerID = "srv" + strconv.Itoa(i)
		if err := fx.store.Save(CredentialAccount(r.ServerID, fakeUserID), NewAccessToken("t"+strconv.Itoa(i))); err != nil {
			t.Fatal(err)
		}
		file.Saved = append(file.Saved, r)
	}
	if err := file.Save(fx.path); err != nil {
		t.Fatal(err)
	}
	fx = reopen(fx.store, fx.path)
	authenticated(t, mustRestore(t, fx))
	if err := fx.service.ChangeServer(); err != nil {
		t.Fatal(err)
	}

	saved := fx.service.SavedSessions()
	if len(saved) != MaxSaved {
		t.Fatal(len(saved))
	}
	if saved[0].ID != fakeServerID {
		t.Fatal("le plus récent d'abord")
	}
	if tok, _ := fx.store.Load(CredentialAccount("srv"+strconv.Itoa(MaxSaved-1), fakeUserID)); tok != nil {
		t.Fatal("le plus ancien jeton doit être supprimé")
	}
	if tok, _ := fx.store.Load(CredentialAccount("srv0", fakeUserID)); tok == nil {
		t.Fatal("srv0 doit rester")
	}
}

func Test_sign_out_keeps_set_aside_sessions_and_clears_the_hint(t *testing.T) {
	server := startFakeEmby(t, fakeEmbyLike(204))
	fx := newFixture(t)
	mustSignIn(t, fx, server.URL)
	if err := fx.service.ChangeServer(); err != nil {
		t.Fatal(err)
	}
	// A second server with its own id and user.
	second := secondServer(t)
	mustSignIn(t, fx, second.URL)
	if err := fx.service.SignOut(bg); err != nil {
		t.Fatal(err)
	}
	saved := fx.service.SavedSessions()
	if len(saved) != 1 || saved[0].ID != fakeServerID {
		t.Fatalf("%+v", saved)
	}
	if rcnToken(t, fx) != fakeToken {
		t.Fatal("jeton perdu")
	}
	if sessionFile(t, fx).Reauth != nil {
		t.Fatal("indice gardé")
	}
}

func Test_shelf_lists_set_aside_signed_out_and_rejected_sessions_newest_first(t *testing.T) {
	first := startFakeEmby(t, fakeEmbyLike(204))
	fx := newFixture(t)
	mustSignIn(t, fx, first.URL)
	if len(fx.service.Shelf()) != 0 {
		t.Fatal("la session ouverte n'est pas une tuile")
	}
	if err := fx.service.ChangeServer(); err != nil {
		t.Fatal(err)
	}
	second := secondServer(t)
	mustSignIn(t, fx, second.URL)
	if err := fx.service.SignOut(bg); err != nil {
		t.Fatal(err)
	}

	// Signed out on purpose: the tile stays, without token and without pill.
	want := []shelfTile{{"secondsrv", ShelfSignedOut}, {fakeServerID, ShelfSession}}
	if got := shelfOf(fx); !reflect.DeepEqual(got, want) {
		t.Fatalf("%v", got)
	}
	if tok := storedTokenFor(t, fx, "secondsrv", secondServerUser); tok != nil {
		t.Fatal("jeton du second serveur gardé")
	}
	fx = reopen(fx.store, fx.path)
	if s := mustRestore(t, fx); s.State != StateSignedOut {
		t.Fatalf("%+v", s)
	}
	js, err := json.Marshal(fx.service.Shelf())
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(js), fakeToken) || !strings.Contains(string(js), `"access":"signedOut"`) {
		t.Fatal(string(js))
	}

	// The first server rejects its token: after "Changer de serveur" its tile
	// says "Mot de passe requis", even after a restart.
	if err := fx.store.Save(CredentialAccount(fakeServerID, fakeUserID), NewAccessToken("revoked")); err != nil {
		t.Fatal(err)
	}
	status, err := fx.service.ResumeSaved(bg, fakeServerID, fakeUserID)
	if err != nil {
		t.Fatal(err)
	}
	if k := reauthKind(t, status); k != "sessionExpired" {
		t.Fatal(k)
	}
	if got := shelfOf(fx)[0]; got != (shelfTile{fakeServerID, ShelfRejected}) {
		t.Fatalf("%v", got)
	}
	if err := fx.service.ChangeServer(); err != nil {
		t.Fatal(err)
	}
	fx = reopen(fx.store, fx.path)
	want = []shelfTile{{fakeServerID, ShelfRejected}, {"secondsrv", ShelfSignedOut}}
	if got := shelfOf(fx); !reflect.DeepEqual(got, want) {
		t.Fatalf("%v", got)
	}
	// Signing in again clears the mark.
	mustSignIn(t, fx, first.URL)
	if err := fx.service.ChangeServer(); err != nil {
		t.Fatal(err)
	}
	if got := shelfOf(fx)[0]; got != (shelfTile{fakeServerID, ShelfSession}) {
		t.Fatalf("%v", got)
	}
	if n := len(shelfOf(fx)); n != 2 {
		t.Fatalf("une tuile par serveur et utilisateur : %d", n)
	}
}

func Test_forget_server_removes_the_record_and_token_then_revokes_best_effort(t *testing.T) {
	server := startFakeEmby(t, fakeEmbyLike(204))
	fx := newFixture(t)
	mustSignIn(t, fx, server.URL)
	if err := fx.service.ChangeServer(); err != nil {
		t.Fatal(err)
	}
	before := len(server.requests())

	if _, err := fx.service.ForgetServer("unknown", fakeUserID); !errors.Is(err, ErrNotFound) {
		t.Fatalf("%v", err)
	}
	revocation, err := fx.service.ForgetServer(fakeServerID, fakeUserID)
	if err != nil {
		t.Fatal(err)
	}
	// Removed before any request: an unreachable server cannot hold it back.
	if len(server.requests()) != before {
		t.Fatal("requête avant l'oubli")
	}
	if storedToken(t, fx) != nil || len(fx.service.Shelf()) != 0 {
		t.Fatal("jeton ou tuile gardés")
	}
	raw, err := os.ReadFile(fx.path)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(raw), server.URL) || strings.Contains(string(raw), "testeur") {
		t.Fatal(string(raw))
	}
	if fx.service.LastServerURL() != nil {
		t.Fatal("adresse gardée")
	}

	if revocation == nil {
		t.Fatal("un jeton à révoquer")
	}
	revocation.Send(bg)
	reqs := server.requests()
	var sent []string
	for _, r := range reqs[before:] {
		sent = append(sent, r.Path)
	}
	if !reflect.DeepEqual(sent, []string{"/emby/System/Info/Public", "/emby/Sessions/Logout"}) {
		t.Fatalf("%v", sent)
	}
	if !rcnNoToken(reqs[before].Auth) {
		t.Fatal(reqs[before].Auth)
	}
	if !strings.Contains(reqs[before+1].Auth, `Token="`+fakeToken+`"`) {
		t.Fatal(reqs[before+1].Auth)
	}
	// Nothing left to forget.
	if _, err := fx.service.ForgetServer(fakeServerID, fakeUserID); !errors.Is(err, ErrNotFound) {
		t.Fatalf("%v", err)
	}
}

func Test_forget_server_never_sends_the_token_to_another_server(t *testing.T) {
	first := startFakeEmby(t, fakeEmbyLike(204))
	fx := newFixture(t)
	mustSignIn(t, fx, first.URL)
	// Another Emby now answers at the saved address.
	other := secondServer(t)
	fx = retarget(t, fx, other.URL)
	if err := fx.service.ChangeServer(); err != nil {
		t.Fatal(err)
	}
	revocation, err := fx.service.ForgetServer(fakeServerID, fakeUserID)
	if err != nil || revocation == nil {
		t.Fatalf("%v %v", revocation, err)
	}
	revocation.Send(bg)
	reqs := other.requests()
	if len(reqs) != 1 {
		t.Fatalf("%+v", reqs)
	}
	for _, r := range reqs {
		if !rcnNoToken(r.Auth) {
			t.Fatal(r.Auth)
		}
	}
	if storedToken(t, fx) != nil {
		t.Fatal("jeton gardé")
	}

	// Unreachable server: forgotten all the same, the revocation just fails.
	fx = newFixture(t)
	mustSignIn(t, fx, first.URL)
	fx = shortTimeouts(retarget(t, fx, "http://127.0.0.1:9"))
	if err := fx.service.ChangeServer(); err != nil {
		t.Fatal(err)
	}
	revocation, err = fx.service.ForgetServer(fakeServerID, fakeUserID)
	if err != nil || revocation == nil {
		t.Fatalf("%v %v", revocation, err)
	}
	if len(fx.service.Shelf()) != 0 {
		t.Fatal("tuile gardée")
	}
	revocation.Send(bg)
	if storedToken(t, fx) != nil {
		t.Fatal("jeton gardé")
	}
}

func Test_forget_server_without_token_only_removes_the_record(t *testing.T) {
	server := startFakeEmby(t, fakeEmbyLike(204))
	fx := newFixture(t)
	mustSignIn(t, fx, server.URL)
	if err := fx.service.SignOut(bg); err != nil {
		t.Fatal(err)
	}
	second := secondServer(t)
	mustSignIn(t, fx, second.URL)
	if err := fx.service.ChangeServer(); err != nil {
		t.Fatal(err)
	}
	if n := len(fx.service.Shelf()); n != 2 {
		t.Fatal(n)
	}

	before := len(server.requests())
	revocation, err := fx.service.ForgetServer(fakeServerID, fakeUserID)
	if err != nil || revocation != nil {
		t.Fatalf("%v %v", revocation, err)
	}
	if len(server.requests()) != before {
		t.Fatal("requête envoyée")
	}
	if got := shelfOf(fx); !reflect.DeepEqual(got, []shelfTile{{"secondsrv", ShelfSession}}) {
		t.Fatalf("%v", got)
	}
	// The address of the server still on the shelf stays prefilled.
	if u := fx.service.LastServerURL(); u == nil || *u != second.URL {
		t.Fatalf("%v", u)
	}
}

// Linked playback calls: capabilities, the session list and the
// `SendString` relay, with the token in the header only.
func Test_linked_playback_calls_use_the_session_relay(t *testing.T) {
	server := startFakeEmby(t, func(r fakeRequest) (int, string) {
		if r.Method == "GET" && strings.HasPrefix(r.Path, "/emby/Sessions?") {
			return 200, `[{"Id":"tv1","DeviceId":"dev-tv","DeviceName":"Shield","Client":"Viewer TV","SupportsRemoteControl":true}]`
		}
		return 204, ""
	})
	url, err := ParseServerURL(server.URL)
	if err != nil {
		t.Fatal(err)
	}
	c, err := NewClient(url, DeviceIdentity{Name: "test", ID: "dev-pc", Version: "0.1.0"})
	if err != nil {
		t.Fatal(err)
	}
	client := c.WithToken(NewAccessToken(fakeToken))
	if client.DeviceID() != "dev-pc" {
		t.Fatal(client.DeviceID())
	}

	if err := client.PostCapabilities(bg); err != nil {
		t.Fatal(err)
	}
	sessions, err := client.Sessions(bg, fakeUserID)
	if err != nil {
		t.Fatal(err)
	}
	if sessions[0].ID != "tv1" || !sessions[0].SupportsRemoteControl {
		t.Fatalf("%+v", sessions[0])
	}
	if err := client.SendString(bg, "tv1", `{"tv":"sync"}`); err != nil {
		t.Fatal(err)
	}

	reqs := server.requests()
	if reqs[0].Method != "POST" || reqs[0].Path != "/emby/Sessions/Capabilities/Full" {
		t.Fatalf("%+v", reqs[0])
	}
	var caps map[string]any
	if err := json.Unmarshal([]byte(reqs[0].Body), &caps); err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(caps["SupportedCommands"], []any{"SendString"}) || caps["SupportsMediaControl"] != true {
		t.Fatal(reqs[0].Body)
	}
	if reqs[1].Path != "/emby/Sessions?ControllableByUserId="+fakeUserID {
		t.Fatal(reqs[1].Path)
	}
	if reqs[2].Path != "/emby/Sessions/tv1/Command" {
		t.Fatal(reqs[2].Path)
	}
	var cmd struct {
		Name      string
		Arguments map[string]string
	}
	if err := json.Unmarshal([]byte(reqs[2].Body), &cmd); err != nil {
		t.Fatal(err)
	}
	if cmd.Name != "SendString" || cmd.Arguments["String"] != `{"tv":"sync"}` {
		t.Fatal(reqs[2].Body)
	}
	for _, r := range reqs {
		if strings.Contains(r.Path, fakeToken) || strings.Contains(r.Body, fakeToken) {
			t.Fatal("le jeton reste hors de l'URL et du corps")
		}
		if !r.HasAuth || !strings.Contains(r.Auth, fakeToken) {
			t.Fatal(r.Auth)
		}
	}

	// A session id that is not one never reaches the server.
	if err := client.SendString(bg, "../x", "{}"); !errors.Is(err, NewInvalidResponse("")) {
		t.Fatalf("%v", err)
	}
	if n := len(server.requests()); n != 3 {
		t.Fatal(n)
	}
}
