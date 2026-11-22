package player

import (
	"os"
	"path/filepath"
	"strings"
	"unicode"

	"viewer/internal/redact"
)

// SourceKind is the variant of a MediaSource.
type SourceKind int

const (
	SourceLocalFile SourceKind = iota
	SourceHTTP
)

// MediaSource is a validated source: only local files and http(s) are accepted from
// the app layer, so mpv-specific protocols (`av://`, `edl://`, `lavfi`…) cannot be
// injected. Value is the path or the URL.
type MediaSource struct {
	Kind  SourceKind
	Value string
}

// ParseSource is `MediaSource::parse`.
func ParseSource(raw string) (MediaSource, error) {
	raw = strings.TrimFunc(raw, unicode.IsSpace)
	lower := redact.ASCIILower(raw)
	if strings.HasPrefix(lower, "http://") || strings.HasPrefix(lower, "https://") {
		if strings.IndexFunc(raw, unicode.IsSpace) >= 0 {
			return MediaSource{}, invalidSource("URL contenant des espaces")
		}
		return MediaSource{Kind: SourceHTTP, Value: raw}, nil
	}
	// Reject anything that looks like a URL scheme other than a drive letter (C:\…).
	if idx := strings.Index(raw, "://"); idx > 1 {
		return MediaSource{}, invalidSource("protocole non autorisé")
	}
	if !filepath.IsAbs(raw) {
		return MediaSource{}, invalidSource("chemin local non absolu")
	}
	if info, err := os.Stat(raw); err != nil || !info.Mode().IsRegular() {
		return MediaSource{}, invalidSource("fichier introuvable")
	}
	return MediaSource{Kind: SourceLocalFile, Value: raw}, nil
}

// IsHTTPS is `MediaSource::is_https`.
func (s MediaSource) IsHTTPS() bool {
	return s.Kind == SourceHTTP && strings.HasPrefix(redact.ASCIILower(s.Value), "https://")
}

// MpvArg is the loadfile argument (`as_mpv_arg`).
func (s MediaSource) MpvArg() string { return s.Value }
