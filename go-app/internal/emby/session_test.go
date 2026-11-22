package emby

// tests.rs, sign-in to watched changes (lines 928–1930), through EmbyService.

import (
	"errors"
	"os"
	"reflect"
	"strings"
	"sync/atomic"
	"testing"
)

func Test_sign_in_stores_token_securely_and_sends_auth_in_header(t *testing.T) {
	server := startFakeEmby(t, fakeEmbyLike(204))
	fx := newFixture(t)
	user, err := fx.service.SignIn(bg, server.URL, " testeur ", fakePassword)
	if err != nil {
		t.Fatal(err)
	}

	if user.UserName != "testeur" || user.IsAdministrator || user.Server.Name != "ServeurTest" || user.Server.Encrypted {
		t.Fatalf("%+v", user)
	}
	if tok := storedToken(t, fx); tok == nil || tok.Expose() != fakeToken {
		t.Fatal("jeton non stocké")
	}
	if cur := fx.service.CurrentUser(); cur == nil || !reflect.DeepEqual(*cur, user) {
		t.Fatalf("utilisateur courant %+v", cur)
	}

	reqs := server.requests()
	auth := reqs[1]
	if auth.Path != "/emby/Users/AuthenticateByName" {
		t.Fatal(auth.Path)
	}
	if !strings.Contains(auth.Body, `"Username":"testeur"`) {
		t.Fatal(auth.Body)
	}
	header := auth.Auth
	if !strings.HasPrefix(header, `Emby Client="Viewer"`) || !strings.Contains(header, `Device="PC test bureau"`) || strings.Contains(header, "Token=") {
		t.Fatal(header)
	}
	// Neither the token nor the password ever appears in a URL.
	for _, r := range reqs {
		if strings.Contains(r.Path, fakeToken) || strings.Contains(r.Path, fakePassword) {
			t.Fatal(r.Path)
		}
	}

	// session.json has the metadata needed to restore, and no secret.
	raw, err := os.ReadFile(fx.path)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(raw), fakeToken) || strings.Contains(string(raw), fakePassword) {
		t.Fatal(string(raw))
	}
	parsed := sessionFile(t, fx)
	if parsed.Session.UserID != fakeUserID {
		t.Fatal(parsed.Session.UserID)
	}
	if !strings.Contains(header, `DeviceId="`+parsed.DeviceID+`"`) {
		t.Fatal(header)
	}
}

func Test_wrong_password_is_reported_and_nothing_is_stored(t *testing.T) {
	server := startFakeEmby(t, fakeEmbyLike(204))
	fx := newFixture(t)
	_, err := fx.service.SignIn(bg, server.URL, "testeur", "nope")
	if !errors.Is(err, ErrInvalidCredentials) {
		t.Fatalf("%v", err)
	}
	wantKind(t, err, "invalidCredentials")
	if storedToken(t, fx) != nil || fx.service.CurrentUser() != nil {
		t.Fatal("rien ne doit être gardé")
	}
	if _, err := os.Stat(fx.path); !os.IsNotExist(err) {
		t.Fatalf("session.json écrit : %v", err)
	}
}

func Test_restore_after_restart_reuses_token_and_keeps_device_id(t *testing.T) {
	server := startFakeEmby(t, fakeEmbyLike(204))
	fx := newFixture(t)
	mustSignIn(t, fx, server.URL)
	deviceBefore := sessionFile(t, fx).DeviceID

	fx = reopen(fx.store, fx.path)
	user := authenticated(t, mustRestore(t, fx))
	if user.UserID != fakeUserID {
		t.Fatal(user.UserID)
	}
	header := server.last(t).Auth
	if !strings.Contains(header, `Token="`+fakeToken+`"`) || !strings.Contains(header, `DeviceId="`+deviceBefore+`"`) {
		t.Fatal(header)
	}
}

func Test_restore_with_revoked_token_forgets_it(t *testing.T) {
	server := startFakeEmby(t, fakeEmbyLike(204))
	fx := newFixture(t)
	mustSignIn(t, fx, server.URL)
	// Token revoked on the server side (e.g. device removed in the dashboard).
	if err := fx.store.Save(CredentialAccount(fakeServerID, fakeUserID), NewAccessToken("revoked")); err != nil {
		t.Fatal(err)
	}

	fx = reopen(fx.store, fx.path)
	if k := reauthKind(t, mustRestore(t, fx)); k != "sessionExpired" {
		t.Fatal(k)
	}
	if storedToken(t, fx) != nil || fx.service.CurrentUser() != nil {
		t.Fatal("jeton ou session gardés")
	}
	if u := fx.service.LastServerURL(); u == nil || *u != server.URL {
		t.Fatalf("%v", u)
	}
	// After a restart the reauthentication screen is still prefilled.
	fx = reopen(fx.store, fx.path)
	if k := reauthKind(t, mustRestore(t, fx)); k != "sessionExpired" {
		t.Fatal(k)
	}
	// Signing in again clears the hint.
	mustSignIn(t, fx, server.URL)
	if file := sessionFile(t, fx); file.Reauth != nil {
		t.Fatalf("%+v", file.Reauth)
	}
}

func Test_restore_with_nothing_saved_is_signed_out(t *testing.T) {
	fx := newFixture(t)
	if s := mustRestore(t, fx); s.State != StateSignedOut {
		t.Fatalf("%+v", s)
	}
}

func Test_sign_out_revokes_on_server_and_forgets_locally_even_if_logout_fails(t *testing.T) {
	for _, logoutStatus := range []int{204, 500} {
		server := startFakeEmby(t, fakeEmbyLike(logoutStatus))
		fx := newFixture(t)
		mustSignIn(t, fx, server.URL)
		if err := fx.service.SignOut(bg); err != nil {
			t.Fatal(err)
		}

		logout := server.last(t)
		if logout.Path != "/emby/Sessions/Logout" || !strings.Contains(logout.Auth, `Token="`+fakeToken+`"`) {
			t.Fatalf("%+v", logout)
		}
		if storedToken(t, fx) != nil {
			t.Fatalf("status %d", logoutStatus)
		}
		if fx.service.CurrentUser() != nil {
			t.Fatal("utilisateur gardé")
		}
		if _, err := fx.service.Client(); !errors.Is(err, ErrNotSignedIn) {
			t.Fatal(err)
		}
		fx = reopen(fx.store, fx.path)
		if s := mustRestore(t, fx); s.State != StateSignedOut {
			t.Fatalf("%+v", s)
		}
	}
}

// A played item starts from the beginning even with a saved position (session.rs
// `filter(|u| !u.played)`); tests.rs has no case for it.
func Test_go_resume_of_a_played_item_starts_at_zero(t *testing.T) {
	base := fakeBrowseLike(new(atomic.Bool))
	server := startFakeEmby(t, func(r fakeRequest) (int, string) {
		if path, _ := splitQuery(r.Path); path == "/emby/Users/"+fakeUserID+"/Items/30207" {
			return 200, strings.Replace(fakeFixture("movie_detail"), `"Played":false`, `"Played":true`, 1)
		}
		return base(r)
	})
	fx := newFixture(t)
	mustSignIn(t, fx, server.URL)
	res, err := fx.service.PreparePlayback(bg, "30207", true)
	if err != nil {
		t.Fatal(err)
	}
	if res.Plan.StartTicks != 0 {
		t.Fatal(res.Plan.StartTicks)
	}
}
