package redact

// Tests of `redact` in src-tauri/src/diagnostics.rs, under the same names. The
// diagnostics unit (wave 7) keeps them here.

import "testing"

func eq(t *testing.T, in, want string) {
	t.Helper()
	if got := Redact(in); got != want {
		t.Errorf("Redact(%q)\n got %q\nwant %q", in, got, want)
	}
}

func Test_redacts_query_and_header_secrets(t *testing.T) {
	eq(t, "GET https://emby/Videos/1/stream?static=true&api_key=abc123&x=1 failed",
		"GET https://emby/Videos/1/stream?static=true&api_key=<redacted>&x=1 failed")
	eq(t, "x?ApiKey=abc&y=1", "x?ApiKey=<redacted>&y=1")
	eq(t, "X-Emby-Token: abc", "X-Emby-Token: <redacted>")
	eq(t, "x-emby-token=abc def", "x-emby-token=<redacted> def")
	eq(t, `X-Emby-Authorization: MediaBrowser Client="Viewer", Token="abc"`, "X-Emby-Authorization: <redacted>")
	eq(t, "Authorization: Bearer abc.def", "Authorization: <redacted>")
	eq(t, "headers=X-MediaBrowser-Token: abc,Accept: */*", "headers=X-MediaBrowser-Token: <redacted>,Accept: */*")
}

func Test_redacts_json_and_quoted_secrets(t *testing.T) {
	eq(t, `{"Username":"quentin","Pw":"s3cr\"et","x":1}`, `{"Username":"quentin","Pw":"<redacted>","x":1}`)
	eq(t, `{"AccessToken": "abc", "User": {}}`, `{"AccessToken": "<redacted>", "User": {}}`)
	eq(t, "password='hunter 2' ok", "password='<redacted>' ok")
	eq(t, "access_token=abc;", "access_token=<redacted>;")
	eq(t, "Token=\"abc", "Token=\"<redacted>")
}

func Test_redacts_url_credentials(t *testing.T) {
	eq(t, "open http://user:pa%40ss@nas:8080/x?y and https://plain.host/a@b",
		"open http://<redacted>@nas:8080/x?y and https://plain.host/a@b")
	eq(t, "http://host:8096/emby", "http://host:8096/emby")
}

func Test_redacts_every_line_of_a_message(t *testing.T) {
	eq(t, "a\nAuthorization: x y\r\napi_key=k\n", "a\nAuthorization: <redacted>\r\napi_key=<redacted>\n")
}

func Test_leaves_ordinary_text_alone(t *testing.T) {
	for _, text := range []string{
		"no secret here",
		"rejected token not deleted: keyring",
		"tokens=3 passwords counted",
		"stop report given up",
		"token",
		"api_key=",
		"Durée : 1:23:45, café ☕ token",
	} {
		eq(t, text, text)
	}
	eq(t, "prix=5 € token=é", "prix=5 € token=<redacted>")
}

// Not in Rust: a backslash at the very end of a quoted value, and an empty text.
func Test_go_edges(t *testing.T) {
	eq(t, `pw="ab\`, `pw="<redacted>`)
	eq(t, "", "")
	eq(t, "\n\n", "\n\n")
}
