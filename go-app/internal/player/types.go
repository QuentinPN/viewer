// Package player is the transport-neutral playback abstraction, port of
// src-tauri/src/player/mod.rs (ADR 0128).
//
// Wave 2 ports only the types the Emby playback negotiation and the goldens need
// (PlaybackRequest, TrackSelection, TrackPick, ExternalSubtitle, SubtitleStyle, Track,
// PlayerDiagnostics). Wave 3 adds the engine in the other files: StallWatch, MediaSource,
// PlayerCommand, PlayerEvent, PlayerError, PlayerEngine and MpvEngine (libmpv).
//
// Serde mapping, as in internal/emby: struct field order is the Rust order; an
// `Option` is a pointer written null; a `Vec` is a List, written [] when nil; a type
// the UI or the engine contract decodes has a strict UnmarshalJSON (exact keys,
// missing keys take the serde default, unknown keys ignored, null refused where serde
// refuses it).
package player

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
)

// List is a Vec: nil is written [], never null. Same as emby.List, which cannot be
// used here (emby imports player).
type List[T any] []T

// MarshalJSON writes [] for a nil list.
func (l List[T]) MarshalJSON() ([]byte, error) {
	if l == nil {
		return []byte("[]"), nil
	}
	return json.Marshal([]T(l))
}

// Header is one HTTP header, a Rust `(String, String)`: written ["name", "value"].
type Header = [2]string

// PlaybackRequest is what to play. Built by the coordinator, never by the Svelte UI directly.
type PlaybackRequest struct {
	// Source is a local file path or http(s) URL (validated by MediaSource, wave 3).
	Source string `json:"source"`
	// HTTPHeaders are sent by the player (e.g. auth), so tokens never go in the URL.
	HTTPHeaders List[Header] `json:"httpHeaders"`
	// StartSeconds is the resume position in seconds.
	StartSeconds *float64 `json:"startSeconds"`
	// Subtitles are files added once the media is loaded (e.g. text tracks a
	// transcode delivers separately). Validated like Source.
	Subtitles List[ExternalSubtitle] `json:"subtitles"`
	// Tracks: audio and subtitle tracks to select once the file is loaded (ADR 0035).
	Tracks TrackSelection `json:"tracks"`
	// SubtitleStyle: how text subtitles look (ADR 0035).
	SubtitleStyle SubtitleStyle `json:"subtitleStyle"`
}

// UnmarshalJSON follows serde: `source` required, the other keys default.
func (r *PlaybackRequest) UnmarshalJSON(b []byte) error {
	out := PlaybackRequest{}
	hasSource := false
	err := decodeObject(b, "PlaybackRequest", func(key string, v json.RawMessage) error {
		switch key {
		case "source":
			hasSource = true
			return strict(v, &out.Source)
		case "httpHeaders":
			return decodeHeaders(v, &out.HTTPHeaders)
		case "startSeconds":
			return optional(v, &out.StartSeconds)
		case "subtitles":
			return strict(v, &out.Subtitles)
		case "tracks":
			return strict(v, &out.Tracks)
		case "subtitleStyle":
			return strict(v, &out.SubtitleStyle)
		}
		return nil
	})
	if err != nil {
		return err
	}
	if !hasSource {
		return errors.New("PlaybackRequest : champ source manquant")
	}
	*r = out
	return nil
}

// TrackPickKind is the variant of a TrackPick. The zero value is TrackAuto, the serde default.
type TrackPickKind int

const (
	// TrackAuto: the player's own choice, from the file's flags.
	TrackAuto TrackPickKind = iota
	// TrackOff: no track; only meaningful for subtitles.
	TrackOff
	// TrackStream: the stream with Index in the container, as FFmpeg numbers it
	// (Emby's `Index`). Left to the player when the file has no such stream.
	TrackStream
)

var trackPickTags = [...]string{"auto", "off", "stream"}

// TrackPick is which track of a kind to select once the file is loaded (ADR 0035).
// Index is meaningful for TrackStream only. Serde: internally tagged by `type`.
type TrackPick struct {
	Kind  TrackPickKind
	Index int64
}

// PickStream is `TrackPick::Stream { index }`.
func PickStream(index int64) TrackPick { return TrackPick{Kind: TrackStream, Index: index} }

// MarshalJSON writes {"type":"auto"}, {"type":"off"} or {"type":"stream","index":N}.
func (p TrackPick) MarshalJSON() ([]byte, error) {
	if p.Kind == TrackStream {
		return json.Marshal(struct {
			Type  string `json:"type"`
			Index int64  `json:"index"`
		}{"stream", p.Index})
	}
	if p.Kind < 0 || int(p.Kind) >= len(trackPickTags) {
		return nil, fmt.Errorf("TrackPick : variante %d inconnue", p.Kind)
	}
	return json.Marshal(struct {
		Type string `json:"type"`
	}{trackPickTags[p.Kind]})
}

// UnmarshalJSON reads the `type` tag among the camelCase names; `stream` needs an
// integer `index`. Other keys are ignored, as serde does. Serde would also accept
// the sequence form `["stream", 2]`; it is refused here (nothing sends it).
func (p *TrackPick) UnmarshalJSON(b []byte) error {
	var tag *string
	var index *int64
	err := decodeObject(b, "TrackPick", func(key string, v json.RawMessage) error {
		switch key {
		case "type":
			var s string
			if err := strict(v, &s); err != nil {
				return err
			}
			tag = &s
		case "index":
			var i int64
			if err := strict(v, &i); err != nil {
				return err
			}
			index = &i
		}
		return nil
	})
	if err != nil {
		return err
	}
	if tag == nil {
		return errors.New("TrackPick : champ type manquant")
	}
	for i, name := range trackPickTags {
		if name != *tag {
			continue
		}
		out := TrackPick{Kind: TrackPickKind(i)}
		if out.Kind == TrackStream {
			if index == nil {
				return errors.New("TrackPick : champ index manquant")
			}
			out.Index = *index
		}
		*p = out
		return nil
	}
	return fmt.Errorf("TrackPick : variante inconnue %q", *tag)
}

// TrackSelection is the audio and subtitle pick of a file. The zero value is the
// serde default (both Auto).
type TrackSelection struct {
	Audio    TrackPick `json:"audio"`
	Subtitle TrackPick `json:"subtitle"`
}

// UnmarshalJSON follows serde: both keys default to Auto, null is refused.
func (s *TrackSelection) UnmarshalJSON(b []byte) error {
	out := TrackSelection{}
	err := decodeObject(b, "TrackSelection", func(key string, v json.RawMessage) error {
		switch key {
		case "audio":
			return strict(v, &out.Audio)
		case "subtitle":
			return strict(v, &out.Subtitle)
		}
		return nil
	})
	if err == nil {
		*s = out
	}
	return err
}

// SubtitleStyle is the look of text subtitles (SRT, WebVTT…). ASS/SSA keep their
// own styling unless OverrideASS is set (ADR 0035). The zero value is the default.
type SubtitleStyle struct {
	Size        SubtitleSize       `json:"size"`
	Color       SubtitleColor      `json:"color"`
	Background  SubtitleBackground `json:"background"`
	Position    SubtitlePosition   `json:"position"`
	OverrideASS bool               `json:"overrideAss"`
}

// UnmarshalJSON follows `#[serde(rename_all = "camelCase", default)]`.
func (s *SubtitleStyle) UnmarshalJSON(b []byte) error {
	out := SubtitleStyle{}
	err := decodeObject(b, "SubtitleStyle", func(key string, v json.RawMessage) error {
		switch key {
		case "size":
			return strict(v, &out.Size)
		case "color":
			return strict(v, &out.Color)
		case "background":
			return strict(v, &out.Background)
		case "position":
			return strict(v, &out.Position)
		case "overrideAss":
			return strict(v, &out.OverrideASS)
		}
		return nil
	})
	if err == nil {
		*s = out
	}
	return err
}

// SubtitleSize: the zero value is SizeNormal, the serde default.
type SubtitleSize int

const (
	SizeNormal SubtitleSize = iota
	SizeSmall
	SizeLarge
	SizeLarger
)

var subtitleSizeNames = [...]string{"normal", "small", "large", "larger"}

func (v SubtitleSize) MarshalJSON() ([]byte, error) {
	return marshalVariant(int(v), subtitleSizeNames[:], "SubtitleSize")
}

func (v *SubtitleSize) UnmarshalJSON(b []byte) error {
	i, err := unitVariant(b, subtitleSizeNames[:], "SubtitleSize")
	if err == nil {
		*v = SubtitleSize(i)
	}
	return err
}

// SubtitleColor: the zero value is ColorWhite, the serde default.
type SubtitleColor int

const (
	ColorWhite SubtitleColor = iota
	ColorYellow
)

var subtitleColorNames = [...]string{"white", "yellow"}

func (v SubtitleColor) MarshalJSON() ([]byte, error) {
	return marshalVariant(int(v), subtitleColorNames[:], "SubtitleColor")
}

func (v *SubtitleColor) UnmarshalJSON(b []byte) error {
	i, err := unitVariant(b, subtitleColorNames[:], "SubtitleColor")
	if err == nil {
		*v = SubtitleColor(i)
	}
	return err
}

// SubtitleBackground: the zero value is BackgroundOutline, the serde default.
type SubtitleBackground int

const (
	// BackgroundOutline is mpv's default: a dark outline around the letters.
	BackgroundOutline SubtitleBackground = iota
	// BackgroundBox is a translucent dark box behind the text.
	BackgroundBox
)

var subtitleBackgroundNames = [...]string{"outline", "box"}

func (v SubtitleBackground) MarshalJSON() ([]byte, error) {
	return marshalVariant(int(v), subtitleBackgroundNames[:], "SubtitleBackground")
}

func (v *SubtitleBackground) UnmarshalJSON(b []byte) error {
	i, err := unitVariant(b, subtitleBackgroundNames[:], "SubtitleBackground")
	if err == nil {
		*v = SubtitleBackground(i)
	}
	return err
}

// SubtitlePosition: the zero value is PositionBottom, the serde default.
type SubtitlePosition int

const (
	PositionBottom SubtitlePosition = iota
	// PositionRaised: higher up, clear of the player's controls and of text burnt
	// into the picture.
	PositionRaised
	PositionTop
)

var subtitlePositionNames = [...]string{"bottom", "raised", "top"}

func (v SubtitlePosition) MarshalJSON() ([]byte, error) {
	return marshalVariant(int(v), subtitlePositionNames[:], "SubtitlePosition")
}

func (v *SubtitlePosition) UnmarshalJSON(b []byte) error {
	i, err := unitVariant(b, subtitlePositionNames[:], "SubtitlePosition")
	if err == nil {
		*v = SubtitlePosition(i)
	}
	return err
}

// ExternalSubtitle is a subtitle file loaded next to the media, with the same HTTP headers.
type ExternalSubtitle struct {
	Source string  `json:"source"`
	Title  *string `json:"title"`
	Lang   *string `json:"lang"`
	// Selected when loaded; otherwise left to the player's own track choice.
	Selected bool `json:"selected"`
}

// UnmarshalJSON follows serde: `source` required, the other keys default.
func (s *ExternalSubtitle) UnmarshalJSON(b []byte) error {
	out := ExternalSubtitle{}
	hasSource := false
	err := decodeObject(b, "ExternalSubtitle", func(key string, v json.RawMessage) error {
		switch key {
		case "source":
			hasSource = true
			return strict(v, &out.Source)
		case "title":
			return optional(v, &out.Title)
		case "lang":
			return optional(v, &out.Lang)
		case "selected":
			return strict(v, &out.Selected)
		}
		return nil
	})
	if err != nil {
		return err
	}
	if !hasSource {
		return errors.New("ExternalSubtitle : champ source manquant")
	}
	*s = out
	return nil
}

// Track is one track of the loaded file, sent to the UI (`player_tracks`, the
// `tracks` event). Only serialized: the engine builds it (wave 3).
type Track struct {
	ID       int64     `json:"id"`
	Kind     TrackKind `json:"kind"`
	Title    *string   `json:"title"`
	Lang     *string   `json:"lang"`
	Codec    *string   `json:"codec"`
	Selected bool      `json:"selected"`
	External bool      `json:"external"`
	Default  bool      `json:"default"`
}

// TrackKind is the kind of a Track, written camelCase.
type TrackKind string

const (
	KindVideo TrackKind = "video"
	KindAudio TrackKind = "audio"
	KindSub   TrackKind = "sub"
)

// PlayerDiagnostics is the technical snapshot used for diagnostics (Direct Play
// badge, hwdec, logs). Only serialized: the engine fills it (wave 3). The zero
// value is PlayerDiagnostics::default().
type PlayerDiagnostics struct {
	MpvVersion    *string `json:"mpvVersion"`
	FfmpegVersion *string `json:"ffmpegVersion"`
	VideoOutput   *string `json:"videoOutput"`
	GpuAPI        *string `json:"gpuApi"`
	HwdecCurrent  *string `json:"hwdecCurrent"`
	VideoCodec    *string `json:"videoCodec"`
	VideoWidth    *int64  `json:"videoWidth"`
	VideoHeight   *int64  `json:"videoHeight"`
	AudioCodec    *string `json:"audioCodec"`
	// AudioOutput is the active audio output driver (`current-ao`); nil means no device is open.
	AudioOutput *string  `json:"audioOutput"`
	Container   *string  `json:"container"`
	OsdWidth    *int64   `json:"osdWidth"`
	OsdHeight   *int64   `json:"osdHeight"`
	TimePos     *float64 `json:"timePos"`
	Duration    *float64 `json:"duration"`
	Paused      *bool    `json:"paused"`
	// Volume is the engine volume in percent (0–130), as set by SetVolume (ADR 0017).
	Volume                *float64 `json:"volume"`
	FrameDropCount        *int64   `json:"frameDropCount"`
	DecoderFrameDropCount *int64   `json:"decoderFrameDropCount"`
	// ChapterCount: chapters mpv found in the file (`chapters`), compared with Emby's (ADR 0034).
	ChapterCount *int64 `json:"chapterCount"`
	// SubtitleOptions in effect (ADR 0035): `sub-scale`, `sub-color`,
	// `sub-border-style`, `sub-pos`, `sub-align-y`, `sub-ass-override`.
	SubtitleOptions List[[2]string] `json:"subtitleOptions"`
}

// ------------------------------------------------------------ strict decoding

func isNull(b []byte) bool { return bytes.Equal(bytes.TrimSpace(b), []byte("null")) }

// decodeObject decodes a JSON object with exact keys, calling field for each; null
// and non-objects are refused.
func decodeObject(b []byte, typ string, field func(key string, v json.RawMessage) error) error {
	if isNull(b) {
		return fmt.Errorf("%s : null refusé", typ)
	}
	var raw map[string]json.RawMessage
	if err := json.Unmarshal(b, &raw); err != nil {
		return fmt.Errorf("%s : %w", typ, err)
	}
	for key, v := range raw {
		if err := field(key, v); err != nil {
			return fmt.Errorf("%s.%s : %w", typ, key, err)
		}
	}
	return nil
}

// decodeHeaders decodes a `Vec<(String, String)>`: each entry exactly two strings.
func decodeHeaders(v json.RawMessage, dst *List[Header]) error {
	var raw []json.RawMessage
	if err := strict(v, &raw); err != nil {
		return err
	}
	out := make(List[Header], 0, len(raw))
	for _, entry := range raw {
		var pair []json.RawMessage
		if err := strict(entry, &pair); err != nil {
			return err
		}
		if len(pair) != 2 {
			return fmt.Errorf("en-tête de %d éléments au lieu de 2", len(pair))
		}
		var h Header
		for i := range pair {
			if err := strict(pair[i], &h[i]); err != nil {
				return err
			}
		}
		out = append(out, h)
	}
	*dst = out
	return nil
}

// strict decodes a non-Option field: null is refused.
func strict[T any](v json.RawMessage, dst *T) error {
	if isNull(v) {
		return errors.New("null refusé")
	}
	return json.Unmarshal(v, dst)
}

// optional decodes an `Option<T>`: null is nil.
func optional[T any](v json.RawMessage, dst **T) error {
	if isNull(v) {
		*dst = nil
		return nil
	}
	var out T
	if err := json.Unmarshal(v, &out); err != nil {
		return err
	}
	*dst = &out
	return nil
}

// unitVariant decodes a serde unit variant: a JSON string among names.
func unitVariant(b []byte, names []string, typ string) (int, error) {
	if isNull(b) {
		return 0, fmt.Errorf("%s : null refusé", typ)
	}
	var s string
	if err := json.Unmarshal(b, &s); err != nil {
		return 0, fmt.Errorf("%s : %w", typ, err)
	}
	for i, n := range names {
		if n == s {
			return i, nil
		}
	}
	return 0, fmt.Errorf("%s : variante inconnue %q", typ, s)
}

func marshalVariant(i int, names []string, typ string) ([]byte, error) {
	if i < 0 || i >= len(names) {
		return nil, fmt.Errorf("%s : variante %d inconnue", typ, i)
	}
	return json.Marshal(names[i])
}
