// Package redact removes secrets from text before it is logged. Port of
// `redact` in src-tauri/src/diagnostics.rs.
//
// Ported early, in wave 3, because the player logs mpv's messages through it
// (player/mpv.rs); the diagnostics unit (wave 7) reuses this package rather than
// porting the function again.
package redact

import (
	"strings"
	"unicode"
)

// Marker replaces every secret value.
const Marker = "<redacted>"

// secretKeys are keys whose value is a secret, longest first so `access_token`
// wins over `token`.
var secretKeys = []string{
	"x-emby-authorization",
	"x-mediabrowser-token",
	"authorization",
	"x-emby-token",
	"access_token",
	"accesstoken",
	"password",
	"api_key",
	"apikey",
	"passwd",
	"token",
	"pw",
}

// wholeLineKeys: authorization headers hold spaces, commas and quotes, so they are
// redacted to the end of the line.
var wholeLineKeys = []string{"x-emby-authorization", "authorization"}

// Redact removes secret values from text, line by line: `key=value`, `key: value`
// and JSON `"key":"value"` for the keys of secretKeys (any ASCII case), and the
// `user:password@` part of URLs.
func Redact(text string) string {
	var out strings.Builder
	out.Grow(len(text))
	for len(text) > 0 {
		// split_inclusive('\n'): each line keeps its newline.
		n := strings.IndexByte(text, '\n') + 1
		if n == 0 {
			n = len(text)
		}
		out.WriteString(redactUserinfo(redactKeys(text[:n])))
		text = text[n:]
	}
	return out.String()
}

func isWord(b byte) bool {
	return b >= 'a' && b <= 'z' || b >= 'A' && b <= 'Z' || b >= '0' && b <= '9' || b == '_'
}

// ASCIILower lowercases ASCII letters only (Rust `to_ascii_lowercase`), so byte
// offsets are kept.
func ASCIILower(s string) string {
	b := []byte(s)
	for i, c := range b {
		if c >= 'A' && c <= 'Z' {
			b[i] = c + ('a' - 'A')
		}
	}
	return string(b)
}

func redactKeys(line string) string {
	// Every offset below is an ASCII byte of a key or a delimiter, so slicing the
	// original line is safe.
	lower := ASCIILower(line)
	lineEnd := len(strings.TrimRight(line, "\r\n"))
	var out strings.Builder
	copied := 0
	for i := 0; i < lineEnd; {
		key, valueStart, ok := secretAt(lower, i, lineEnd)
		if !ok {
			i++
			continue
		}
		start, end := valueSpan(lower, valueStart, lineEnd, contains(wholeLineKeys, key))
		if end > start {
			out.WriteString(line[copied:start])
			out.WriteString(Marker)
			copied = end
		}
		i = max(end, valueStart)
	}
	out.WriteString(line[copied:])
	return out.String()
}

func contains(list []string, s string) bool {
	for _, v := range list {
		if v == s {
			return true
		}
	}
	return false
}

// secretAt gives the key starting at i and where its value starts, when i starts a
// secret key followed by `=` or `:` (after an optional closing quote and spaces).
func secretAt(b string, i, end int) (string, int, bool) {
	if i > 0 && isWord(b[i-1]) {
		return "", 0, false
	}
	key := ""
	for _, k := range secretKeys {
		after := i + len(k)
		if after <= end && b[i:after] == k && (after == end || !isWord(b[after])) {
			key = k
			break
		}
	}
	if key == "" {
		return "", 0, false
	}
	j := i + len(key)
	if j < end && (b[j] == '"' || b[j] == '\'') {
		j++
	}
	for j < end && b[j] == ' ' {
		j++
	}
	if j >= end || (b[j] != '=' && b[j] != ':') {
		return "", 0, false
	}
	j++
	for j < end && b[j] == ' ' {
		j++
	}
	return key, j, true
}

// valueSpan gives the span (start, exclusive end) of the secret value found at
// start: the inside of a quoted value, the rest of the line for authorization
// headers, else up to the next delimiter.
func valueSpan(b string, start, end int, wholeLine bool) (int, int) {
	if wholeLine {
		return start, end
	}
	if start < end && (b[start] == '"' || b[start] == '\'') {
		q := b[start]
		j := start + 1
		for j < end && b[j] != q {
			if b[j] == '\\' {
				j += 2
			} else {
				j++
			}
		}
		return start + 1, min(j, end)
	}
	j := start
	for j < end && !strings.ContainsRune("&\"', ;\t)]}", rune(b[j])) {
		j++
	}
	return start, j
}

// redactUserinfo turns `scheme://user:password@host` into `scheme://<redacted>@host`.
func redactUserinfo(line string) string {
	var out strings.Builder
	rest := line
	for {
		pos := strings.Index(rest, "://")
		if pos < 0 {
			break
		}
		authStart := pos + 3
		out.WriteString(rest[:authStart])
		tail := rest[authStart:]
		authorityEnd := strings.IndexFunc(tail, func(r rune) bool {
			return r == '/' || r == '?' || r == '#' || unicode.IsSpace(r)
		})
		if authorityEnd < 0 {
			authorityEnd = len(tail)
		}
		at := strings.LastIndexByte(tail[:authorityEnd], '@')
		if at >= 0 && strings.Contains(tail[:at], ":") {
			out.WriteString(Marker)
			rest = tail[at:]
		} else {
			rest = tail
		}
	}
	out.WriteString(rest)
	return out.String()
}
