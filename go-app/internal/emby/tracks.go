package emby

// Audio and subtitle preferences kept on this PC (ADR 0035), port of
// src-tauri/src/emby/tracks.rs: which streams of the source to play, from the
// server's own picks and the user's languages and mode.

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"slices"
	"strconv"
	"strings"

	"viewer/internal/player"
)

// TrackPreferences is what the user chose in Réglages › Sous-titres. Languages are
// ISO 639-2/B codes of Languages; nil keeps the server's pick. The zero value is the
// serde default.
type TrackPreferences struct {
	AudioLanguage    *string
	SubtitleLanguage *string
	SubtitleMode     SubtitleMode
}

// UnmarshalJSON follows serde's `#[serde(rename_all = "camelCase", default)]`: exact
// key names, missing keys keep the default, unknown keys are ignored, null means
// "none" for the languages and is refused for the mode.
func (p *TrackPreferences) UnmarshalJSON(b []byte) error {
	var raw map[string]json.RawMessage
	if bytes.Equal(bytes.TrimSpace(b), []byte("null")) {
		return errors.New("TrackPreferences : null refusé")
	}
	if err := json.Unmarshal(b, &raw); err != nil {
		return fmt.Errorf("TrackPreferences : %w", err)
	}
	out := TrackPreferences{}
	for key, v := range raw {
		var err error
		switch key {
		case "audioLanguage":
			out.AudioLanguage, err = optional[string](v)
		case "subtitleLanguage":
			out.SubtitleLanguage, err = optional[string](v)
		case "subtitleMode":
			err = json.Unmarshal(v, &out.SubtitleMode)
		}
		if err != nil {
			return fmt.Errorf("TrackPreferences.%s : %w", key, err)
		}
	}
	*p = out
	return nil
}

// SubtitleMode is the subtitle choice of TrackPreferences. The zero value is
// SubtitleServer, the serde default.
type SubtitleMode int

const (
	// SubtitleServer: the subtitle the server picks from the user's Emby settings.
	SubtitleServer SubtitleMode = iota
	SubtitleOff
	// SubtitleForcedOnly: forced subtitles only (signs, foreign dialogue), in the
	// subtitle language or else the audio's.
	SubtitleForcedOnly
	// SubtitleAlways: full subtitles, forced ones when there are no others.
	SubtitleAlways
)

var subtitleModeNames = [...]string{"server", "off", "forcedOnly", "always"}

func (m SubtitleMode) String() string {
	if int(m) >= 0 && int(m) < len(subtitleModeNames) {
		return subtitleModeNames[m]
	}
	return "SubtitleMode(" + strconv.Itoa(int(m)) + ")"
}

// UnmarshalJSON accepts the camelCase names only, as serde.
func (m *SubtitleMode) UnmarshalJSON(b []byte) error {
	i, err := unitVariant(b, subtitleModeNames[:], "SubtitleMode")
	if err == nil {
		*m = SubtitleMode(i)
	}
	return err
}

// Languages offered, each with the spellings found in files: `fre`, `fra` and `fr`
// were all seen on the test server.
var Languages = [...][]string{
	{"fre", "fra", "fr"},
	{"eng", "en"},
	{"spa", "es"},
	{"ger", "deu", "de"},
	{"ita", "it"},
	{"por", "pt"},
	{"dut", "nld", "nl"},
	{"jpn", "ja"},
	{"kor", "ko"},
	{"chi", "zho", "zh"},
	{"rus", "ru"},
	{"ara", "ar"},
}

// textSubtitles are the formats the subtitle style applies to (ADR 0035); image
// formats (PGS, DVD) cannot be restyled, so text wins when both match.
var textSubtitles = [...]string{"subrip", "srt", "ass", "ssa", "vtt", "webvtt", "mov_text"}

// Validate refuses a language the UI does not offer, before any request.
func (p TrackPreferences) Validate() error {
	for _, code := range []*string{p.AudioLanguage, p.SubtitleLanguage} {
		if code != nil && spellings(*code) == nil {
			return NewInvalidResponse("langue inconnue : " + *code)
		}
	}
	return nil
}

// spellings of a code of Languages (its first spelling), nil when not offered.
func spellings(code string) []string {
	for _, l := range Languages {
		if l[0] == code {
			return l
		}
	}
	return nil
}

// streamLanguage is the spellings of a stream's language, when it is one of Languages.
func streamLanguage(stream *MediaStreamDto) []string {
	if stream.Language == nil {
		return nil
	}
	lang := asciiLower(*stream.Language)
	for _, l := range Languages {
		if slices.Contains(l, lang) {
			return l
		}
	}
	return nil
}

func speaks(stream *MediaStreamDto, language []string) bool {
	l := streamLanguage(stream)
	return l != nil && l[0] == language[0]
}

func isTextSubtitle(stream *MediaStreamDto) bool {
	return stream.Codec != nil && slices.Contains(textSubtitles[:], asciiLower(*stream.Codec))
}

// SubtitleChoiceKind is the variant of a SubtitleChoice.
type SubtitleChoiceKind int

const (
	// SubtitlePlayer: neither the server nor the preferences name one: the player decides.
	SubtitlePlayer SubtitleChoiceKind = iota
	SubtitleNone
	SubtitleStream
)

// SubtitleChoice is the subtitle picked; Index is meaningful for SubtitleStream only.
type SubtitleChoice struct {
	Kind  SubtitleChoiceKind
	Index int32
}

func subtitleStream(i int32) SubtitleChoice { return SubtitleChoice{Kind: SubtitleStream, Index: i} }

// Choice is the streams picked, as Emby `Index` values. Audio nil means none named.
type Choice struct {
	Audio    *int32
	Subtitle SubtitleChoice
}

// Choose picks the audio and subtitle streams of a source. Without a preference the
// server's picks stand. When transcoding, only subtitles the server delivers as
// files can be shown: the others would be burnt into the picture, which fails on the
// test server (ADR 0009). When playing the file, the server's external subtitle
// files are not loaded, so only the file's own count.
func Choose(streams []MediaStreamDto, method PlayMethod, defaultAudio, defaultSubtitle *int32, prefs *TrackPreferences) Choice {
	var audioStreams []*MediaStreamDto
	for i := range streams {
		s := &streams[i]
		if s.StreamType == "Audio" && !s.IsExternal && s.Index != nil {
			audioStreams = append(audioStreams, s)
		}
	}
	var serverAudio *MediaStreamDto
	if defaultAudio != nil {
		for _, s := range audioStreams {
			if sameIndex(s.Index, defaultAudio) {
				serverAudio = s
				break
			}
		}
	}
	var audio *MediaStreamDto
	if prefs.AudioLanguage != nil {
		if lang := spellings(*prefs.AudioLanguage); lang != nil {
			var speaking []*MediaStreamDto
			for _, s := range audioStreams {
				if speaks(s, lang) {
					speaking = append(speaking, s)
				}
			}
			// The server's pick when it already speaks the language, then the file's default.
			if serverAudio != nil {
				for _, s := range speaking {
					if sameIndex(s.Index, serverAudio.Index) {
						audio = s
						break
					}
				}
			}
			if audio == nil {
				for _, s := range speaking {
					if s.IsDefault {
						audio = s
						break
					}
				}
			}
			if audio == nil && len(speaking) > 0 {
				audio = speaking[0]
			}
		}
	}
	if audio == nil {
		audio = serverAudio
	}

	var candidates []*MediaStreamDto
	for i := range streams {
		s := &streams[i]
		if s.StreamType != "Subtitle" || s.Index == nil {
			continue
		}
		var shown bool
		if method == MethodTranscode {
			shown = s.DeliveryMethod != nil && *s.DeliveryMethod == "External" && s.DeliveryURL != nil
		} else {
			shown = !s.IsExternal
		}
		if shown {
			candidates = append(candidates, s)
		}
	}
	isCandidate := func(i int32, extra func(*MediaStreamDto) bool) bool {
		for _, s := range candidates {
			if *s.Index == i && extra(s) {
				return true
			}
		}
		return false
	}
	// Text first, then the file's default flag, then the file's order; the first of
	// equal keys, as min_by_key.
	best := func(pick func(*MediaStreamDto) bool) *int32 {
		var found *MediaStreamDto
		for _, s := range candidates {
			if pick(s) && (found == nil || subtitleRank(s, found) < 0) {
				found = s
			}
		}
		if found == nil {
			return nil
		}
		return found.Index
	}
	var wantedSubtitle []string
	if prefs.SubtitleLanguage != nil {
		wantedSubtitle = spellings(*prefs.SubtitleLanguage)
	}
	streamOrOff := func(i *int32) SubtitleChoice {
		if i == nil {
			return SubtitleChoice{Kind: SubtitleNone}
		}
		return subtitleStream(*i)
	}
	var subtitle SubtitleChoice
	switch prefs.SubtitleMode {
	case SubtitleOff:
		subtitle = SubtitleChoice{Kind: SubtitleNone}
	case SubtitleForcedOnly:
		lang := wantedSubtitle
		if lang == nil && audio != nil {
			lang = streamLanguage(audio)
		}
		var pick *int32
		if lang != nil {
			pick = best(func(s *MediaStreamDto) bool { return s.IsForced && speaks(s, lang) })
		}
		subtitle = streamOrOff(pick)
	case SubtitleAlways:
		var pick *int32
		if lang := wantedSubtitle; lang != nil {
			pick = best(func(s *MediaStreamDto) bool { return !s.IsForced && speaks(s, lang) })
			if pick == nil {
				pick = best(func(s *MediaStreamDto) bool { return s.IsForced && speaks(s, lang) })
			}
		} else {
			if defaultSubtitle != nil && isCandidate(*defaultSubtitle, func(s *MediaStreamDto) bool { return !s.IsForced }) {
				pick = defaultSubtitle
			} else {
				pick = best(func(s *MediaStreamDto) bool { return !s.IsForced })
			}
		}
		subtitle = streamOrOff(pick)
	default: // SubtitleServer
		if defaultSubtitle != nil && isCandidate(*defaultSubtitle, func(*MediaStreamDto) bool { return true }) {
			subtitle = subtitleStream(*defaultSubtitle)
		} else {
			// None, or one this playback cannot show: as before ADR 0035.
			subtitle = SubtitleChoice{Kind: SubtitlePlayer}
		}
	}
	var audioIndex *int32
	if audio != nil && audio.Index != nil {
		i := *audio.Index
		audioIndex = &i
	}
	return Choice{Audio: audioIndex, Subtitle: subtitle}
}

// subtitleRank orders two candidates by (!text, !default, index): negative when a
// comes first.
func subtitleRank(a, b *MediaStreamDto) int {
	if ta, tb := isTextSubtitle(a), isTextSubtitle(b); ta != tb {
		if ta {
			return -1
		}
		return 1
	}
	if a.IsDefault != b.IsDefault {
		if a.IsDefault {
			return -1
		}
		return 1
	}
	switch {
	case *a.Index < *b.Index:
		return -1
	case *a.Index > *b.Index:
		return 1
	}
	return 0
}

// Selection is what the player selects once the file is loaded. A transcode carries
// one audio track, the one asked of the server, and its subtitles come as files
// selected when added.
func (c Choice) Selection(method PlayMethod) player.TrackSelection {
	var out player.TrackSelection
	switch {
	case c.Subtitle.Kind == SubtitleNone:
		out.Subtitle = player.TrackPick{Kind: player.TrackOff}
	case c.Subtitle.Kind == SubtitleStream && method != MethodTranscode:
		out.Subtitle = player.PickStream(int64(c.Subtitle.Index))
	}
	if c.Audio != nil && method != MethodTranscode {
		out.Audio = player.PickStream(int64(*c.Audio))
	}
	return out
}

// Apply picks the tracks of a plan and marks them: the player's selection, and for a
// transcode which subtitle file is selected when added.
func Apply(plan *PlaybackPlan, prefs *TrackPreferences) Choice {
	choice := Choose(plan.Streams, plan.Method, plan.DefaultAudioStreamIndex, plan.DefaultSubtitleStreamIndex, prefs)
	if plan.Method == MethodTranscode {
		var wanted *int32
		if choice.Subtitle.Kind == SubtitleStream {
			i := choice.Subtitle.Index
			wanted = &i
		}
		// Same filter and order as the files of Plan; zipped, so the shorter ends it.
		n := 0
		for i := range plan.Streams {
			s := &plan.Streams[i]
			if !isExternalSubtitleFile(s) {
				continue
			}
			if n >= len(plan.Subtitles) {
				break
			}
			plan.Subtitles[n].Selected = wanted != nil && sameIndex(s.Index, wanted)
			n++
		}
	}
	plan.Tracks = choice.Selection(plan.Method)
	return choice
}

// AccountTracks are the audio and subtitle preferences of the Emby account (ADR
// 0115), shown next to the local settings. The server applies them itself when it
// picks the default streams of PlaybackInfo, which is what SubtitleServer and an
// empty audio language keep.
type AccountTracks struct {
	// AudioLanguages: Emby codes in preference order ("fre", "eng").
	AudioLanguages List[string] `json:"audioLanguages"`
	// PlayDefaultAudio is `PlayDefaultAudioTrack`: the file's default audio, whatever its language.
	PlayDefaultAudio  bool         `json:"playDefaultAudio"`
	SubtitleLanguages List[string] `json:"subtitleLanguages"`
	// SubtitleMode among the documented values; nil when absent or unknown.
	SubtitleMode *string `json:"subtitleMode"`
}

// accountSubtitleModes are the values of `SubtitlePlaybackMode` in the Emby documentation.
var accountSubtitleModes = [...]string{"Default", "Always", "OnlyForced", "None", "Smart", "HearingImpaired"}

// accountLanguages reads "fre,eng" in order; anything that is not a language code is dropped.
func accountLanguages(preference *string) List[string] {
	var out List[string]
	if preference == nil {
		return out
	}
	for code := range strings.SplitSeq(*preference, ",") {
		code = strings.TrimSpace(code)
		usable := len(code) >= 2 && len(code) <= 8 && !strings.ContainsFunc(code, func(r rune) bool {
			return !((r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') || r == '-')
		})
		code = asciiLower(code)
		if usable && !slices.Contains(out, code) {
			out = append(out, code)
		}
	}
	return out
}

// AccountTracksOf reads the four fields from the user's `Configuration` (nil when
// absent): an object or the first object of an array. Anything else (`ProfilePin`…)
// is never read. Keys are exact, as in serde_json's map; text that is not JSON reads
// as nothing.
func AccountTracksOf(configuration json.RawMessage) AccountTracks {
	var config any
	if len(configuration) > 0 && json.Unmarshal(configuration, &config) != nil {
		return AccountTracks{}
	}
	if items, ok := config.([]any); ok {
		config = nil
		if len(items) > 0 {
			config = items[0]
		}
	}
	object, ok := config.(map[string]any)
	if !ok {
		return AccountTracks{}
	}
	text := func(key string) *string {
		if s, ok := object[key].(string); ok {
			return &s
		}
		return nil
	}
	out := AccountTracks{
		AudioLanguages:    accountLanguages(text("AudioLanguagePreference")),
		SubtitleLanguages: accountLanguages(text("SubtitleLanguagePreference")),
	}
	if b, ok := object["PlayDefaultAudioTrack"].(bool); ok {
		out.PlayDefaultAudio = b
	}
	if mode := text("SubtitleMode"); mode != nil {
		trimmed := strings.TrimSpace(*mode)
		for _, m := range accountSubtitleModes {
			if asciiEqualFold(m, trimmed) {
				found := m
				out.SubtitleMode = &found
				break
			}
		}
	}
	return out
}
