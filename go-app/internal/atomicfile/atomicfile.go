// Package atomicfile writes a small JSON settings file whole or not at all: a
// temporary file beside it, then a rename.
package atomicfile

import (
	"os"
	"path/filepath"
	"strings"
)

// Write creates the folder of path, writes data to `<name>.json.tmp` beside it,
// then renames that over path. The error is the os one, unwrapped by the caller.
func Write(path string, data []byte) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	// Rust `with_extension("json.tmp")`: the extension is replaced, so
	// playback-quality.json becomes playback-quality.json.tmp.
	tmp := strings.TrimSuffix(path, filepath.Ext(path)) + ".json.tmp"
	if err := os.WriteFile(tmp, data, 0o644); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}
