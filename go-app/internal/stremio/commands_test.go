package stremio

import (
	"context"
	"encoding/json"
	"net/http"
	"slices"
	"strings"
	"testing"

	"viewer/internal/bridge"
)

// call runs a handler the way the bridge does and returns the JSON result or the
// serialized rejection.
func call(t *testing.T, h map[string]Handler, cmd, args string) (string, string) {
	t.Helper()
	res, err := h[cmd](context.Background(), json.RawMessage(args))
	if err != nil {
		return "", string(bridge.MarshalError(err))
	}
	out, mErr := json.Marshal(res)
	if mErr != nil {
		t.Fatal(mErr)
	}
	return string(out), ""
}

func TestHandlersNames(t *testing.T) {
	svc, _, _, _ := newTestService(t)
	var names []string
	for n := range Handlers(svc) {
		names = append(names, n)
	}
	slices.Sort(names)
	if !slices.Equal(names, []string{"stremio_sign_in", "stremio_sign_out", "stremio_status"}) {
		t.Fatal(names)
	}
}

func TestHandlersSignInStatusSignOut(t *testing.T) {
	svc, f, store, _ := newTestService(t)
	h := Handlers(svc)
	if out, err := call(t, h, "stremio_status", `{}`); err != "" || out != `{"state":"signed_out","addons":[]}` {
		t.Fatal(out, err)
	}
	out, err := call(t, h, "stremio_sign_in", `{"email":"owner@example.com","password":"hunter2"}`)
	if err != "" || !strings.HasPrefix(out, `{"state":"signed_in","addons":[{"name":"TMDB Collections","movie":true,"series":false},`) {
		t.Fatal(out, err)
	}
	assertNoSecret(t, "réponse", out, f.URL)
	if out, err := call(t, h, "stremio_sign_out", `{}`); err != "" || out != `{"state":"signed_out","addons":[]}` {
		t.Fatal(out, err)
	}
	if stored(t, store) != "" {
		t.Fatal("coffre non vidé")
	}
}

func TestHandlersErrors(t *testing.T) {
	svc, f, _, _ := newTestService(t)
	h := Handlers(svc)
	f.login = string(fixture(t, "login_wrong_password"))
	if _, err := call(t, h, "stremio_sign_in", `{"email":"owner@example.com","password":"hunter2"}`); err != `{"kind":"rejected","message":"Adresse e-mail ou mot de passe incorrect."}` {
		t.Fatal(err)
	}
	if _, err := call(t, h, "stremio_sign_in", `{"email":" ","password":"hunter2"}`); err != `{"kind":"invalid","message":"Saisis ton adresse e-mail et ton mot de passe."}` {
		t.Fatal(err)
	}
	f.handler = func(w http.ResponseWriter, _ *http.Request) { closeConnection(w) }
	if _, err := call(t, h, "stremio_sign_in", `{"email":"owner@example.com","password":"hunter2"}`); err != `{"kind":"unreachable","message":"Stremio ne répond pas. Réessaie plus tard."}` {
		t.Fatal(err)
	}
}

func TestHandlersRefuseBadArgs(t *testing.T) {
	svc, f, _, _ := newTestService(t)
	h := Handlers(svc)
	cases := []struct{ cmd, args, want string }{
		{"stremio_sign_in", `{"email":"owner@example.com"}`, "command stremio_sign_in missing required key password"},
		{"stremio_sign_in", `{"password":"hunter2"}`, "command stremio_sign_in missing required key email"},
		{"stremio_sign_in", `{"email":"owner@example.com","password":null}`, "command stremio_sign_in missing required key password"},
		{"stremio_sign_in", `{"email":"owner@example.com","password":"hunter2","remember":true}`, "invalid args for command `stremio_sign_in`"},
		{"stremio_sign_in", `{"email":"owner@example.com","password":7}`, "invalid args for command `stremio_sign_in`"},
		{"stremio_sign_in", `["owner@example.com","hunter2"]`, "invalid args for command `stremio_sign_in`"},
		{"stremio_status", `{"x":1}`, "invalid args for command `stremio_status`"},
		{"stremio_sign_out", `{"x":1}`, "invalid args for command `stremio_sign_out`"},
		{"stremio_status", `[]`, "invalid args for command `stremio_status`"},
	}
	for _, c := range cases {
		_, err := call(t, h, c.cmd, c.args)
		var msg string
		if json.Unmarshal([]byte(err), &msg) != nil || !strings.HasPrefix(msg, c.want) {
			t.Errorf("%s %s : %s", c.cmd, c.args, err)
		}
		if strings.Contains(err, "hunter2") || strings.Contains(err, "owner@") {
			t.Errorf("%s %s : l'erreur reprend un argument : %s", c.cmd, c.args, err)
		}
	}
	if len(f.log()) != 0 {
		t.Fatalf("requêtes %+v", f.log())
	}
}
