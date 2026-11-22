package updater

import (
	"crypto/ed25519"
	"encoding/base64"
	"testing"
)

func Test_go_semver_parses_strictly(t *testing.T) {
	for _, s := range []string{"1.2.3", "0.0.0", "1.2.3-rc.1", "1.2.3-alpha-1.0a", "1.2.3+build.5", "1.2.3-rc.1+exp.sha.5114f85", "10.20.30"} {
		v, err := ParseVersion(s)
		if err != nil {
			t.Errorf("%s: %v", s, err)
			continue
		}
		if v.String() != s {
			t.Errorf("%s renders as %s", s, v)
		}
	}
	for _, s := range []string{"", "1", "1.2", "1.2.3.4", "01.2.3", "1.02.3", "v1.2.3", " 1.2.3", "1.2.3-", "1.2.3-01", "1.2.3-a..b", "1.2.3+", "1.2.3-é", "1.2.x", "-1.2.3", "99999999999999999999.0.0"} {
		if _, err := ParseVersion(s); err == nil {
			t.Errorf("%q must be rejected", s)
		}
	}
}

func Test_go_semver_orders_by_precedence(t *testing.T) {
	// SemVer 2.0.0 section 11 example, then build metadata as a tie-breaker.
	ordered := []string{
		"1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2",
		"1.0.0-beta.11", "1.0.0-rc.1", "1.0.0", "1.0.0+build.1", "1.0.0+build.2", "1.0.1",
		"1.2.0", "1.10.0", "2.0.0",
	}
	for i := range ordered {
		for j := range ordered {
			a, _ := ParseVersion(ordered[i])
			b, _ := ParseVersion(ordered[j])
			want := 0
			if i < j {
				want = -1
			} else if i > j {
				want = 1
			}
			if got := a.Compare(b); got != want {
				t.Errorf("compare(%s, %s) = %d, want %d", ordered[i], ordered[j], got, want)
			}
		}
	}
}

func Test_go_verify_signature(t *testing.T) {
	pub, priv, err := ed25519.GenerateKey(nil)
	if err != nil {
		t.Fatal(err)
	}
	data := []byte("MZ installer")
	sig := ed25519.Sign(priv, SignedMessage("1.3.0", data))
	key := base64.StdEncoding.EncodeToString(pub)
	for _, v := range []string{"1.3.0", "v1.3.0", " 1.3.0 "} {
		if err := Verify(data, v, base64.StdEncoding.EncodeToString(sig), key); err != nil {
			t.Errorf("valid for %q: %v", v, err)
		}
	}
	if err := Verify(data, "1.3.0", base64.RawStdEncoding.EncodeToString(sig), base64.RawStdEncoding.EncodeToString(pub)); err != nil {
		t.Errorf("unpadded: %v", err)
	}
	bare := base64.StdEncoding.EncodeToString(ed25519.Sign(priv, data))
	for name, c := range map[string][4]string{
		"tampered":          {"MZ installeR", "1.3.0", base64.StdEncoding.EncodeToString(sig), key},
		"other version":     {string(data), "1.4.0", base64.StdEncoding.EncodeToString(sig), key},
		"bytes signed bare": {string(data), "1.3.0", bare, key},
		"short sig":         {string(data), "1.3.0", base64.StdEncoding.EncodeToString(sig[:63]), key},
		"empty sig":         {string(data), "1.3.0", "", key},
		"empty key":         {string(data), "1.3.0", base64.StdEncoding.EncodeToString(sig), ""},
		"short key":         {string(data), "1.3.0", base64.StdEncoding.EncodeToString(sig), base64.StdEncoding.EncodeToString(pub[:31])},
		"garbage key":       {string(data), "1.3.0", base64.StdEncoding.EncodeToString(sig), "not base64!"},
		"minisign blob":     {string(data), "1.3.0", "dW50cnVzdGVkIGNvbW1lbnQ6IHNpZ25hdHVyZSBmcm9tIHRhdXJpIHNlY3JldCBrZXkK", key},
		"truncated version": {string(data), "1.3.", base64.StdEncoding.EncodeToString(sig), key},
	} {
		if err := Verify([]byte(c[0]), c[1], c[2], c[3]); Category(err) != "signature" {
			t.Errorf("%s: %v", name, err)
		}
	}
}

// The exact bytes publish.ts must sign (MAP.md, release-pipeline).
func Test_go_signed_message_layout(t *testing.T) {
	msg := SignedMessage("1.3.0", []byte("abc"))
	want := "viewer-update-v1\x001.3.0\x00"
	if string(msg[:len(want)]) != want || len(msg) != len(want)+64 {
		t.Fatalf("message %q", msg)
	}
	// SHA-512("abc"), FIPS 180-2 test vector.
	if got := base64.StdEncoding.EncodeToString(msg[len(want):]); got != "3a81oZNherrMQXNJriBBMRLm+k6JqX6iCp7u5ktV05ohkpkqJ0/BqDa6PCOj/uu9RU1EI2Q86A4qmslPpUyknw==" {
		t.Errorf("digest %s", got)
	}
}

// it_escapes_correctly_for_nsis (plugin 2.14.1), same cases.
func Test_it_escapes_correctly_for_nsis(t *testing.T) {
	cases := []string{
		"something", "--flag", "--empty=", "--arg=value", "some space", "--arg value",
		"--arg=unwrapped space", `--arg="wrapped"`, `--arg="wrapped space"`,
		`--arg=midword"wrapped space"`, "",
	}
	escaped := []string{
		"something", "--flag", "--empty=", "--arg=value", `"some space"`, `"--arg value"`,
		`"--arg=unwrapped space"`, `--arg=\"wrapped\"`, `"--arg=\"wrapped space\""`,
		`"--arg=midword\"wrapped space\""`, `""`,
	}
	for i, c := range cases {
		if got := escapeNSISArg(c); got != escaped[i] {
			t.Errorf("escape(%q) = %s, want %s", c, got, escaped[i])
		}
	}
	for c, want := range map[string]string{`a\b`: `a\b`, `a\"b`: `a\\\"b`, `C:\dir\`: `C:\dir\`, `C:/my dir\`: `"C:/my dir\\"`} {
		if got := escapeNSISArg(c); got != want {
			t.Errorf("escape(%q) = %s, want %s", c, got, want)
		}
	}
}
