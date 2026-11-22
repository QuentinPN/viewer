package emby

// Streaming quality setting, port of src-tauri/src/emby/quality.rs: a
// bitrate cap per Emby server, one for a local-network address and one for a remote
// one, kept in `playback-quality.json`. Contains no secret.

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"sync"
	"viewer/internal/iokind"
)

// BitrateChoices are the caps offered in the settings, in bits per second; nil is "Maximale".
var BitrateChoices = List[uint64]{40_000_000, 20_000_000, 10_000_000, 8_000_000, 4_000_000, 3_000_000, 1_500_000}

// QualityCaps are the caps of one server; nil sends the default limit.
type QualityCaps struct {
	Local  *uint64 `json:"local"`
	Remote *uint64 `json:"remote"`
}

// UnmarshalJSON follows serde: exact key names, missing or null is nil, unknown keys
// ignored, a negative, fractional or non-number cap refused.
func (c *QualityCaps) UnmarshalJSON(b []byte) error {
	var raw map[string]json.RawMessage
	if bytes.Equal(bytes.TrimSpace(b), []byte("null")) {
		return errors.New("QualityCaps : null refusé")
	}
	if err := json.Unmarshal(b, &raw); err != nil {
		return fmt.Errorf("QualityCaps : %w", err)
	}
	var out QualityCaps
	var err error
	if v, ok := raw["local"]; ok {
		if out.Local, err = optional[uint64](v); err != nil {
			return fmt.Errorf("QualityCaps.local : %w", err)
		}
	}
	if v, ok := raw["remote"]; ok {
		if out.Remote, err = optional[uint64](v); err != nil {
			return fmt.Errorf("QualityCaps.remote : %w", err)
		}
	}
	*c = out
	return nil
}

// Equal compares the caps by value.
func (c QualityCaps) Equal(o QualityCaps) bool {
	return eqOptU64(c.Local, o.Local) && eqOptU64(c.Remote, o.Remote)
}

func eqOptU64(a, b *uint64) bool {
	return (a == nil && b == nil) || (a != nil && b != nil && *a == *b)
}

func (c QualityCaps) String() string {
	show := func(v *uint64) string {
		if v == nil {
			return "None"
		}
		return fmt.Sprint(*v)
	}
	return "QualityCaps{local: " + show(c.Local) + ", remote: " + show(c.Remote) + "}"
}

// Validated refuses any cap outside BitrateChoices with a Settings EmbyError.
func (c QualityCaps) Validated() (QualityCaps, error) {
	for _, b := range []*uint64{c.Local, c.Remote} {
		if b != nil && !slices.Contains(BitrateChoices, *b) {
			return QualityCaps{}, NewSettings("débit de lecture non proposé")
		}
	}
	return c, nil
}

// sanitized drops caps outside the list, e.g. from a hand-edited or older file.
func (c QualityCaps) sanitized() QualityCaps {
	keep := func(b *uint64) *uint64 {
		if b != nil && slices.Contains(BitrateChoices, *b) {
			v := *b
			return &v
		}
		return nil
	}
	return QualityCaps{Local: keep(c.Local), Remote: keep(c.Remote)}
}

// ForAddress is the cap for a server reached through a local (or remote) address.
func (c QualityCaps) ForAddress(local bool) *uint64 {
	if local {
		return c.Local
	}
	return c.Remote
}

// StreamingLimit is the limit of one negotiation, sent to PlaybackInfo and shown by the player.
type StreamingLimit struct {
	Bitrate uint64
	// Cap chosen (or the debug override); nil sends the default limit.
	Cap          *uint64
	LocalAddress bool
}

// PlaybackQuality is what the settings screen shows for the current server.
type PlaybackQuality struct {
	Local  *uint64 `json:"local"`
	Remote *uint64 `json:"remote"`
	// LocalAddress: whether the session's address counts as local, so which cap applies.
	LocalAddress bool         `json:"localAddress"`
	Choices      List[uint64] `json:"choices"`
}

// NewPlaybackQuality is the value session.rs `playback_quality` builds.
func NewPlaybackQuality(caps QualityCaps, localAddress bool) PlaybackQuality {
	return PlaybackQuality{Local: caps.Local, Remote: caps.Remote, LocalAddress: localAddress, Choices: slices.Clone(BitrateChoices)}
}

// qualityFile is `playback-quality.json`, keyed by Emby server id (sorted on write,
// as Rust's BTreeMap).
type qualityFile struct {
	Servers map[string]QualityCaps `json:"servers"`
}

func (f *qualityFile) UnmarshalJSON(b []byte) error {
	var raw map[string]json.RawMessage
	if err := json.Unmarshal(b, &raw); err != nil {
		return err
	}
	if raw == nil {
		return errors.New("qualityFile : null refusé")
	}
	out := qualityFile{Servers: map[string]QualityCaps{}}
	if v, ok := raw["servers"]; ok {
		if bytes.Equal(bytes.TrimSpace(v), []byte("null")) {
			return errors.New("qualityFile.servers : null refusé")
		}
		if err := json.Unmarshal(v, &out.Servers); err != nil {
			return err
		}
		if out.Servers == nil {
			out.Servers = map[string]QualityCaps{}
		}
	}
	*f = out
	return nil
}

// QualityStore keeps the caps of every server in one JSON file.
type QualityStore struct {
	path string
	mu   sync.Mutex
	file qualityFile
}

// LoadQualityStore reads path; an unreadable file counts as empty: every cap is "Maximale".
func LoadQualityStore(path string) *QualityStore {
	file := qualityFile{Servers: map[string]QualityCaps{}}
	if b, err := os.ReadFile(path); err == nil {
		var read qualityFile
		if json.Unmarshal(b, &read) == nil {
			file = read
		}
	}
	for id, caps := range file.Servers {
		file.Servers[id] = caps.sanitized()
	}
	return &QualityStore{path: path, file: file}
}

// Get returns the caps of a server, the default when none are saved.
func (s *QualityStore) Get(serverID string) QualityCaps {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.file.Servers[serverID]
}

// Set validates and saves the caps of a server; back to the default, the server
// leaves the file. On a refused value the old caps stay.
func (s *QualityStore) Set(serverID string, caps QualityCaps) error {
	caps, err := caps.Validated()
	if err != nil {
		return err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if caps.Equal(QualityCaps{}) {
		delete(s.file.Servers, serverID)
	} else {
		s.file.Servers[serverID] = caps
	}
	return saveQualityFile(s.file, s.path)
}

// saveQualityFile writes atomically (temp file + rename), like `session.json`.
func saveQualityFile(file qualityFile, path string) error {
	ioErr := func(err error) error {
		return NewSettings("playback-quality.json : " + osErrorText(err))
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return ioErr(err)
	}
	// Rust `with_extension("json.tmp")`: the extension is replaced, so
	// playback-quality.json becomes playback-quality.json.tmp.
	tmp := strings.TrimSuffix(path, filepath.Ext(path)) + ".json.tmp"
	text, err := json.MarshalIndent(file, "", "  ")
	if err != nil {
		return NewSettings(err.Error())
	}
	if err := os.WriteFile(tmp, text, 0o644); err != nil {
		return ioErr(err)
	}
	if err := os.Rename(tmp, path); err != nil {
		return ioErr(err)
	}
	return nil
}

// ioKindText is the Display of the io::ErrorKind Rust logs for err: never the OS text.
func ioKindText(err error) string { return iokind.Text(err) }

// osErrorText is the OS text of an I/O error, without Go's "op path:" prefix (Rust
// prints the io::Error alone).
func osErrorText(err error) string {
	var pe *os.PathError
	if errors.As(err, &pe) {
		return pe.Err.Error()
	}
	var le *os.LinkError
	if errors.As(err, &le) {
		return le.Err.Error()
	}
	return err.Error()
}
