// Package tlsroots writes the Windows root certificates as a PEM file for mpv's
// `tls-ca-file`: FFmpeg's OpenSSL has no certificate store of its own (ADR 0026).
// Port of src-tauri/src/player/tls_roots.rs; see research/tls-roots.md.
package tlsroots

import (
	"bytes"
	"encoding/base64"
	"errors"
	"os"
	"path/filepath"
	"strings"
)

// Refresh writes the roots Windows trusts for server authentication to path. It
// fails rather than leave the player with no or stale roots to validate against.
func Refresh(path string) error {
	roots, err := windowsRoots()
	if err != nil {
		return err
	}
	if len(roots) == 0 {
		return errors.New("aucun certificat racine")
	}
	return writeIfChanged(path, toPEM(roots))
}

// toPEM encodes DER certificates as PEM blocks, base64 wrapped at 64 characters.
func toPEM(certs [][]byte) string {
	var pem strings.Builder
	for _, der := range certs {
		pem.WriteString("-----BEGIN CERTIFICATE-----\n")
		encoded := base64.StdEncoding.EncodeToString(der)
		for len(encoded) > 0 {
			n := min(64, len(encoded))
			pem.WriteString(encoded[:n])
			pem.WriteByte('\n')
			encoded = encoded[n:]
		}
		pem.WriteString("-----END CERTIFICATE-----\n")
	}
	return pem.String()
}

// writeIfChanged replaces the file by renaming, so a reader never sees half of it,
// and leaves an identical file alone: the player may be reading it.
func writeIfChanged(path, content string) error {
	if current, err := os.ReadFile(path); err == nil && bytes.Equal(current, []byte(content)) {
		return nil
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	// Rust `with_extension("tmp")`: the extension is replaced.
	temporary := strings.TrimSuffix(path, filepath.Ext(path)) + ".tmp"
	if err := os.WriteFile(temporary, []byte(content), 0o644); err != nil {
		return err
	}
	return os.Rename(temporary, path)
}
