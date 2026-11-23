package player

import (
	"strings"

	"viewer/internal/redact"
)

// mpvLogText is a line of mpv's log as the app logs it: secrets redacted, and
// every http(s) URL cut down to its scheme and host. mpv names the file it fails
// to open, and a debrid link (Stremio) carries its key in the path.
func mpvLogText(text string) string {
	return redact.Redact(maskURLPaths(text))
}

// urlEnd tells where a URL quoted in a log line stops.
func urlEnd(c byte) bool {
	return c <= ' ' || strings.IndexByte(`'"<>()[]{},`, c) >= 0
}

// maskURLPaths keeps the scheme and host of each http(s) URL in text and replaces
// any user info, path, query or fragment: `https://host/<redacted>`.
func maskURLPaths(text string) string {
	lower := redact.ASCIILower(text)
	var out strings.Builder
	i := 0
	for {
		j := strings.Index(lower[i:], "http")
		if j < 0 {
			out.WriteString(text[i:])
			return out.String()
		}
		j += i
		rest := lower[j:]
		var scheme int
		switch {
		case strings.HasPrefix(rest, "https://"):
			scheme = len("https://")
		case strings.HasPrefix(rest, "http://"):
			scheme = len("http://")
		default:
			out.WriteString(text[i : j+4])
			i = j + 4
			continue
		}
		start := j + scheme
		end := start
		for end < len(text) && !urlEnd(text[end]) {
			end++
		}
		authority := text[start:end]
		cut := strings.IndexAny(authority, "/?#")
		if cut >= 0 {
			authority = authority[:cut]
		}
		if at := strings.LastIndexByte(authority, '@'); at >= 0 {
			authority = authority[at+1:]
		}
		out.WriteString(text[i:start])
		out.WriteString(authority)
		if cut >= 0 || authority != text[start:end] {
			out.WriteString("/" + redact.Marker)
		}
		i = end
	}
}
