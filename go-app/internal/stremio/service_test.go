package stremio

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"slices"
	"strings"
	"testing"

	"viewer/internal/emby"
)

func newTestService(t testing.TB) (*Service, *fakeServer, *emby.MemoryCredentialStore, *syncBuffer) {
	t.Helper()
	logs := captureLogs(t)
	f := newFakeServer(t)
	store := &emby.MemoryCredentialStore{}
	t.Cleanup(func() { assertNoSecret(t, "journal", logs.String(), f.URL) })
	return NewService(store, NewClient(f.URL, "1")), f, store, logs
}

func stored(t testing.TB, store *emby.MemoryCredentialStore) string {
	t.Helper()
	tok, err := store.Load(Account)
	if err != nil {
		t.Fatal(err)
	}
	if tok == nil {
		return ""
	}
	return tok.Expose()
}

func collectionCalls(f *fakeServer) int {
	n := 0
	for _, r := range f.log() {
		if r.URI == "/api/addonCollectionGet" {
			n++
		}
	}
	return n
}

func TestServiceSignInThenStatus(t *testing.T) {
	svc, f, store, _ := newTestService(t)
	ctx := context.Background()
	if got := svc.Status(ctx); got.State != StateSignedOut || got.Addons == nil || len(got.Addons) != 0 {
		t.Fatalf("%+v", got)
	}
	if err := svc.SignIn(ctx, "  owner@example.com ", "hunter2"); err != nil {
		t.Fatal(err)
	}
	if stored(t, store) != fixtureKey {
		t.Fatal("clé absente du coffre")
	}
	var body map[string]any
	_ = json.Unmarshal([]byte(f.log()[0].Body), &body)
	if body["email"] != "owner@example.com" {
		t.Fatalf("e-mail %v", body["email"])
	}
	want := []AddonInfo{
		{"TMDB Collections", true, false},
		{"Local Files (without catalog support)", true, true},
		{"Lumio · Profil", true, true},
		{"AIOSources", true, true},
		{"StreamFusionReborn", true, true},
		{"Wastream | AD", true, true},
		{"Comet | AD", true, true},
		{"TorrentsDB AD", true, true},
	}
	for range 2 {
		got := svc.Status(ctx)
		if got.State != StateSignedIn || !slices.Equal(got.Addons, want) {
			t.Fatalf("%+v", got)
		}
	}
	if n := collectionCalls(f); n != 1 {
		t.Fatalf("collection demandée %d fois", n)
	}
	addons, err := svc.Addons(ctx)
	if err != nil || len(addons) != 10 || collectionCalls(f) != 1 {
		t.Fatalf("%d addons, %v", len(addons), err)
	}
	out, err := json.Marshal(svc.Status(ctx))
	if err != nil || !strings.HasPrefix(string(out), `{"state":"signed_in","addons":[{"name":"TMDB Collections","movie":true,"series":false}`) {
		t.Fatalf("%s %v", out, err)
	}
	assertNoSecret(t, "statut", string(out), f.URL)
}

func TestServiceWrongPassword(t *testing.T) {
	svc, f, store, _ := newTestService(t)
	f.login = string(fixture(t, "login_wrong_password"))
	err := svc.SignIn(context.Background(), "owner@example.com", "hunter2")
	wantKind(t, err, KindRejected)
	if stored(t, store) != "" {
		t.Fatal("coffre rempli")
	}
	if got := svc.Status(context.Background()); got.State != StateSignedOut {
		t.Fatalf("%+v", got)
	}
}

func TestServiceSignInRefusesEmptyFields(t *testing.T) {
	svc, f, _, _ := newTestService(t)
	for _, c := range [][2]string{{"", "pw"}, {"   ", "pw"}, {"a@b.c", ""}} {
		err := svc.SignIn(context.Background(), c[0], c[1])
		if e := wantKind(t, err, KindInvalid); e.Addon != "" {
			t.Fatal(e)
		}
	}
	if len(f.log()) != 0 {
		t.Fatalf("requêtes %+v", f.log())
	}
}

func TestServiceSessionExpired(t *testing.T) {
	svc, f, store, _ := newTestService(t)
	ctx := context.Background()
	if err := store.Save(Account, emby.NewAccessToken(fixtureKey)); err != nil {
		t.Fatal(err)
	}
	f.collection = string(fixture(t, "addon_collection_session_expired"))
	for range 2 {
		if got := svc.Status(ctx); got.State != StateReauthRequired || len(got.Addons) != 0 {
			t.Fatalf("%+v", got)
		}
	}
	if n := collectionCalls(f); n != 1 {
		t.Fatalf("collection demandée %d fois", n)
	}
	if _, err := svc.Addons(ctx); !errors.Is(err, ErrReauthRequired) {
		t.Fatal(err)
	}
	if stored(t, store) != fixtureKey {
		t.Fatal("la clé est gardée jusqu'à la déconnexion")
	}
	if err := svc.SignOut(); err != nil {
		t.Fatal(err)
	}
	if stored(t, store) != "" {
		t.Fatal("coffre non vidé")
	}
	if got := svc.Status(ctx); got.State != StateSignedOut {
		t.Fatalf("%+v", got)
	}
	if _, err := svc.Addons(ctx); !errors.Is(err, ErrSignedOut) {
		t.Fatal(err)
	}
	// Signing in again clears the refusal.
	f.collection = f.rewrittenCollection(nil)
	if err := svc.SignIn(ctx, "owner@example.com", "hunter2"); err != nil {
		t.Fatal(err)
	}
	if got := svc.Status(ctx); got.State != StateSignedIn {
		t.Fatalf("%+v", got)
	}
}

func TestServiceUnreachableThenUp(t *testing.T) {
	svc, f, store, _ := newTestService(t)
	ctx := context.Background()
	if err := store.Save(Account, emby.NewAccessToken(fixtureKey)); err != nil {
		t.Fatal(err)
	}
	f.handler = func(w http.ResponseWriter, _ *http.Request) { closeConnection(w) }
	if got := svc.Status(ctx); got.State != StateUnreachable {
		t.Fatalf("%+v", got)
	}
	if _, err := svc.Addons(ctx); kindOf(err) != KindUnreachable {
		t.Fatal(err)
	}
	f.mu.Lock()
	f.handler = nil
	f.mu.Unlock()
	if got := svc.Status(ctx); got.State != StateSignedIn || len(got.Addons) != 8 {
		t.Fatalf("%+v", got)
	}
}

func TestServiceSignOutForgetsTheCollection(t *testing.T) {
	svc, f, store, _ := newTestService(t)
	ctx := context.Background()
	if err := svc.SignIn(ctx, "owner@example.com", "hunter2"); err != nil {
		t.Fatal(err)
	}
	if got := svc.Status(ctx); got.State != StateSignedIn {
		t.Fatalf("%+v", got)
	}
	if err := svc.SignOut(); err != nil {
		t.Fatal(err)
	}
	if stored(t, store) != "" || svc.Status(ctx).State != StateSignedOut {
		t.Fatal("toujours connecté")
	}
	// Signing out twice is fine; there is no API logout call.
	if err := svc.SignOut(); err != nil {
		t.Fatal(err)
	}
	for _, r := range f.log() {
		if strings.Contains(r.URI, "logout") {
			t.Fatalf("appel %s", r.URI)
		}
	}
	if err := svc.SignIn(ctx, "owner@example.com", "hunter2"); err != nil {
		t.Fatal(err)
	}
	if svc.Status(ctx).State != StateSignedIn || collectionCalls(f) != 2 {
		t.Fatalf("collection redemandée : %d", collectionCalls(f))
	}
}

func TestServiceNeverPrintsSecrets(t *testing.T) {
	svc, f, _, _ := newTestService(t)
	if err := svc.SignIn(context.Background(), "owner@example.com", "hunter2"); err != nil {
		t.Fatal(err)
	}
	svc.Status(context.Background())
	for _, verb := range []string{"%v", "%+v", "%#v", "%s", "%x", "%d"} {
		out := fmt.Sprintf(verb, svc)
		assertNoSecret(t, "service", out, f.URL)
		if !strings.Contains(out, "StremioService(***)") {
			t.Errorf("%s : %s", verb, out)
		}
	}
}
