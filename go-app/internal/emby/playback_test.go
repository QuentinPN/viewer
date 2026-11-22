package emby

import (
	"encoding/json"
	"errors"
	"slices"
	"strings"
	"testing"

	"viewer/internal/player"
)

func pbServer(t *testing.T) ServerURL {
	t.Helper()
	s, err := ParseServerURL("http://192.168.1.20:8096")
	if err != nil {
		t.Fatal(err)
	}
	return s
}

func pbSource(t *testing.T, js string) PlaybackInfoResponse {
	t.Helper()
	var r PlaybackInfoResponse
	if err := json.Unmarshal([]byte(js), &r); err != nil {
		t.Fatal(err)
	}
	return r
}

func pbPlan(t *testing.T, js string, version *string) PlaybackPlan {
	t.Helper()
	p, err := Plan(pbServer(t), pbSource(t, js), version)
	if err != nil {
		t.Fatalf("plan: %v", err)
	}
	return p
}

func pbKind(t *testing.T, err error) ErrorKind {
	t.Helper()
	k, ok := KindOf(err)
	if !ok {
		t.Fatalf("not an EmbyError: %v", err)
	}
	return k
}

func pbDeref[T any](p *T) any {
	if p == nil {
		return nil
	}
	return *p
}

// Shape of a real Emby 4.10.1.0 answer (ids changed, token replaced).
const pbDirect = `{"MediaSources":[{"Protocol":"File","Id":"mediasource_30207",
        "Type":"Default","Container":"mkv","SupportsTranscoding":true,
        "SupportsDirectStream":true,"SupportsDirectPlay":true,"RequiredHttpHeaders":{},
        "DirectStreamUrl":"/videos/30207/original.mkv?DeviceId=dev&MediaSourceId=mediasource_30207&PlaySessionId=abc&api_key=SECRET",
        "AddApiKeyToDirectStreamUrl":false,"DefaultAudioStreamIndex":1}],"PlaySessionId":"abc"}`

// emby/playback.rs direct_play_without_token_in_url
func Test_direct_play_without_token_in_url(t *testing.T) {
	plan := pbPlan(t, pbDirect, nil)
	if plan.Method != MethodDirectPlay {
		t.Fatal(plan.Method)
	}
	if plan.URL != "http://192.168.1.20:8096/emby/videos/30207/original.mkv?DeviceId=dev&MediaSourceId=mediasource_30207&PlaySessionId=abc" {
		t.Fatal(plan.URL)
	}
	if strings.Contains(plan.URL, "SECRET") {
		t.Fatal("token in URL")
	}
	if plan.MediaSourceID != "mediasource_30207" || pbDeref(plan.PlaySessionID) != "abc" ||
		pbDeref(plan.Container) != "mkv" || len(plan.Headers) != 0 {
		t.Fatalf("%+v", plan)
	}
}

// emby/playback.rs direct_stream_when_only_that_is_allowed
func Test_direct_stream_when_only_that_is_allowed(t *testing.T) {
	js := strings.Replace(pbDirect, `"SupportsDirectPlay":true`, `"SupportsDirectPlay":false`, 1)
	if m := pbPlan(t, js, nil).Method; m != MethodDirectStream {
		t.Fatal(m)
	}
}

// Shape of a real Emby 4.10.1.0 transcode (bitrate above the limit): the transcoding
// URL is in both fields, text subtitles are `External`.
const pbTranscode = `{"MediaSources":[{"Id":"mediasource_30207","Container":"mkv",
        "SupportsDirectPlay":false,"SupportsDirectStream":false,"SupportsTranscoding":true,
        "DirectStreamUrl":"/videos/30207/master.m3u8?PlaySessionId=p&api_key=SECRET&TranscodeReasons=ContainerBitrateExceedsLimit",
        "TranscodingUrl":"/videos/30207/master.m3u8?PlaySessionId=p&api_key=SECRET&TranscodeReasons=ContainerBitrateExceedsLimit",
        "TranscodingSubProtocol":"hls","TranscodingContainer":"ts","DefaultSubtitleStreamIndex":4,
        "MediaStreams":[
          {"Type":"Video","Index":0,"Codec":"h264"},
          {"Type":"Subtitle","Index":3,"Codec":"subrip","Language":"fre","Title":"Forcés",
           "DeliveryMethod":"External","DeliveryUrl":"/Videos/30207/mediasource_30207/Subtitles/3/0/Stream.subrip?api_key=SECRET"},
          {"Type":"Subtitle","Index":4,"Codec":"subrip","Language":"eng",
           "DeliveryMethod":"External","DeliveryUrl":"/Videos/30207/mediasource_30207/Subtitles/4/0/Stream.subrip?api_key=SECRET"},
          {"Type":"Subtitle","Index":5,"Codec":"PGSSUB","DeliveryMethod":"Encode"}]}],
        "PlaySessionId":"p"}`

type pbSub struct {
	source      string
	lang, title any
	selected    bool
}

func pbSubs(plan PlaybackPlan) []pbSub {
	var out []pbSub
	for _, s := range plan.Subtitles {
		out = append(out, pbSub{s.Source, pbDeref(s.Lang), pbDeref(s.Title), s.Selected})
	}
	return out
}

// emby/playback.rs transcode_plays_hls_with_external_text_subtitles
func Test_transcode_plays_hls_with_external_text_subtitles(t *testing.T) {
	plan := pbPlan(t, pbTranscode, nil)
	if plan.Method != MethodTranscode {
		t.Fatal(plan.Method)
	}
	if plan.URL != "http://192.168.1.20:8096/emby/videos/30207/master.m3u8?PlaySessionId=p&TranscodeReasons=ContainerBitrateExceedsLimit" {
		t.Fatal(plan.URL)
	}
	if pbDeref(plan.Container) != "ts" || pbDeref(plan.TranscodeReasons) != "débit trop élevé" {
		t.Fatalf("%+v", plan)
	}
	want := []pbSub{
		{"http://192.168.1.20:8096/emby/Videos/30207/mediasource_30207/Subtitles/3/0/Stream.subrip", "fre", "Forcés", false},
		{"http://192.168.1.20:8096/emby/Videos/30207/mediasource_30207/Subtitles/4/0/Stream.subrip", "eng", nil, true},
	}
	if got := pbSubs(plan); !slices.Equal(got, want) {
		t.Fatalf("%#v", got)
	}
	// Rust checks the Debug text; every exported field, streams included, is in the JSON.
	if strings.Contains(string(marshal(t, plan)), "SECRET") {
		t.Fatal("token kept in the plan")
	}
}

// emby/playback.rs transcode_reasons_in_french_and_no_subtitles_when_playing_the_file
func Test_transcode_reasons_in_french_and_no_subtitles_when_playing_the_file(t *testing.T) {
	js := strings.ReplaceAll(pbTranscode, "ContainerBitrateExceedsLimit", "VideoCodecNotSupported,AudioCodecNotSupported")
	if r := pbDeref(pbPlan(t, js, nil).TranscodeReasons); r != "codec vidéo non pris en charge, codec audio non pris en charge" {
		t.Fatal(r)
	}
	direct := strings.Replace(pbTranscode, `"SupportsDirectPlay":false`, `"SupportsDirectPlay":true`, 1)
	plan := pbPlan(t, direct, nil)
	if plan.Method != MethodDirectPlay || len(plan.Subtitles) != 0 || plan.TranscodeReasons != nil ||
		pbDeref(plan.Container) != "mkv" {
		t.Fatalf("%+v", plan)
	}
}

// Two versions in one answer, as a server that ignores `MediaSourceId` would send
// them (ADR 0036).
const pbTwoVersions = `{"MediaSources":[
        {"Id":"mediasource_30500","Container":"mkv","SupportsDirectPlay":true,
         "DirectStreamUrl":"/videos/30500/original.mkv?MediaSourceId=mediasource_30500&api_key=SECRET"},
        {"Id":"30501","Container":"mp4","SupportsDirectPlay":false,"SupportsTranscoding":true,
         "TranscodingUrl":"/videos/30500/master.m3u8?MediaSourceId=30501&api_key=SECRET&TranscodeReasons=VideoBitrateNotSupported"}],
        "PlaySessionId":"p"}`

// emby/playback.rs the_version_asked_for_is_played_with_the_servers_decision_for_it
func Test_the_version_asked_for_is_played_with_the_servers_decision_for_it(t *testing.T) {
	first := pbPlan(t, pbTwoVersions, nil)
	if first.MediaSourceID != "mediasource_30500" || first.Method != MethodDirectPlay {
		t.Fatalf("%+v", first)
	}
	second := pbPlan(t, pbTwoVersions, ptr("30501"))
	if second.MediaSourceID != "30501" || second.Method != MethodTranscode || second.Container != nil ||
		pbDeref(second.TranscodeReasons) != "débit trop élevé" {
		t.Fatalf("%+v", second)
	}
	if !strings.Contains(second.URL, "MediaSourceId=30501") || strings.Contains(second.URL, "SECRET") {
		t.Fatal(second.URL)
	}
	// Never another version in place of the one chosen.
	_, err := Plan(pbServer(t), pbSource(t, pbTwoVersions), ptr("30502"))
	if pbKind(t, err) != KindNoCompatible {
		t.Fatal(err)
	}
}

// emby/playback.rs subtitle_urls_off_the_server_are_refused
func Test_subtitle_urls_off_the_server_are_refused(t *testing.T) {
	js := strings.Replace(pbTranscode, "/Videos/30207/mediasource_30207/Subtitles/4/0/Stream.subrip", "http://evil.example/sub.srt", 1)
	if _, err := Plan(pbServer(t), pbSource(t, js), nil); err == nil {
		t.Fatal("accepted")
	}
}

// emby/playback.rs server_error_codes_and_empty_answers
func Test_server_error_codes_and_empty_answers(t *testing.T) {
	_, err := Plan(pbServer(t), pbSource(t, `{"ErrorCode":"NotAllowed"}`), nil)
	if pbKind(t, err) != KindPlaybackRefused {
		t.Fatal(err)
	}
	_, err = Plan(pbServer(t), pbSource(t, `{"ErrorCode":"RateLimitExceeded"}`), nil)
	if pbKind(t, err) != KindPlaybackRefused {
		t.Fatal(err)
	}
	for _, js := range []string{
		`{"ErrorCode":"NoCompatibleStream"}`,
		`{"MediaSources":[]}`,
		`{"MediaSources":[{"Id":"m","SupportsDirectPlay":true}]}`,
		`{"MediaSources":[{"Id":"m","SupportsDirectPlay":false,"SupportsDirectStream":false,
                "SupportsTranscoding":false,"DirectStreamUrl":"/videos/1/original.mkv"}]}`,
	} {
		_, err := Plan(pbServer(t), pbSource(t, js), nil)
		if pbKind(t, err) != KindNoCompatible {
			t.Errorf("%s: %v", js, err)
		}
	}
}

// emby/playback.rs stream_url_stays_under_videos_on_the_server
func Test_stream_url_stays_under_videos_on_the_server(t *testing.T) {
	s := pbServer(t)
	for _, raw := range []string{
		"http://evil.example/videos/1/original.mkv",
		"//evil.example/videos/1/original.mkv",
		"/Users/1/Items",
		"/videos/../Users/1",
		"/videos/%2e%2e/Users/1",
		"videos/1/original.mkv",
		`/videos\..\Users`,
	} {
		if _, err := StreamURL(s, raw); err == nil {
			t.Error(raw)
		}
	}
	got, err := StreamURL(s, "/Videos/1/stream.mkv?Static=true&ApiKey=x&X-Emby-Token=y&AccessToken=z#f")
	if err != nil || got != "http://192.168.1.20:8096/emby/Videos/1/stream.mkv?Static=true" {
		t.Fatal(got, err)
	}
}

// emby/playback.rs only_plain_required_headers_are_kept
func Test_only_plain_required_headers_are_kept(t *testing.T) {
	headers := map[string]string{
		"Referer":       "x",
		"X-Emby-Token":  "t",
		"Authorization": "a",
		"Bad Name":      "v",
		"X-Ok":          "line\r\nInjected: 1",
		"Cookie":        "c",
	}
	if got := requiredHeaders(headers); !slices.Equal(got, []player.Header{{"Referer", "x"}}) {
		t.Fatal(got)
	}
}

// emby/playback.rs profile_serializes_as_emby_expects
func Test_profile_serializes_as_emby_expects(t *testing.T) {
	var js struct {
		DirectPlayProfiles  []map[string]any
		TranscodingProfiles []map[string]any
		SubtitleProfiles    []map[string]any
		MaxStreamingBitrate uint64
	}
	if err := json.Unmarshal(marshal(t, DeviceProfileFor(MaxStreamingBitrate)), &js); err != nil {
		t.Fatal(err)
	}
	if js.DirectPlayProfiles[0]["Type"] != "Video" ||
		!strings.Contains(js.DirectPlayProfiles[0]["VideoCodec"].(string), "hevc") ||
		js.TranscodingProfiles[0]["Protocol"] != "hls" || js.SubtitleProfiles[0]["Method"] != "Embed" {
		t.Fatalf("%+v", js)
	}
	var external []string
	for _, p := range js.SubtitleProfiles {
		if p["Method"] == "External" {
			external = append(external, p["Format"].(string))
		}
	}
	if !slices.Equal(external, externalTextSubtitles[:]) || js.MaxStreamingBitrate != MaxStreamingBitrate {
		t.Fatal(external, js.MaxStreamingBitrate)
	}
}

// ------------------------------------------- Go additions: branches Rust leaves untested

// The flags decide: a transcode whose two URLs differ plays the transcoding one.
func Test_go_transcode_uses_the_transcoding_url_not_the_direct_one(t *testing.T) {
	js := `{"MediaSources":[{"Id":"m","SupportsTranscoding":true,
        "DirectStreamUrl":"/videos/1/original.mkv","TranscodingUrl":"/videos/1/master.m3u8?TranscodeReasons=ContainerNotSupported",
        "Container":"avi","TranscodingContainer":"ts","RunTimeTicks":0}]}`
	plan := pbPlan(t, js, nil)
	if plan.Method != MethodTranscode || plan.URL != "http://192.168.1.20:8096/emby/videos/1/master.m3u8?TranscodeReasons=ContainerNotSupported" ||
		pbDeref(plan.Container) != "ts" || plan.RunTimeTicks != nil || plan.PlaySessionID != nil {
		t.Fatalf("%+v", plan)
	}
	// Direct stream needs DirectStreamUrl even when allowed: without it, the transcode.
	js = `{"MediaSources":[{"Id":"m","SupportsDirectStream":true,"SupportsTranscoding":true,
        "TranscodingUrl":"/videos/1/master.m3u8"}]}`
	if m := pbPlan(t, js, nil).Method; m != MethodTranscode {
		t.Fatal(m)
	}
	// A transcode allowed without a transcoding URL is no stream.
	js = `{"MediaSources":[{"Id":"m","SupportsTranscoding":true,"DirectStreamUrl":"/videos/1/master.m3u8"}]}`
	if _, err := Plan(pbServer(t), pbSource(t, js), nil); pbKind(t, err) != KindNoCompatible {
		t.Fatal(err)
	}
}

func Test_go_run_time_is_kept_only_when_positive(t *testing.T) {
	for ticks, want := range map[string]any{"-5": nil, "0": nil, "7": int64(7)} {
		js := `{"MediaSources":[{"Id":"m","SupportsDirectPlay":true,"DirectStreamUrl":"/videos/1/a.mkv","RunTimeTicks":` + ticks + `}]}`
		if got := pbDeref(pbPlan(t, js, nil).RunTimeTicks); got != want {
			t.Error(ticks, got)
		}
	}
}

// Any other error code, even empty, is no compatible stream (Some("") in Rust).
func Test_go_unknown_or_empty_error_codes_mean_no_stream(t *testing.T) {
	for _, js := range []string{`{"ErrorCode":"Other","MediaSources":[{"Id":"m","SupportsDirectPlay":true,"DirectStreamUrl":"/videos/1/a.mkv"}]}`, `{"ErrorCode":""}`} {
		if _, err := Plan(pbServer(t), pbSource(t, js), nil); pbKind(t, err) != KindNoCompatible {
			t.Error(js, err)
		}
	}
	// The refusal keeps its French reason.
	_, err := Plan(pbServer(t), pbSource(t, `{"ErrorCode":"RateLimitExceeded"}`), nil)
	if err.Error() != "lecture refusée : trop de lectures en cours sur le serveur" {
		t.Fatal(err)
	}
}

// The streams keep their DeliveryUrl without the token, or none when it is off the
// server; the response given to Plan is left unchanged.
func Test_go_stream_delivery_urls_lose_the_token_or_are_dropped(t *testing.T) {
	js := `{"MediaSources":[{"Id":"m","SupportsDirectPlay":true,"DirectStreamUrl":"/videos/1/a.mkv",
        "RequiredHttpHeaders":{"X-B":"2","X-A":"1"},
        "MediaStreams":[
          {"Type":"Subtitle","Index":2,"DeliveryMethod":"External","DeliveryUrl":"/Videos/1/m/Subtitles/2/0/Stream.srt?api_key=SECRET"},
          {"Type":"Subtitle","Index":3,"DeliveryMethod":"External","DeliveryUrl":"http://evil.example/s.srt"},
          {"Type":"Audio","Index":1}]}]}`
	response := pbSource(t, js)
	plan, err := Plan(pbServer(t), response, nil)
	if err != nil {
		t.Fatal(err)
	}
	if got := pbDeref(plan.Streams[0].DeliveryURL); got != "http://192.168.1.20:8096/emby/Videos/1/m/Subtitles/2/0/Stream.srt" {
		t.Fatal(got)
	}
	if plan.Streams[1].DeliveryURL != nil || plan.Streams[2].DeliveryURL != nil {
		t.Fatal("kept")
	}
	if *response.MediaSources[0].MediaStreams[0].DeliveryURL != "/Videos/1/m/Subtitles/2/0/Stream.srt?api_key=SECRET" {
		t.Fatal("response changed")
	}
	// Direct play: no subtitle files, and the off-server one does not fail the plan.
	if len(plan.Subtitles) != 0 || !slices.Equal(plan.Headers, []player.Header{{"X-A", "1"}, {"X-B", "2"}}) {
		t.Fatalf("%+v", plan)
	}
}

func Test_go_every_transcode_reason_has_its_label(t *testing.T) {
	for raw, want := range map[string]string{
		"/videos/1/master.m3u8":                                                     "raison non précisée",
		"/videos/1/master.m3u8?PlaySessionId=p":                                     "raison non précisée",
		"/videos/1/master.m3u8?transcodereasons=ContainerNotSupported":              "conteneur non pris en charge",
		"/videos/1/master.m3u8?TranscodeReasons=SubtitleCodecNotSupported":          "sous-titres non pris en charge",
		"/videos/1/master.m3u8?TranscodeReasons=AudioChannelsNotSupported":          "nombre de canaux audio non pris en charge",
		"/videos/1/master.m3u8?TranscodeReasons=DirectPlayError":                    "échec de la lecture directe",
		"/videos/1/master.m3u8?TranscodeReasons=Novel":                              "autre raison",
		"/videos/1/master.m3u8?TranscodeReasons=":                                   "autre raison",
		"/videos/1/master.m3u8?TranscodeReasons=%20VideoBitrateNotSupported+":       "débit trop élevé",
		"/videos/1/master.m3u8?TranscodeReasons=DirectPlayError#x":                  "autre raison",
		"/videos/1/master.m3u8?TranscodeReasons=A&TranscodeReasons=DirectPlayError": "autre raison, échec de la lecture directe",
	} {
		if got := transcodeReasons(raw); got != want {
			t.Errorf("%s: %q", raw, got)
		}
	}
}

// The query keeps its order and its other parameters, encoded as the url crate does.
func Test_go_stream_url_keeps_query_order_and_encoding(t *testing.T) {
	s := pbServer(t)
	for raw, want := range map[string]string{
		"/videos/1/m.m3u8?Z=1&api_key=t&A=2&a b=c+d&e=%zz&*-._~=é&&f": "/videos/1/m.m3u8?Z=1&A=2&a+b=c+d&e=%25zz&*-._%7E=%C3%A9&f=",
		"/videos/1/a.mkv?api_key=only":                                "/videos/1/a.mkv",
		"/videos/1/a.mkv?":                                            "/videos/1/a.mkv",
		"/videos/./1/a b.mkv\t":                                       "/videos/1/a%20b.mkv",
		"/VIDEOS/1/a.mkv#x?api_key=t":                                 "/VIDEOS/1/a.mkv",
		"/videos/1/a.mkv?apikey=1&ACCESSTOKEN=2&x-emby-token=3":       "/videos/1/a.mkv",
	} {
		got, err := StreamURL(s, raw)
		if err != nil || got != "http://192.168.1.20:8096/emby"+want {
			t.Errorf("%q: %q %v", raw, got, err)
		}
	}
	for _, raw := range []string{"/videos", "/video/1", "/videos/1/../../Users", "/"} {
		if _, err := StreamURL(s, raw); err == nil {
			t.Error(raw)
		}
	}
	// A server under a path keeps it, and its own /emby/videos prefix is required.
	sub, err := ParseServerURL("https://emby.test/media")
	if err != nil {
		t.Fatal(err)
	}
	if got, err := StreamURL(sub, "/videos/1/a.mkv"); err != nil || got != "https://emby.test/media/emby/videos/1/a.mkv" {
		t.Fatal(got, err)
	}
	if _, err := StreamURL(sub, "/../../emby/videos/1/a.mkv"); err == nil {
		t.Fatal("escaped the server path")
	}
}

func Test_go_required_headers_refuse_commas_and_unicode_controls(t *testing.T) {
	got := requiredHeaders(map[string]string{
		"X-Comma": "a,b", "X-C1": "a\u0085b", "X-Ünï": "v", "": "v", "X-Session": "ok", "x-authority": "v",
		"X-Plain": "é",
	})
	if !slices.Equal(got, []player.Header{{"X-Plain", "é"}, {"X-Session", "ok"}}) {
		t.Fatal(got)
	}
	if requiredHeaders(nil) != nil {
		t.Fatal("nil map")
	}
}

func Test_go_playback_start_and_engine_request_mirror_emby_play(t *testing.T) {
	plan := pbPlan(t, pbDirect, nil)
	start := NewPlaybackStart("30207", false, &plan)
	if !start.LocalAddress || start.BitrateCap != nil || start.ItemID != "30207" {
		t.Fatalf("%+v", start)
	}
	plan.BitrateLimit = &StreamingLimit{Bitrate: 4_000_000, Cap: u64(4_000_000), LocalAddress: false}
	start = NewPlaybackStart("30207", true, &plan)
	if start.LocalAddress || pbDeref(start.BitrateCap) != uint64(4_000_000) || !start.Resume {
		t.Fatalf("%+v", start)
	}
	req := EngineRequest(&plan, nil, player.SubtitleStyle{Size: player.SizeLarge})
	if req.StartSeconds != nil || req.SubtitleStyle.Size != player.SizeLarge || string(marshal(t, req.HTTPHeaders)) != "[]" {
		t.Fatalf("%+v", req)
	}
	plan.StartTicks = 15_000_000
	if s := pbDeref(EngineRequest(&plan, nil, player.SubtitleStyle{}).StartSeconds); s != 1.5 {
		t.Fatal(s)
	}
}

// ------------------------------------------------------------------- goldens

const pbPureServer = "https://emby.test"

// planProjection is plan_json of golden.rs: what the player and the reports read
// from a plan.
func planProjection(p *PlaybackPlan, headers []player.Header) map[string]any {
	var limit any
	if l := p.BitrateLimit; l != nil {
		limit = map[string]any{"bitrate": l.Bitrate, "cap": l.Cap, "localAddress": l.LocalAddress}
	}
	return map[string]any{
		"method":                     p.Method,
		"url":                        p.URL,
		"headers":                    player.List[player.Header](headers),
		"mediaSourceId":              p.MediaSourceID,
		"playSessionId":              p.PlaySessionID,
		"container":                  p.Container,
		"runTimeTicks":               p.RunTimeTicks,
		"startTicks":                 p.StartTicks,
		"transcodeReasons":           p.TranscodeReasons,
		"subtitles":                  player.List[player.ExternalSubtitle](p.Subtitles),
		"title":                      p.Title,
		"bitrateLimit":               limit,
		"defaultAudioStreamIndex":    p.DefaultAudioStreamIndex,
		"defaultSubtitleStreamIndex": p.DefaultSubtitleStreamIndex,
		"tracks":                     p.Tracks,
		"streamCount":                len(p.Streams),
	}
}

func Test_golden_playback_device_profile(t *testing.T) {
	assertTypedGolden(t, "playback.device_profile.json", DeviceProfileFor(MaxStreamingBitrate))
}

func Test_golden_playback_plan_fn(t *testing.T) {
	server, err := ParseServerURL(pbPureServer)
	if err != nil {
		t.Fatal(err)
	}
	tok := func(s string) string { return strings.ReplaceAll(s, "SECRET", "TOKEN_PLACEHOLDER") }
	direct, transcode, two := tok(pbDirect), tok(pbTranscode), tok(pbTwoVersions)
	rows := []struct {
		name, body string
		version    *string
	}{
		{"direct_play", direct, nil},
		{"direct_stream", strings.Replace(direct, `"SupportsDirectPlay":true`, `"SupportsDirectPlay":false`, 1), nil},
		{"transcode_hls", transcode, nil},
		{"transcode_reasons", strings.ReplaceAll(transcode, "ContainerBitrateExceedsLimit", "VideoCodecNotSupported,AudioCodecNotSupported"), nil},
		{"version_first", two, nil},
		{"version_second", two, ptr("30501")},
		{"version_unknown", two, ptr("30502")},
		{"subtitle_off_the_server", strings.Replace(transcode, "/Videos/30207/mediasource_30207/Subtitles/4/0/Stream.subrip", "http://evil.example/sub.srt", 1), nil},
		{"error_not_allowed", `{"ErrorCode":"NotAllowed"}`, nil},
		{"error_rate_limit", `{"ErrorCode":"RateLimitExceeded"}`, nil},
		{"error_no_compatible_stream", `{"ErrorCode":"NoCompatibleStream"}`, nil},
		{"no_media_sources", `{"MediaSources":[]}`, nil},
		{"no_url_for_direct_play", `{"MediaSources":[{"Id":"m","SupportsDirectPlay":true}]}`, nil},
	}
	for _, row := range rows {
		plan, err := Plan(server, pbSource(t, row.body), row.version)
		var got any
		if err != nil {
			got = map[string]any{"error": err}
		} else {
			got = map[string]any{"plan": planProjection(&plan, nil)}
		}
		assertTableGolden(t, "playback.plan_fn."+row.name+".json", got)
	}
}

func Test_golden_playback_stream_url(t *testing.T) {
	server, err := ParseServerURL(pbPureServer)
	if err != nil {
		t.Fatal(err)
	}
	var rows []map[string]any
	for _, raw := range []string{
		"/videos/30207/original.mkv?DeviceId=dev&api_key=x",
		"/Videos/1/stream.mkv?Static=true&ApiKey=x&X-Emby-Token=y&AccessToken=z#f",
		"http://evil.example/videos/1/original.mkv",
		"//evil.example/videos/1/original.mkv",
		"/Users/1/Items",
		"/videos/../Users/1",
		"/videos/%2e%2e/Users/1",
		"videos/1/original.mkv",
		`/videos\..\Users`,
	} {
		if url, err := StreamURL(server, raw); err != nil {
			rows = append(rows, map[string]any{"raw": raw, "error": err})
		} else {
			rows = append(rows, map[string]any{"raw": raw, "url": url})
		}
	}
	assertTableGolden(t, "playback.stream_url.json", rows)
}

// The browse_like() fake-server cases of golden.rs (`playback_cases`), through
// EmbyService.PrepareSource on signed_in_browser: the session fields (title,
// bitrateLimit, startTicks, the forced reason of a fallback, the X-Emby-Token header)
// come from prepareWith. The fake server address is written http://emby.test and the
// token TOKEN_PLACEHOLDER, as golden.rs does.
func Test_golden_playback_fake_server_plans(t *testing.T) {
	server, fx, _ := signedInBrowser(t)
	fix := goldenFix(t, server.URL)
	english := TrackPreferences{AudioLanguage: ptr("eng"), SubtitleLanguage: ptr("eng"), SubtitleMode: SubtitleAlways}
	off := TrackPreferences{SubtitleMode: SubtitleOff}
	cases := []struct {
		name, item     string
		resume, forced bool
		version        *string
		prefs          TrackPreferences
	}{
		{"direct_play", "30207", false, false, nil, TrackPreferences{}},
		{"direct_play_resume", "30207", true, false, nil, TrackPreferences{}},
		{"direct_play_subtitles_off", "30207", false, false, nil, off},
		{"episode_direct_play", "80475", false, false, nil, TrackPreferences{}},
		{"fallback_transcode", "30207", false, true, nil, TrackPreferences{}},
		{"transcode_english", "40001", false, false, nil, english},
		{"two_versions_second", "30500", false, false, ptr("30501"), TrackPreferences{}},
		{"transcode_no_title", "40000", false, false, nil, TrackPreferences{}},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			res, err := fx.service.PrepareSource(bg, c.item, c.version, c.resume, c.forced, c.prefs)
			if err != nil {
				t.Fatal(err)
			}
			plan, headers := res.Plan, res.Headers
			name := "playback.plan." + c.name + ".json"
			assertSameJSON(t, name, readGolden(t, name), fix(marshal(t, planProjection(&plan, headers))))
			name = "playback.emby_play." + c.name + ".json"
			if err := orderedEqual(readGolden(t, name), fix(marshal(t, NewPlaybackStart(c.item, c.resume, &plan)))); err != nil {
				t.Errorf("%s: %v", name, err)
			}
			name = "playback.engine_request." + c.name + ".json"
			req := fix(marshal(t, EngineRequest(&plan, headers, player.SubtitleStyle{})))
			if err := orderedEqual(readGolden(t, name), req); err != nil {
				t.Errorf("%s: %v\ngo: %s", name, err, req)
			}
		})
	}

	// Plans refused by the server's answer.
	refused := []struct {
		name string
		call func() error
	}{
		{"unknown_item", func() error { _, err := fx.service.PreparePlayback(bg, "99999", false); return err }},
		{"fallback_still_offers_the_file", func() error { _, err := fx.service.PrepareTranscode(bg, "80475", false); return err }},
		{"resume_without_item", func() error { _, err := fx.service.PreparePlayback(bg, "40000", true); return err }},
	}
	for _, c := range refused {
		goldenErr(t, fix, "errors.playback."+c.name+".json", c.call())
	}
}

func Test_golden_player_shapes(t *testing.T) {
	assertTypedGolden(t, "player.external_subtitle.json", player.ExternalSubtitle{
		Source: "https://emby.test/emby/Videos/1/s/Subtitles/3/0/Stream.subrip", Title: ptr("Forcés"), Lang: ptr("fre"), Selected: true,
	})
	assertTypedGolden(t, "player.player_diagnostics.empty.json", player.PlayerDiagnostics{})
	assertTypedGolden(t, "player.player_diagnostics.json", player.PlayerDiagnostics{
		MpvVersion: ptr("mpv 0.40.0"), FfmpegVersion: ptr("7.1"), VideoOutput: ptr("gpu-next"), GpuAPI: ptr("d3d11"),
		HwdecCurrent: ptr("d3d11va"), VideoCodec: ptr("hevc"), VideoWidth: ptr[int64](3840), VideoHeight: ptr[int64](2160),
		AudioCodec: ptr("ac3"), AudioOutput: ptr("wasapi"), Container: ptr("matroska,webm"), OsdWidth: ptr[int64](1920),
		OsdHeight: ptr[int64](1080), TimePos: ptr(12.5), Duration: ptr(6000.0), Paused: ptr(false), Volume: ptr(100.0),
		FrameDropCount: ptr[int64](0), DecoderFrameDropCount: ptr[int64](2), ChapterCount: ptr[int64](3),
		SubtitleOptions: player.List[[2]string]{{"sub-scale", "1.0"}},
	})
	assertTypedGolden(t, "player.player_tracks.json", player.List[player.Track]{
		{ID: 1, Kind: player.KindAudio, Title: ptr("Français 5.1"), Lang: ptr("fre"), Codec: ptr("ac3"), Selected: true, Default: true},
		{ID: 3, Kind: player.KindSub},
	})
}

func Test_go_plan_errors_are_emby_errors(t *testing.T) {
	_, err := Plan(pbServer(t), pbSource(t, `{}`), nil)
	if !errors.Is(err, ErrNoCompatibleStream) {
		t.Fatal(err)
	}
}

// The main URL is checked like the subtitle files; invalid UTF-8 after decoding a
// kept parameter becomes U+FFFD (research/stream-url.md).
func Test_go_plan_refuses_a_stream_url_off_the_server_and_repairs_utf8(t *testing.T) {
	js := `{"MediaSources":[{"Id":"m","SupportsDirectPlay":true,"DirectStreamUrl":"http://evil.example/videos/1/a.mkv"}]}`
	if _, err := Plan(pbServer(t), pbSource(t, js), nil); pbKind(t, err) != KindInvalidResponse {
		t.Fatal(err)
	}
	if got := formDecode("%FFa%E2%82"); got != "�a�" {
		t.Fatalf("%q", got)
	}
	got, err := StreamURL(pbServer(t), "/videos/1/a.mkv?x=%FF")
	if err != nil || got != "http://192.168.1.20:8096/emby/videos/1/a.mkv?x=%EF%BF%BD" {
		t.Fatal(got, err)
	}
	if rawQuery("  https://h/emby/videos/1?a=\t1#f\n ") != "a=1" || rawQuery("https://h/x") != "" {
		t.Fatal("rawQuery")
	}
}
