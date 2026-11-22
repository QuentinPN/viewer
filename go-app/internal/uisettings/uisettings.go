// Package uisettings is src-tauri/src/ui_settings.rs: the UI preferences handed
// over from the Tauri app. WebView2 keeps localStorage per origin
// (`http://tauri.localhost` there, `http://wails.localhost` here), so the Tauri
// frontend exports the app's keys with `ui_settings_export` at each launch, and
// this build reads them back with `ui_settings_import` when its own storage holds
// none (src/lib/native/uiSettings.ts). The file lives in the app config folder, so
// the debug build keeps its own.
package uisettings

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io/fs"
	"os"
	"path/filepath"
	"slices"
	"strconv"
	"strings"

	"viewer/internal/iokind"
)

// FileName is the file in the app config folder.
const FileName = "ui-settings.json"

// Keys are the app's localStorage keys with a fixed name (src/lib/native/uiSettings.ts).
var Keys = []string{
	"viewer.player.volume",
	"viewer.player.skip",
	"viewer.player.subtitles",
	"viewer.player.nightMode",
	"viewer.ui.seasonalLogo",
}

// Prefixes are the prefixes of the app's per server / user keys.
var Prefixes = []string{
	"viewer.home-rows.v1:",
	"viewer.library-options.v1:",
	"viewer.search-history.v1:",
	"viewer.version.v1:",
}

const (
	// MaxEntries is the most entries of an export.
	MaxEntries = 200
	// MaxValueBytes is the UTF-8 size limit of one value.
	MaxValueBytes = 64 * 1024
	// MaxTotalBytes is the UTF-8 size limit of every key and value together.
	MaxTotalBytes = 512 * 1024
)

// Error is `UiSettingsError`: Rust serializes it as its `#[error]` text, a bare
// string, which is what a Go error without a kind becomes in the bridge.
type Error struct{ text string }

func (e *Error) Error() string { return e.text }

// MarshalJSON makes the error serialize as a bare string, like Rust.
func (e *Error) MarshalJSON() ([]byte, error) { return json.Marshal(e.text) }

// Is compares the texts, so the sentinels below match.
func (e *Error) Is(target error) bool {
	t, ok := target.(*Error)
	return ok && t.text == e.text
}

// The variants of `UiSettingsError`.
var (
	ErrUnavailable    = &Error{"dossier des réglages indisponible sur ce poste"}
	ErrTooManyEntries = &Error{"réglages refusés : plus de " + strconv.Itoa(MaxEntries) + " entrées"}
	ErrKeyNotAllowed  = &Error{"réglages refusés : clé non autorisée"}
	ErrValueTooLarge  = &Error{"réglages refusés : valeur de plus de 64 Kio"}
	ErrTooLarge       = &Error{"réglages refusés : plus de 512 Kio au total"}
)

// WriteError is `UiSettingsError::Write(detail)`.
func WriteError(detail string) *Error { return &Error{"réglages non enregistrés : " + detail} }

// ReadError is `UiSettingsError::Read(detail)`.
func ReadError(detail string) *Error { return &Error{"réglages illisibles : " + detail} }

// IsAllowedKey is `is_allowed_key`: whether key is one of the app's UI preferences.
func IsAllowedKey(key string) bool {
	if slices.Contains(Keys, key) {
		return true
	}
	for _, p := range Prefixes {
		if len(key) > len(p) && strings.HasPrefix(key, p) {
			return true
		}
	}
	return false
}

// Validate is `validate`: the limits, checked in key order so Go and Rust refuse
// the same way.
func Validate(entries map[string]string) error {
	if len(entries) > MaxEntries {
		return ErrTooManyEntries
	}
	keys := make([]string, 0, len(entries))
	for k := range entries {
		keys = append(keys, k)
	}
	slices.Sort(keys)
	total := 0
	for _, k := range keys {
		if !IsAllowedKey(k) {
			return ErrKeyNotAllowed
		}
		v := entries[k]
		if len(v) > MaxValueBytes {
			return ErrValueTooLarge
		}
		total += len(k) + len(v)
	}
	if total > MaxTotalBytes {
		return ErrTooLarge
	}
	return nil
}

// Export is `export`: validates then writes dir/ui-settings.json atomically (temp
// file + rename), replacing the previous export. Compact JSON, keys sorted, `<>&`
// not escaped, as serde_json writes it.
func Export(dir string, entries map[string]string) error {
	if err := Validate(entries); err != nil {
		return err
	}
	ioErr := func(err error) error { return WriteError(iokind.Text(err)) }
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return ioErr(err)
	}
	if entries == nil {
		entries = map[string]string{}
	}
	var buf bytes.Buffer
	enc := json.NewEncoder(&buf)
	enc.SetEscapeHTML(false)
	if err := enc.Encode(entries); err != nil {
		return WriteError("JSON")
	}
	tmp := filepath.Join(dir, FileName+".tmp")
	if err := os.WriteFile(tmp, bytes.TrimSuffix(buf.Bytes(), []byte("\n")), 0o644); err != nil {
		return ioErr(err)
	}
	if err := os.Rename(tmp, filepath.Join(dir, FileName)); err != nil {
		return ioErr(err)
	}
	return nil
}

// Import is `import`: reads dir/ui-settings.json, nil when there is no export, the
// same limits as Export otherwise. The file is left in place.
func Import(dir string) (map[string]string, error) {
	data, err := os.ReadFile(filepath.Join(dir, FileName))
	if errors.Is(err, fs.ErrNotExist) {
		return nil, nil
	}
	if err != nil {
		return nil, ReadError(iokind.Text(err))
	}
	var entries map[string]string
	if json.Unmarshal(data, &entries) != nil || entries == nil {
		return nil, ReadError("JSON invalide")
	}
	if err := Validate(entries); err != nil {
		return nil, err
	}
	return entries, nil
}

// ExportHandler is the `ui_settings_export` command, with the signature of
// bridge.Handler once its argument is read. dir is the app config folder ("" when
// none).
func ExportHandler(dir string) func(ctx context.Context, entries map[string]string) (any, error) {
	return func(_ context.Context, entries map[string]string) (any, error) {
		if dir == "" {
			return nil, ErrUnavailable
		}
		if err := Export(dir, entries); err != nil {
			return nil, err
		}
		return nil, nil
	}
}

// ImportHandler is the `ui_settings_import` command: the entries, or JSON null
// when there is no export.
func ImportHandler(dir string) func(ctx context.Context, args json.RawMessage) (any, error) {
	return func(context.Context, json.RawMessage) (any, error) {
		if dir == "" {
			return nil, ErrUnavailable
		}
		entries, err := Import(dir)
		if err != nil {
			return nil, err
		}
		if entries == nil {
			return nil, nil
		}
		return entries, nil
	}
}
