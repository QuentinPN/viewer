package emby

// Playback negotiation, port of src-tauri/src/emby/playback.rs:
// the libmpv device profile sent to PlaybackInfo, the choice between Direct Play,
// Direct Stream and an HLS transcode from the server's answer, and stream and
// subtitle URLs with their token removed (the token travels in a header).
//
// Stream URLs are parsed with the WHATWG parser of server_url.go, as the Rust code
// uses the `url` crate; the query is read and written back as the crate's
// form_urlencoded does (research/stream-url.md).

import (
	"cmp"
	"slices"
	"strconv"
	"strings"
	"unicode"
	"unicode/utf8"

	"viewer/internal/formenc"
	"viewer/internal/player"
)

// MaxStreamingBitrate is sent when no cap is chosen ("Maximale").
const MaxStreamingBitrate uint64 = 400_000_000

// externalTextSubtitles are the text formats a transcode can deliver as separate
// files instead of burning them into the picture (burn-in fails on the test server).
var externalTextSubtitles = [...]string{"ass", "ssa", "subrip", "srt", "vtt"}

// tokenParams are the query parameters that carry the token in server-built URLs
// (compared lowercase).
var tokenParams = [...]string{"api_key", "apikey", "x-emby-token", "accesstoken"}

// DeviceProfileFor is what libmpv (FFmpeg) decodes. Declared, not tested one by one:
// see the playback matrix in TESTING.md for verified combinations. A
// maxStreamingBitrate above a file's bitrate makes the server transcode it.
func DeviceProfileFor(maxStreamingBitrate uint64) DeviceProfile {
	var subtitles List[SubtitleProfile]
	for _, format := range []string{"ass", "ssa", "subrip", "srt", "pgssub", "dvdsub", "vtt", "mov_text"} {
		// Embedded when playing the file.
		subtitles = append(subtitles, SubtitleProfile{Format: format, Method: "Embed"})
	}
	for _, format := range externalTextSubtitles {
		// Text tracks of a transcode come as separate files (MPEG-TS cannot carry them).
		subtitles = append(subtitles, SubtitleProfile{Format: format, Method: "External"})
	}
	return DeviceProfile{
		Name:                "Viewer (libmpv)",
		MaxStreamingBitrate: maxStreamingBitrate,
		DirectPlayProfiles: List[DirectPlayProfile]{{
			Kind:       "Video",
			Container:  "mkv,webm,mp4,m4v,mov,avi,ts,m2ts,mpegts,mpg,mpeg,wmv,asf,flv,3gp,ogv",
			VideoCodec: "h264,hevc,av1,vp8,vp9,mpeg4,mpeg2video,mpeg1video,vc1,wmv3,msmpeg4v3,h263,theora",
			AudioCodec: "aac,ac3,eac3,dts,truehd,mlp,flac,opus,vorbis,mp3,mp2,alac,pcm_s16le,pcm_s24le,pcm_bluray,pcm_dvd,wmav2,wmapro",
		}},
		// HLS in MPEG-TS, read by mpv; audio copied when it fits.
		TranscodingProfiles: List[TranscodingProfile]{{
			Kind:                "Video",
			Container:           "ts",
			Protocol:            "hls",
			VideoCodec:          "h264",
			AudioCodec:          "aac,ac3,eac3",
			Context:             "Streaming",
			MaxAudioChannels:    "8",
			BreakOnNonKeyFrames: true,
		}},
		SubtitleProfiles: subtitles,
	}
}

// PlaybackPlan is the source chosen for the player. The URL holds no token. It is
// internal, never sent to the UI as such.
type PlaybackPlan struct {
	Method PlayMethod
	URL    string
	// Headers: extra headers the server requires for this source (never authentication).
	Headers       []player.Header
	MediaSourceID string
	PlaySessionID *string
	Container     *string
	RunTimeTicks  *int64
	// StartTicks is where the player starts: 0, or the server's resume position.
	StartTicks int64
	// TranscodeReasons: French reasons, for a transcode only.
	TranscodeReasons *string
	// Subtitles: text subtitles a transcode delivers as files, URLs without token.
	Subtitles []player.ExternalSubtitle
	// Title is set by the session from the item, not by PlaybackInfo.
	Title *PlaybackTitle
	// BitrateLimit is set by the session from the bitrate setting.
	BitrateLimit *StreamingLimit
	// Streams: the source's streams (`DeliveryUrl` without token) and the server's
	// picks, for the track choice.
	Streams                    []MediaStreamDto
	DefaultAudioStreamIndex    *int32
	DefaultSubtitleStreamIndex *int32
	// Tracks is set by the session from the user's preferences.
	Tracks player.TrackSelection
}

// sameIndex is Rust's `Option<i32> == Option<i32>`.
func sameIndex(a, b *int32) bool {
	if a == nil || b == nil {
		return a == nil && b == nil
	}
	return *a == *b
}

// Plan picks the source asked for, else the first the server offers, and
// the best way to play it. A version the server did not offer is not replaced by
// another. A nil mediaSourceID means none asked for.
func Plan(server ServerURL, response PlaybackInfoResponse, mediaSourceID *string) (PlaybackPlan, error) {
	if response.ErrorCode != nil {
		switch *response.ErrorCode {
		case "NotAllowed":
			return PlaybackPlan{}, NewPlaybackRefused("lecture non autorisée pour ce compte")
		case "RateLimitExceeded":
			return PlaybackPlan{}, NewPlaybackRefused("trop de lectures en cours sur le serveur")
		default:
			return PlaybackPlan{}, ErrNoCompatibleStream
		}
	}
	var source *MediaSourceInfo
	for i := range response.MediaSources {
		if mediaSourceID == nil || response.MediaSources[i].ID == *mediaSourceID {
			source = &response.MediaSources[i]
			break
		}
	}
	if source == nil {
		return PlaybackPlan{}, ErrNoCompatibleStream
	}
	// A transcode also fills `DirectStreamUrl` with the transcoding URL, so the flags
	// decide, not which URL is present.
	var method PlayMethod
	var raw string
	switch {
	case source.DirectStreamURL != nil && source.SupportsDirectPlay:
		method, raw = MethodDirectPlay, *source.DirectStreamURL
	case source.DirectStreamURL != nil && source.SupportsDirectStream:
		method, raw = MethodDirectStream, *source.DirectStreamURL
	case source.TranscodingURL != nil && source.SupportsTranscoding:
		method, raw = MethodTranscode, *source.TranscodingURL
	default:
		return PlaybackPlan{}, ErrNoCompatibleStream
	}
	transcode := method == MethodTranscode
	var subtitles []player.ExternalSubtitle
	if transcode {
		var err error
		subtitles, err = externalSubtitles(server, source.MediaStreams, source.DefaultSubtitleStreamIndex)
		if err != nil {
			return PlaybackPlan{}, err
		}
	}
	url, err := StreamURL(server, raw)
	if err != nil {
		return PlaybackPlan{}, err
	}
	container := source.Container
	var reasons *string
	if transcode {
		container = source.TranscodingContainer
		r := transcodeReasons(raw)
		reasons = &r
	}
	var runTime *int64
	if source.RunTimeTicks != nil && *source.RunTimeTicks > 0 {
		t := *source.RunTimeTicks
		runTime = &t
	}
	// `DeliveryUrl` holds the token: kept without it, as the files are. The streams
	// are copied so the caller's response is left as it was.
	streams := slices.Clone(source.MediaStreams)
	for i := range streams {
		if streams[i].DeliveryURL == nil {
			continue
		}
		if u, err := StreamURL(server, *streams[i].DeliveryURL); err == nil {
			streams[i].DeliveryURL = &u
		} else {
			streams[i].DeliveryURL = nil
		}
	}
	return PlaybackPlan{
		Method:                     method,
		URL:                        url,
		Headers:                    requiredHeaders(source.RequiredHTTPHeaders),
		MediaSourceID:              source.ID,
		PlaySessionID:              response.PlaySessionID,
		Container:                  container,
		RunTimeTicks:               runTime,
		StartTicks:                 0,
		TranscodeReasons:           reasons,
		Subtitles:                  subtitles,
		Streams:                    streams,
		DefaultAudioStreamIndex:    source.DefaultAudioStreamIndex,
		DefaultSubtitleStreamIndex: source.DefaultSubtitleStreamIndex,
	}, nil
}

// isExternalSubtitleFile: a subtitle the server delivers as a separate file.
func isExternalSubtitleFile(s *MediaStreamDto) bool {
	return s.StreamType == "Subtitle" && s.DeliveryMethod != nil && *s.DeliveryMethod == "External" &&
		s.DeliveryURL != nil
}

// externalSubtitles are the subtitles the server delivers as separate files
// (`External`), in its order; the one it picked from the user's preferences is selected.
func externalSubtitles(server ServerURL, streams []MediaStreamDto, defaultIndex *int32) ([]player.ExternalSubtitle, error) {
	var out []player.ExternalSubtitle
	for i := range streams {
		s := &streams[i]
		if !isExternalSubtitleFile(s) {
			continue
		}
		source, err := StreamURL(server, *s.DeliveryURL)
		if err != nil {
			return nil, err
		}
		out = append(out, player.ExternalSubtitle{
			Source:   source,
			Title:    s.Title,
			Lang:     s.Language,
			Selected: defaultIndex != nil && sameIndex(s.Index, defaultIndex),
		})
	}
	return out, nil
}

// StreamURL is the absolute stream URL from a server-relative `/videos/…` path,
// without any token-bearing parameter. Anything else (absolute URL, other path) is refused.
func StreamURL(server ServerURL, raw string) (string, error) {
	invalid := NewInvalidResponse("adresse de flux inattendue")
	if !strings.HasPrefix(raw, "/") || strings.HasPrefix(raw, "//") || strings.Contains(raw, `\`) {
		return "", invalid
	}
	base, msg := parseURL(server.API("/"))
	if msg != "" {
		return "", invalid
	}
	// The WHATWG parser resolves `..` and `%2e%2e`, so the prefix check sees the final path.
	full := server.API(raw)
	u, msg := parseURL(full)
	if msg != "" {
		return "", invalid
	}
	videos := base.path + "videos/"
	if u.scheme != base.scheme || u.host != base.host || u.port != base.port ||
		!strings.HasPrefix(asciiLower(u.path), asciiLower(videos)) {
		return "", invalid
	}
	var kept [][2]string
	for _, pair := range formParse(rawQuery(full)) {
		if !slices.Contains(tokenParams[:], asciiLower(pair[0])) {
			kept = append(kept, pair)
		}
	}
	var b strings.Builder
	b.WriteString(u.scheme)
	b.WriteString("://")
	b.WriteString(u.host.String())
	if u.port >= 0 {
		b.WriteByte(':')
		b.WriteString(strconv.Itoa(u.port))
	}
	b.WriteString(u.path)
	if len(kept) > 0 {
		b.WriteByte('?')
		b.WriteString(formenc.Serialize(kept))
	}
	return b.String(), nil
}

// rawQuery is the query text the WHATWG parser sees: after the input cleanup (C0
// controls and spaces trimmed, tabs and newlines removed), between the first `?`
// and the fragment. The authority of a URL built by ServerURL.API holds neither.
func rawQuery(input string) string {
	input = strings.TrimFunc(input, func(r rune) bool { return r <= 0x20 })
	input = strings.Map(func(r rune) rune {
		if r == '\t' || r == '\n' || r == '\r' {
			return -1
		}
		return r
	}, input)
	i := strings.IndexAny(input, "?#")
	if i < 0 || input[i] != '?' {
		return ""
	}
	q := input[i+1:]
	if j := strings.IndexByte(q, '#'); j >= 0 {
		q = q[:j]
	}
	return q
}

// formParse is `form_urlencoded::parse`: `&`-separated, empty pieces skipped, split
// at the first `=`, `+` as space, lenient percent-decoding, invalid UTF-8 replaced.
func formParse(query string) [][2]string {
	var out [][2]string
	for piece := range strings.SplitSeq(query, "&") {
		if piece == "" {
			continue
		}
		name, value, _ := strings.Cut(piece, "=")
		out = append(out, [2]string{formDecode(name), formDecode(value)})
	}
	return out
}

func formDecode(s string) string {
	b := make([]byte, 0, len(s))
	for i := 0; i < len(s); i++ {
		c := s[i]
		switch {
		case c == '+':
			b = append(b, ' ')
		case c == '%' && i+2 < len(s) && isHex(s[i+1]) && isHex(s[i+2]):
			b = append(b, hexVal(s[i+1])<<4|hexVal(s[i+2]))
			i += 2
		default:
			b = append(b, c)
		}
	}
	if utf8.Valid(b) {
		return string(b)
	}
	return strings.ToValidUTF8(string(b), "�")
}

// asciiLower is `to_ascii_lowercase`: only A-Z change.
func asciiLower(s string) string {
	return strings.Map(func(r rune) rune {
		if r >= 'A' && r <= 'Z' {
			return r + ('a' - 'A')
		}
		return r
	}, s)
}

// asciiEqualFold is `eq_ignore_ascii_case` (strings.EqualFold also folds Unicode,
// e.g. the Kelvin sign with k).
func asciiEqualFold(a, b string) bool {
	return len(a) == len(b) && asciiLower(a) == asciiLower(b)
}

// requiredHeaders keeps the server-required headers that are safe to hand to mpv:
// plain names, no control characters, nothing that could replace authentication.
// Sorted by name then value (the map has no order).
func requiredHeaders(headers map[string]string) []player.Header {
	var out []player.Header
	for name, value := range headers {
		lower := asciiLower(name)
		if name == "" || !plainHeaderName(name) || strings.Contains(lower, "auth") ||
			strings.Contains(lower, "token") || strings.Contains(lower, "cookie") ||
			strings.ContainsFunc(value, func(r rune) bool { return unicode.IsControl(r) || r == ',' }) {
			continue
		}
		out = append(out, player.Header{name, value})
	}
	slices.SortFunc(out, func(a, b player.Header) int {
		return cmp.Or(cmp.Compare(a[0], b[0]), cmp.Compare(a[1], b[1]))
	})
	return out
}

func plainHeaderName(name string) bool {
	for _, r := range name {
		if !((r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') || (r >= '0' && r <= '9') || r == '-') {
			return false
		}
	}
	return true
}

// transcodeReasons are the French reasons from `TranscodeReasons=` in a transcoding
// URL. As in Rust, the query is everything after the first `?`, fragment included.
func transcodeReasons(raw string) string {
	_, query, _ := strings.Cut(raw, "?")
	var reasons []string
	for _, pair := range formParse(query) {
		if !asciiEqualFold(pair[0], "TranscodeReasons") {
			continue
		}
		for r := range strings.SplitSeq(pair[1], ",") {
			reasons = append(reasons, reasonLabel(strings.TrimSpace(r)))
		}
	}
	if len(reasons) == 0 {
		return "raison non précisée"
	}
	return strings.Join(reasons, ", ")
}

func reasonLabel(reason string) string {
	switch reason {
	case "ContainerNotSupported":
		return "conteneur non pris en charge"
	case "VideoCodecNotSupported":
		return "codec vidéo non pris en charge"
	case "AudioCodecNotSupported":
		return "codec audio non pris en charge"
	case "SubtitleCodecNotSupported":
		return "sous-titres non pris en charge"
	case "ContainerBitrateExceedsLimit", "VideoBitrateNotSupported":
		return "débit trop élevé"
	case "AudioChannelsNotSupported":
		return "nombre de canaux audio non pris en charge"
	case "DirectPlayError":
		return "échec de la lecture directe"
	default:
		return "autre raison"
	}
}

// ---------------------------------------------------- emby_play (commands.rs)

// NewPlaybackStart is the PlaybackStart `emby_play` returns to the UI, from the plan
// the session prepared (commands.rs, mirrored by playback_start in golden.rs).
func NewPlaybackStart(itemID string, resume bool, plan *PlaybackPlan) PlaybackStart {
	start := PlaybackStart{
		MediaSourceID:    plan.MediaSourceID,
		ItemID:           itemID,
		Resume:           resume,
		Method:           plan.Method,
		Container:        plan.Container,
		TranscodeReasons: plan.TranscodeReasons,
		Title:            plan.Title,
		LocalAddress:     true,
		Origin:           OriginEmby,
	}
	if limit := plan.BitrateLimit; limit != nil {
		start.BitrateCap = limit.Cap
		start.LocalAddress = limit.LocalAddress
	}
	return start
}

// EngineRequest is the request `emby_play` hands to the engine (commands.rs,
// mirrored by engine_request in golden.rs): the plan's URL, subtitle files and
// tracks, the headers the session returned (required ones plus authentication) and
// the subtitle style of the preferences.
func EngineRequest(plan *PlaybackPlan, headers []player.Header, style player.SubtitleStyle) player.PlaybackRequest {
	var start *float64
	if plan.StartTicks > 0 {
		s := float64(plan.StartTicks) / 10_000_000.0
		start = &s
	}
	return player.PlaybackRequest{
		Source:        plan.URL,
		HTTPHeaders:   slices.Clone(headers),
		StartSeconds:  start,
		Subtitles:     slices.Clone(plan.Subtitles),
		Tracks:        plan.Tracks,
		SubtitleStyle: style,
	}
}
