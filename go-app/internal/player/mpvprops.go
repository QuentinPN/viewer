package player

// Pure parts of player/mpv.rs: option values, command arguments and the parsing of
// mpv's JSON properties. Platform-neutral, so they are tested everywhere; the
// engine itself is mpv_engine_windows.go.

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"math"
	"strconv"
	"strings"
)

// streamLavfOptions are FFmpeg's HTTP options: after a network error or an early
// end of stream, it waits 0, 1, 3 then 7 s before each new try; the next delay
// (15 s) is past `reconnect_delay_max`, so it gives up there. HTTP errors such as
// 401 are not retried. No redirects: they would carry the auth header
// to another host. streamLavfFor relaxes that for one load.
const streamLavfOptions = "reconnect=1,reconnect_streamed=1,reconnect_on_network_error=1,reconnect_delay_max=7,max_redirects=0"

// streamLavfRedirects is streamLavfOptions with up to 5 redirects followed.
var streamLavfRedirects = strings.TrimSuffix(streamLavfOptions, "max_redirects=0") + "max_redirects=5"

// streamLavfFor is the `stream-lavf-o` of one load: redirects only when it asks
// for them (PlaybackRequest.FollowRedirects).
func streamLavfFor(followRedirects bool) string {
	if followRedirects {
		return streamLavfRedirects
	}
	return streamLavfOptions
}

// demuxerLavfOptions is what FFmpeg may open by itself, such as HLS segments: a
// playlist from the server must not reach local files.
const demuxerLavfOptions = "protocol_whitelist=[http,https,tcp,tls]"

// networkTimeout is the seconds a connection or a read may hang before FFmpeg
// counts it as failed. mpv reads again after a failure, so a stream that stays dead
// is ended by StallWatch, not by these options.
const networkTimeout = "15"

// nightFilterChain is night mode, as on the TV: FFmpeg's `acompressor`
// through mpv's `lavfi`, labelled `@night`. Peaks above about -24 dBFS are
// compressed 4:1, then a makeup gain of about +6 dB lifts speech; no look-ahead,
// so no latency.
const nightFilterChain = "@night:lavfi=[acompressor=threshold=0.063:ratio=4:attack=20:release=250:makeup=2]"

// nightFilter is the `af` value for night mode on or off: the chain's only filter.
func nightFilter(on bool) string {
	if on {
		return nightFilterChain
	}
	return ""
}

// delayMaxSeconds: past ten seconds either way, the file itself is out of sync.
const delayMaxSeconds = 10.0

// formatFloat is Rust's `f64::to_string`: shortest digits, never an exponent.
func formatFloat(v float64) string { return strconv.FormatFloat(v, 'f', -1, 64) }

// clamp is Rust's `f64::clamp`: NaN stays NaN.
func clamp(v, lo, hi float64) float64 {
	if math.IsNaN(v) {
		return v
	}
	return math.Min(math.Max(v, lo), hi)
}

func finite(v float64) (float64, error) {
	if math.IsNaN(v) || math.IsInf(v, 0) {
		return 0, invalidSource("valeur non finie")
	}
	return v, nil
}

func delayValue(seconds float64) (string, error) {
	v, err := finite(seconds)
	if err != nil {
		return "", err
	}
	return formatFloat(clamp(v, -delayMaxSeconds, delayMaxSeconds)), nil
}

func trackValue(id *int64) string {
	if id == nil {
		return "no"
	}
	return strconv.FormatInt(*id, 10)
}

// jsonValue decodes like serde_json::from_str::<Value>: numbers keep their text
// (for as_i64), and nothing may follow the value.
func jsonValue(s string) (any, error) {
	dec := json.NewDecoder(bytes.NewReader([]byte(s)))
	dec.UseNumber()
	var v any
	if err := dec.Decode(&v); err != nil {
		return nil, err
	}
	if _, err := dec.Token(); !errors.Is(err, io.EOF) {
		return nil, errors.New("trailing characters")
	}
	return v, nil
}

// asI64 is serde_json's `Value::as_i64`: an integer within i64, not `1.0`.
func asI64(v any) (int64, bool) {
	n, ok := v.(json.Number)
	if !ok {
		return 0, false
	}
	i, err := strconv.ParseInt(string(n), 10, 64)
	return i, err == nil
}

func asStr(v any) (string, bool) {
	s, ok := v.(string)
	return s, ok
}

func asBool(v any) (bool, bool) {
	b, ok := v.(bool)
	return b, ok
}

func asArray(v any) []any {
	a, _ := v.([]any)
	return a
}

func field(t any, key string) any {
	m, _ := t.(map[string]any)
	return m[key]
}

func parseTracks(s string) (List[Track], error) {
	value, err := jsonValue(s)
	if err != nil {
		return nil, mpvError("track-list", err.Error())
	}
	out := List[Track]{}
	for _, t := range asArray(value) {
		typ, ok := asStr(field(t, "type"))
		if !ok {
			continue
		}
		var kind TrackKind
		switch typ {
		case "video":
			kind = KindVideo
		case "audio":
			kind = KindAudio
		case "sub":
			kind = KindSub
		default:
			continue
		}
		id, ok := asI64(field(t, "id"))
		if !ok {
			continue
		}
		text := func(k string) *string {
			if s, ok := asStr(field(t, k)); ok {
				return &s
			}
			return nil
		}
		flag := func(k string) bool {
			b, _ := asBool(field(t, k))
			return b
		}
		out = append(out, Track{
			ID:       id,
			Kind:     kind,
			Title:    text("title"),
			Lang:     text("lang"),
			Codec:    text("codec"),
			Selected: flag("selected"),
			External: flag("external"),
			Default:  flag("default"),
		})
	}
	return out, nil
}

// subtitleOptionNames are the mpv options set for each file from the subtitle
// style.
var subtitleOptionNames = [7]string{
	"sub-scale",
	"sub-color",
	"sub-border-style",
	"sub-back-color",
	"sub-pos",
	"sub-align-y",
	"sub-ass-override",
}

// subtitleOptions gives the values of subtitleOptionNames, in order. They apply to
// text subtitles; ASS and SSA keep their own styles (`sub-ass-override=no`, so not
// even `sub-scale`) unless the user overrides them.
func subtitleOptions(style SubtitleStyle) [7][2]string {
	scale := "1"
	switch style.Size {
	case SizeSmall:
		scale = "0.8"
	case SizeLarge:
		scale = "1.25"
	case SizeLarger:
		scale = "1.5"
	}
	color := "#FFFFFF"
	if style.Color == ColorYellow {
		color = "#FFE14D"
	}
	border := "outline-and-shadow"
	if style.Background == BackgroundBox {
		border = "background-box"
	}
	// `sub-pos` is the bottom edge in percent of the height; 100 keeps mpv's margin.
	pos, align := "100", "bottom"
	switch style.Position {
	case PositionRaised:
		pos = "85"
	case PositionTop:
		align = "top"
	}
	ass := "no"
	if style.OverrideASS {
		ass = "force"
	}
	return [7][2]string{
		{"sub-scale", scale},
		{"sub-color", color},
		{"sub-border-style", border},
		// The box colour, 70 % opaque black (#AARRGGBB). The outline style draws no
		// shadow (`sub-shadow-offset` stays 0), so it ignores it.
		{"sub-back-color", "#B3000000"},
		{"sub-pos", pos},
		{"sub-align-y", align},
		{"sub-ass-override", ass},
	}
}

// trackWithFFIndex gives the mpv track id of the stream with FFmpeg index `index`
// in a `track-list`, among the file's own tracks of that kind ("audio" or "sub").
func trackWithFFIndex(list, kind string, index int64) (int64, bool) {
	value, err := jsonValue(list)
	if err != nil {
		return 0, false
	}
	tracks, ok := value.([]any)
	if !ok {
		return 0, false
	}
	for _, t := range tracks {
		external, isBool := asBool(field(t, "external"))
		own := !(isBool && external)
		typ, ok1 := asStr(field(t, "type"))
		ff, ok2 := asI64(field(t, "ff-index"))
		if !ok1 || !ok2 || !own || typ != kind || ff != index {
			continue
		}
		// Rust's find_map goes on to the next track when this one has no id.
		if id, ok := asI64(field(t, "id")); ok {
			return id, true
		}
	}
	return 0, false
}

// selectedStreams gives the FFmpeg indexes of the selected file tracks in a
// `track-list`: audio, then subtitle (-1 when none is shown). An added external
// track has no index. nil is Rust's None.
func selectedStreams(list string) (audio, subtitle *int32) {
	value, err := jsonValue(list)
	if err != nil {
		return nil, nil
	}
	tracks, ok := value.([]any)
	if !ok {
		return nil, nil
	}
	selected := func(kind string) any {
		for _, t := range tracks {
			typ, _ := asStr(field(t, "type"))
			sel, _ := asBool(field(t, "selected"))
			if typ == kind && sel {
				return t
			}
		}
		return nil
	}
	ffIndex := func(t any) *int32 {
		if ext, _ := asBool(field(t, "external")); ext {
			return nil
		}
		i, ok := asI64(field(t, "ff-index"))
		if !ok || i < math.MinInt32 || i > math.MaxInt32 {
			return nil
		}
		v := int32(i)
		return &v
	}
	if t := selected("audio"); t != nil {
		audio = ffIndex(t)
	}
	if t := selected("sub"); t != nil {
		subtitle = ffIndex(t)
	} else {
		none := int32(-1)
		subtitle = &none
	}
	return audio, subtitle
}
