package emby

import (
	"encoding/json"
	"errors"
	"testing"
)

func okURL(t *testing.T, input string) string {
	t.Helper()
	u, err := ParseServerURL(input)
	if err != nil {
		t.Fatalf("%q: %v", input, err)
	}
	return u.String()
}

// emby/server_url.rs normalizes_common_inputs
func Test_normalizes_common_inputs(t *testing.T) {
	for in, want := range map[string]string{
		"http://192.168.1.10:8096":                "http://192.168.1.10:8096",
		"192.168.1.10:8096":                       "http://192.168.1.10:8096",
		" http://192.168.1.10:8096/ ":             "http://192.168.1.10:8096",
		"http://emby.local:8096/emby":             "http://emby.local:8096",
		"http://192.168.1.10:8096/web/index.html": "http://192.168.1.10:8096",
		"https://media.example.com/proxy/":        "https://media.example.com/proxy",
	} {
		if got := okURL(t, in); got != want {
			t.Errorf("%q → %q, want %q", in, got, want)
		}
	}
	u, err := ParseServerURL("http://10.0.0.2:8096")
	if err != nil {
		t.Fatal(err)
	}
	if got := u.API("/System/Info/Public"); got != "http://10.0.0.2:8096/emby/System/Info/Public" {
		t.Fatal(got)
	}
}

// emby/server_url.rs plain_http_only_on_local_network
func Test_plain_http_only_on_local_network(t *testing.T) {
	for _, local := range []string{
		"http://127.0.0.1:8096", "http://10.1.2.3", "http://172.16.0.1", "http://192.168.0.5",
		"http://169.254.1.1", "http://[::1]:8096", "http://[fd12::1]", "http://localhost:8096",
		"http://emby.home.arpa", "http://emby.lan",
	} {
		if _, err := ParseServerURL(local); err != nil {
			t.Errorf("%s: %v", local, err)
		}
	}
	for _, remote := range []string{"http://8.8.8.8:8096", "http://media.example.com", "http://172.32.0.1"} {
		if _, err := ParseServerURL(remote); !errors.Is(err, ErrInsecureRemoteURL) {
			t.Errorf("%s: %v", remote, err)
		}
	}
	if _, err := ParseServerURL("https://8.8.8.8"); err != nil {
		t.Fatal(err)
	}
}

// emby/server_url.rs rejects_unsafe_or_malformed_urls
func Test_rejects_unsafe_or_malformed_urls(t *testing.T) {
	for _, bad := range []string{
		"", "ftp://192.168.1.10", "file:///C:/x", "http://user:pw@192.168.1.10",
		"http://192.168.1.10/?api_key=x", "http://192.168.1.10/#x", "http://",
	} {
		if _, err := ParseServerURL(bad); !errors.Is(err, NewInvalidURL("")) {
			t.Errorf("%q: %v", bad, err)
		}
	}
}

// WHATWG URL parsing as the `url` crate 2.5.8 does it (checked against the crate,
// go-app/research/server-url.md): default ports, case, numeric IPv4 forms, IPv6
// form, dot segments, backslashes, tabs and newlines.
func TestServerURLFollowsTheURLCrate(t *testing.T) {
	type want struct {
		url   string
		local bool
		kind  ErrorKind
	}
	for in, w := range map[string]want{
		"http://EMBY.Local:80/":                {url: "http://emby.local", local: true},
		"https://Media.Example.com:443/x":      {url: "https://media.example.com/x"},
		"http://127.1:8096":                    {url: "http://127.0.0.1:8096", local: true},
		"http://0x7f.0.0.1":                    {url: "http://127.0.0.1", local: true},
		"http://2130706433":                    {url: "http://127.0.0.1", local: true},
		"http://0300.0250.1.10":                {url: "http://192.168.1.10", local: true},
		"http://192.168.1.10.":                 {url: "http://192.168.1.10", local: true},
		"http://[0:0:0:0:0:0:0:1]:8096":        {url: "http://[::1]:8096", local: true},
		"http://[FE80::1]":                     {url: "http://[fe80::1]", local: true},
		"http://[::ffff:192.168.1.1]":          {kind: KindInsecureRemoteURL},
		"https://[::ffff:192.168.1.1]":         {url: "https://[::ffff:c0a8:101]"},
		"http://192.168.1.10/a/./b/../c":       {url: "http://192.168.1.10/a/c", local: true},
		"http://192.168.1.10/a/%2e%2E/c":       {url: "http://192.168.1.10/c", local: true},
		"http:\\\\192.168.1.10\\proxy":         {kind: KindInsecureRemoteURL}, // no "://": the host is "http"
		"http://192.168.1.10\\proxy\\":         {url: "http://192.168.1.10/proxy", local: true},
		"http://\\192.168.1.10/x":              {url: "http://192.168.1.10/x", local: true},
		"http://192.168.1.10/a^b|c":            {url: "http://192.168.1.10/a^b|c", local: true},
		"http:192.168.1.10://x":                {url: "http://192.168.1.10//x", local: true},
		"http://192.168.\t1.10\n":              {url: "http://192.168.1.10", local: true},
		"http://192.168.1.10/my proxy":         {url: "http://192.168.1.10/my%20proxy", local: true},
		"http://192.168.1.10/é":                {url: "http://192.168.1.10/%C3%A9", local: true},
		"http://192.168.1.10:08096":            {url: "http://192.168.1.10:8096", local: true},
		"http://192.168.1.10:":                 {url: "http://192.168.1.10", local: true},
		"http://@192.168.1.10":                 {url: "http://192.168.1.10", local: true},
		"http://emby%2Elocal":                  {url: "http://emby.local", local: true},
		"http://192.168.1.10/proxy/emby/web/x": {url: "http://192.168.1.10/proxy", local: true},
		"http://192.168.1.10/website/web":      {url: "http://192.168.1.10/website/web", local: true},
		"http://192.168.1.10//emby":            {url: "http://192.168.1.10", local: true},
		"HTTP://192.168.1.10":                  {url: "http://192.168.1.10", local: true},
		"http://localhost.":                    {kind: KindInsecureRemoteURL},
		"http://0.0.0.0":                       {kind: KindInsecureRemoteURL},
		"http://192.168.1.10:65536":            {kind: KindInvalidURL},
		"http://192.168.1.10:80a":              {kind: KindInvalidURL},
		"http://256.1.1.1":                     {kind: KindInvalidURL},
		"http://1.2.3.4.5":                     {kind: KindInvalidURL},
		"http://[::1":                          {kind: KindInvalidURL},
		"http://[fe80::1%25eth0]":              {kind: KindInvalidURL},
		"http://a b.local":                     {kind: KindInvalidURL},
		"http://émby.local":                    {url: "http://xn--mby-9la.local", local: true},
		"http://ÉMBY.local:8096":               {url: "http://xn--mby-9la.local:8096", local: true},
		"http://a⿰b.local":                     {kind: KindInvalidURL}, // IDNA rejects it, as the url crate does
		"http://:pw@192.168.1.10":              {kind: KindInvalidURL},
		"http://192.168.1.10?":                 {kind: KindInvalidURL},
		"http://192.168.1.10#":                 {kind: KindInvalidURL},
		"1http://x":                            {kind: KindInvalidURL},
		"mailto://x":                           {kind: KindInvalidURL},
		"ws://":                                {kind: KindInvalidURL},
		"http://@":                             {kind: KindInvalidURL},
		"http://0x":                            {kind: KindInsecureRemoteURL}, // 0.0.0.0
		"https://0x":                           {url: "https://0.0.0.0"},
		"http://1.0x":                          {kind: KindInsecureRemoteURL}, // 1.0.0.0
		"http://09.1.1.1":                      {kind: KindInvalidURL},
		"http://a_b.local":                     {url: "http://a_b.local", local: true},
		"https://x/a{b}`c\"d<e>":               {url: "https://x/a%7Bb%7D%60c%22d%3Ce%3E"},
		"https://x/%zz":                        {url: "https://x/%zz"},
	} {
		u, err := ParseServerURL(in)
		if w.kind != "" {
			if k, _ := KindOf(err); k != w.kind {
				t.Errorf("%q: %v (%v), want %s", in, err, u, w.kind)
			}
			continue
		}
		if err != nil {
			t.Errorf("%q: %v", in, err)
			continue
		}
		if u.String() != w.url || u.IsLocal() != w.local {
			t.Errorf("%q → %q local %v, want %q local %v", in, u.String(), u.IsLocal(), w.url, w.local)
		}
	}
}

type serverURLRow struct {
	Input string  `json:"input"`
	URL   *string `json:"url,omitempty"`
	HTTPS *bool   `json:"https,omitempty"`
	Local *bool   `json:"local,omitempty"`
	API   *string `json:"api,omitempty"`
	Error any     `json:"error,omitempty"` // EmbyError, or the golden {kind, message}
}

func TestGolden_server_url_normalize(t *testing.T) {
	var golden []struct {
		Error *errorJSON `json:"error"`
	}
	if err := json.Unmarshal(readGolden(t, "server_url.normalize.json"), &golden); err != nil {
		t.Fatal(err)
	}
	inputs := []string{
		"http://192.168.1.10:8096", "192.168.1.10:8096", " http://192.168.1.10:8096/ ",
		"http://emby.local:8096/emby", "http://192.168.1.10:8096/web/index.html", "http://192.168.1.10:8096/proxy/web",
		"https://media.example.com/proxy/", "https://media.example.com/emby/", "https://8.8.8.8",
		"http://127.0.0.1:8096", "http://10.1.2.3", "http://172.16.0.1", "http://169.254.1.1",
		"http://[::1]:8096", "http://[fd12::1]", "http://localhost:8096", "http://emby.home.arpa",
		"http://emby.lan", "http://8.8.8.8:8096", "http://media.example.com", "http://172.32.0.1",
		"", "   ", "ftp://192.168.1.10", "file:///C:/x", "http://user:pw@192.168.1.10",
		"http://192.168.1.10/?api_key=x", "http://192.168.1.10/#x", "http://",
	}
	if len(inputs) != len(golden) {
		t.Fatalf("%d inputs, golden %d", len(inputs), len(golden))
	}
	var rows []serverURLRow
	for i, in := range inputs {
		row := serverURLRow{Input: in}
		u, err := ParseServerURL(in)
		if err != nil {
			var e EmbyError
			if !errors.As(err, &e) {
				t.Fatalf("%q: %v", in, err)
			}
			// Malformed-address texts come from the url crate: kind only for these.
			row.Error = e
			if e.Kind == KindInvalidURL && golden[i].Error != nil && golden[i].Error.Kind == KindInvalidURL &&
				isURLCrateText(e.Detail) {
				row.Error = golden[i].Error
			}
		} else {
			s, https, local, api := u.String(), u.IsHTTPS(), u.IsLocal(), u.API("/System/Info/Public")
			row.URL, row.HTTPS, row.Local, row.API = &s, &https, &local, &api
		}
		rows = append(rows, row)
	}
	assertTableGolden(t, "server_url.normalize.json", rows)
}

// isURLCrateText: a parse error text of the url crate (English), not one of ours.
func isURLCrateText(detail string) bool {
	switch detail {
	case "empty host", "invalid port number", "invalid IPv4 address", "invalid IPv6 address",
		"invalid domain character", "relative URL without a base", "invalid international domain name":
		return true
	}
	return false
}

func TestGolden_errors_service_from_server_url(t *testing.T) {
	_, err := ParseServerURL("")
	assertTypedGolden(t, "errors.service.invalid_url.json", err)
	_, err = ParseServerURL("http://media.example.com")
	assertTypedGolden(t, "errors.service.insecure_remote_url.json", err)
}
