package emby

import (
	"encoding/json"
	"reflect"
	"slices"
	"testing"

	"viewer/internal/player"
)

func trJSON(t *testing.T, js string) any {
	t.Helper()
	var v any
	if err := json.Unmarshal([]byte(js), &v); err != nil {
		t.Fatal(err)
	}
	return v
}

// trRaw is a Configuration as the client hands it over (json.RawMessage).
func trRaw(t *testing.T, v any) json.RawMessage {
	t.Helper()
	if v == nil {
		return nil
	}
	return marshal(t, v)
}

const trAccountObject = `{
        "AudioLanguagePreference": "fre, ENG,fre,not a code",
        "PlayDefaultAudioTrack": true,
        "SubtitleLanguagePreference": "fre",
        "SubtitleMode": "onlyforced",
        "ProfilePin": "1234"
    }`

// emby/tracks.rs account_preferences_are_read_from_an_object_or_an_array
func Test_account_preferences_are_read_from_an_object_or_an_array(t *testing.T) {
	read := AccountTracksOf(json.RawMessage(trAccountObject))
	want := AccountTracks{
		AudioLanguages:    List[string]{"fre", "eng"},
		PlayDefaultAudio:  true,
		SubtitleLanguages: List[string]{"fre"},
		SubtitleMode:      ptr("OnlyForced"),
	}
	if !reflect.DeepEqual(read, want) {
		t.Fatalf("%+v", read)
	}
	if got := AccountTracksOf(json.RawMessage("[" + trAccountObject + "]")); !reflect.DeepEqual(got, read) {
		t.Fatalf("%+v", got)
	}
}

// emby/tracks.rs missing_or_unknown_account_preferences_ask_for_nothing
func Test_missing_or_unknown_account_preferences_ask_for_nothing(t *testing.T) {
	for _, config := range []json.RawMessage{nil, json.RawMessage(`[]`), json.RawMessage(`{"SubtitleMode": "Sometimes", "AudioLanguagePreference": ""}`)} {
		if got := AccountTracksOf(config); !reflect.DeepEqual(got, AccountTracks{}) {
			t.Errorf("%v: %+v", config, got)
		}
	}
}

func trStreams(t *testing.T, js string) []MediaStreamDto {
	t.Helper()
	var s []MediaStreamDto
	if err := json.Unmarshal([]byte(js), &s); err != nil {
		t.Fatal(err)
	}
	return s
}

// Streams of a real film of the test server (Emby 4.10.1.0, PlaybackInfo): French and
// English audio, forced and full French subtitles, English ones, and PGS copies.
const trFilm = `[{"Type":"Video","Index":0,"Codec":"hevc"},
            {"Type":"Audio","Index":1,"Codec":"ac3","Language":"fre","IsDefault":true},
            {"Type":"Audio","Index":2,"Codec":"ac3","Language":"eng"},
            {"Type":"Subtitle","Index":3,"Codec":"subrip","Language":"fre","IsForced":true},
            {"Type":"Subtitle","Index":4,"Codec":"subrip","Language":"fre","IsForced":true,"IsDefault":true},
            {"Type":"Subtitle","Index":5,"Codec":"subrip","Language":"fre"},
            {"Type":"Subtitle","Index":6,"Codec":"subrip","Language":"eng"},
            {"Type":"Subtitle","Index":7,"Codec":"PGSSUB","Language":"fra"},
            {"Type":"Subtitle","Index":8,"Codec":"PGSSUB","Language":"en"},
            {"Type":"Subtitle","Index":9,"Codec":"subrip","Language":"spa","IsExternal":true}]`

const trTranscode = `[{"Type":"Audio","Index":1,"Language":"fre","IsDefault":true},
            {"Type":"Audio","Index":2,"Language":"eng"},
            {"Type":"Subtitle","Index":3,"Codec":"subrip","Language":"fre","DeliveryMethod":"External","DeliveryUrl":"/Videos/1/s/Subtitles/3/0/Stream.subrip"},
            {"Type":"Subtitle","Index":4,"Codec":"PGSSUB","Language":"eng","DeliveryMethod":"Encode"}]`

func trPrefs(audio, subtitle *string, mode SubtitleMode) TrackPreferences {
	return TrackPreferences{AudioLanguage: audio, SubtitleLanguage: subtitle, SubtitleMode: mode}
}

func trDirect(t *testing.T, p TrackPreferences) Choice {
	return Choose(trStreams(t, trFilm), MethodDirectPlay, ptr[int32](1), ptr[int32](4), &p)
}

func trAudio(c Choice) any { return pbDeref(c.Audio) }

var trOff = SubtitleChoice{Kind: SubtitleNone}

// emby/tracks.rs without_preferences_the_server_picks_stand
func Test_without_preferences_the_server_picks_stand(t *testing.T) {
	c := trDirect(t, TrackPreferences{})
	if trAudio(c) != int32(1) || c.Subtitle != subtitleStream(4) {
		t.Fatalf("%+v", c)
	}
	// No subtitle named by the server: the player decides, as before ADR 0035.
	c = Choose(trStreams(t, trFilm), MethodDirectPlay, ptr[int32](1), nil, &TrackPreferences{})
	if c.Subtitle != (SubtitleChoice{Kind: SubtitlePlayer}) {
		t.Fatalf("%+v", c)
	}
}

// emby/tracks.rs audio_language_wins_over_the_server_and_falls_back_to_it
func Test_audio_language_wins_over_the_server_and_falls_back_to_it(t *testing.T) {
	if a := trAudio(trDirect(t, trPrefs(ptr("eng"), nil, SubtitleServer))); a != int32(2) {
		t.Fatal(a)
	}
	// No Japanese track: the server's audio stays.
	if a := trAudio(trDirect(t, trPrefs(ptr("jpn"), nil, SubtitleServer))); a != int32(1) {
		t.Fatal(a)
	}
}

// emby/tracks.rs subtitle_modes
func Test_subtitle_modes(t *testing.T) {
	cases := []struct {
		why   string
		prefs TrackPreferences
		want  SubtitleChoice
	}{
		{"off", trPrefs(nil, nil, SubtitleOff), trOff},
		// Forced only: in the audio's language when no subtitle language is set, the
		// file's default among them.
		{"forced in the audio's language", trPrefs(nil, nil, SubtitleForcedOnly), subtitleStream(4)},
		// English audio and forced only: no English forced track, so none.
		{"no English forced", trPrefs(ptr("eng"), nil, SubtitleForcedOnly), trOff},
		// Always in French: the full text track, not the forced ones nor the PGS.
		{"always French", trPrefs(nil, ptr("fre"), SubtitleAlways), subtitleStream(5)},
		// `en` in the file matches English; text before the PGS copy.
		{"always English", trPrefs(ptr("eng"), ptr("eng"), SubtitleAlways), subtitleStream(6)},
		// The server's external Spanish file is not loaded when playing the file.
		{"external Spanish", trPrefs(nil, ptr("spa"), SubtitleAlways), trOff},
		// Always without a language: the server's pick is forced, so the first full one.
		{"always, no language", trPrefs(nil, nil, SubtitleAlways), subtitleStream(5)},
	}
	for _, c := range cases {
		if got := trDirect(t, c.prefs).Subtitle; got != c.want {
			t.Errorf("%s: %+v", c.why, got)
		}
	}
}

// emby/tracks.rs always_falls_back_to_forced_subtitles_in_the_language
func Test_always_falls_back_to_forced_subtitles_in_the_language(t *testing.T) {
	onlyForced := slices.DeleteFunc(trStreams(t, trFilm), func(s MediaStreamDto) bool {
		return *s.Index == 5 || *s.Index == 7
	})
	p := trPrefs(nil, ptr("fre"), SubtitleAlways)
	if c := Choose(onlyForced, MethodDirectPlay, ptr[int32](1), nil, &p); c.Subtitle != subtitleStream(4) {
		t.Fatalf("%+v", c)
	}
}

// emby/tracks.rs a_transcode_only_shows_subtitles_delivered_as_files
func Test_a_transcode_only_shows_subtitles_delivered_as_files(t *testing.T) {
	streams := trStreams(t, trTranscode)
	p := trPrefs(ptr("eng"), ptr("eng"), SubtitleAlways)
	c := Choose(streams, MethodTranscode, ptr[int32](1), ptr[int32](3), &p)
	if trAudio(c) != int32(2) {
		t.Fatal(c.Audio)
	}
	if c.Subtitle != trOff {
		t.Fatal("PGS would be burnt in", c.Subtitle)
	}
	// The server's pick that cannot be shown is left to the player, not burnt in.
	c = Choose(streams, MethodTranscode, ptr[int32](1), ptr[int32](4), &TrackPreferences{})
	if c.Subtitle != (SubtitleChoice{Kind: SubtitlePlayer}) {
		t.Fatal(c.Subtitle)
	}
}

// emby/tracks.rs player_selection_by_method
func Test_player_selection_by_method(t *testing.T) {
	c := Choice{Audio: ptr[int32](2), Subtitle: subtitleStream(5)}
	if got := c.Selection(MethodDirectPlay); got != (player.TrackSelection{Audio: player.PickStream(2), Subtitle: player.PickStream(5)}) {
		t.Fatalf("%+v", got)
	}
	if got := c.Selection(MethodTranscode); got != (player.TrackSelection{}) {
		t.Fatalf("%+v", got)
	}
	off := Choice{Subtitle: trOff}
	if got := off.Selection(MethodTranscode).Subtitle; got != (player.TrackPick{Kind: player.TrackOff}) {
		t.Fatalf("%+v", got)
	}
	p := Choice{Subtitle: SubtitleChoice{Kind: SubtitlePlayer}}
	if got := p.Selection(MethodDirectStream); got != (player.TrackSelection{}) {
		t.Fatalf("%+v", got)
	}
}

func trDecode(js string) (TrackPreferences, error) {
	var p TrackPreferences
	err := json.Unmarshal([]byte(js), &p)
	return p, err
}

// emby/tracks.rs unknown_languages_are_refused
func Test_unknown_languages_are_refused(t *testing.T) {
	if err := trPrefs(ptr("fre"), ptr("eng"), SubtitleAlways).Validate(); err != nil {
		t.Fatal(err)
	}
	if trPrefs(ptr("fra"), nil, SubtitleServer).Validate() == nil {
		t.Fatal("fra accepted")
	}
	if trPrefs(nil, ptr("xx\n"), SubtitleServer).Validate() == nil {
		t.Fatal("xx accepted")
	}
	parsed, err := trDecode(`{"subtitleMode":"forcedOnly","audioLanguage":"eng"}`)
	if err != nil || !reflect.DeepEqual(parsed, trPrefs(ptr("eng"), nil, SubtitleForcedOnly)) {
		t.Fatal(parsed, err)
	}
	if _, err := trDecode(`{"subtitleMode":"smart"}`); err == nil {
		t.Fatal("smart accepted")
	}
}

// ------------------------------------------- Go additions: branches Rust leaves untested

// Keys are exact and null is refused for the mode, as serde.
func Test_go_track_preferences_decode_like_serde(t *testing.T) {
	for js, want := range map[string]TrackPreferences{
		`{}`: {},
		`{"audioLanguage":null,"subtitleLanguage":"fre","other":1}`: trPrefs(nil, ptr("fre"), SubtitleServer),
		`{"AudioLanguage":"eng","subtitleMode":"off"}`:              trPrefs(nil, nil, SubtitleOff),
		`{"subtitleMode":"always"}`:                                 trPrefs(nil, nil, SubtitleAlways),
	} {
		got, err := trDecode(js)
		if err != nil || !reflect.DeepEqual(got, want) {
			t.Errorf("%s: %+v %v", js, got, err)
		}
	}
	for _, js := range []string{`null`, `[]`, `{"subtitleMode":null}`, `{"subtitleMode":"Off"}`, `{"audioLanguage":1}`} {
		if _, err := trDecode(js); err == nil {
			t.Error(js)
		}
	}
}

// The audio choice: the server's pick when it speaks the language, else the file's
// default, else the first; external or unnumbered audio never counts.
func Test_go_audio_choice_order(t *testing.T) {
	streams := trStreams(t, `[
        {"Type":"Audio","Index":1,"Language":"fre"},
        {"Type":"Audio","Index":2,"Language":"EN"},
        {"Type":"Audio","Index":3,"Language":"eng","IsDefault":true},
        {"Type":"Audio","Index":4,"Language":"eng"},
        {"Type":"Audio","Index":5,"Language":"jpn","IsExternal":true},
        {"Type":"Audio","Language":"jpn"}]`)
	eng := trPrefs(ptr("eng"), nil, SubtitleServer)
	for _, c := range []struct {
		server *int32
		prefs  TrackPreferences
		want   any
	}{
		{ptr[int32](4), eng, int32(4)}, // server speaks it
		{ptr[int32](1), eng, int32(3)}, // file's default
		{nil, eng, int32(3)},           // no server pick
		{ptr[int32](1), trPrefs(ptr("jpn"), nil, SubtitleServer), int32(1)}, // external only: server stays
		{ptr[int32](5), TrackPreferences{}, nil},                            // external server pick ignored
		{ptr[int32](9), TrackPreferences{}, nil},                            // unknown server pick
		{ptr[int32](1), trPrefs(ptr("xx"), nil, SubtitleServer), int32(1)},  // language not offered
	} {
		if got := trAudio(Choose(streams, MethodDirectPlay, c.server, nil, &c.prefs)); got != c.want {
			t.Errorf("server %v prefs %+v: %v", pbDeref(c.server), c.prefs, got)
		}
	}
	noDefault := trStreams(t, `[{"Type":"Audio","Index":7,"Language":"eng"},{"Type":"Audio","Index":6,"Language":"eng"}]`)
	if got := trAudio(Choose(noDefault, MethodDirectPlay, nil, nil, &eng)); got != int32(7) {
		t.Fatal("first in file order", got)
	}
}

// Ranking of subtitles: text, then default flag, then index; the first of equal keys.
func Test_go_subtitle_ranking_and_forced_language_sources(t *testing.T) {
	streams := trStreams(t, `[
        {"Type":"Audio","Index":1,"Language":"ger"},
        {"Type":"Subtitle","Index":9,"Codec":"PGSSUB","Language":"de","IsForced":true,"IsDefault":true},
        {"Type":"Subtitle","Index":8,"Codec":"SRT","Language":"deu","IsForced":true},
        {"Type":"Subtitle","Index":7,"Codec":"ass","Language":"deu","IsForced":true},
        {"Type":"Subtitle","Index":6,"Codec":"webvtt","Language":"ita"},
        {"Type":"Subtitle","Codec":"subrip","Language":"ita"}]`)
	forced := TrackPreferences{SubtitleMode: SubtitleForcedOnly}
	// German from the audio; text before the PGS default, then the lower index.
	if c := Choose(streams, MethodDirectPlay, ptr[int32](1), nil, &forced); c.Subtitle != subtitleStream(7) {
		t.Fatal(c.Subtitle)
	}
	// No audio and no subtitle language: nothing forced to look for.
	if c := Choose(streams, MethodDirectPlay, nil, nil, &forced); c.Subtitle != trOff {
		t.Fatal(c.Subtitle)
	}
	// Subtitle language wins over the audio's.
	ita := trPrefs(nil, ptr("ita"), SubtitleAlways)
	if c := Choose(streams, MethodDirectPlay, ptr[int32](1), nil, &ita); c.Subtitle != subtitleStream(6) {
		t.Fatal(c.Subtitle)
	}
	// Always without language: a full server pick stands.
	always := TrackPreferences{SubtitleMode: SubtitleAlways}
	if c := Choose(streams, MethodDirectPlay, nil, ptr[int32](6), &always); c.Subtitle != subtitleStream(6) {
		t.Fatal(c.Subtitle)
	}
	// Server mode: the server's pick must be a candidate of this playback.
	if c := Choose(streams, MethodDirectPlay, nil, ptr[int32](42), &TrackPreferences{}); c.Subtitle != (SubtitleChoice{Kind: SubtitlePlayer}) {
		t.Fatal(c.Subtitle)
	}
	// A transcode without subtitle files has no candidate.
	if c := Choose(streams, MethodTranscode, nil, ptr[int32](6), &always); c.Subtitle != trOff {
		t.Fatal(c.Subtitle)
	}
}

// Apply marks the subtitle file chosen on a transcode, and only there.
func Test_go_apply_marks_the_chosen_subtitle_file(t *testing.T) {
	plan := pbPlan(t, pbTranscode, nil) // server pick 4 (eng), files 3 and 4
	fre := trPrefs(nil, ptr("fre"), SubtitleAlways)
	c := Apply(&plan, &fre)
	if c.Subtitle != subtitleStream(3) || !plan.Subtitles[0].Selected || plan.Subtitles[1].Selected {
		t.Fatalf("%+v %+v", c, plan.Subtitles)
	}
	if plan.Tracks != (player.TrackSelection{}) {
		t.Fatalf("%+v", plan.Tracks)
	}
	off := TrackPreferences{SubtitleMode: SubtitleOff}
	Apply(&plan, &off)
	if plan.Subtitles[0].Selected || plan.Subtitles[1].Selected || plan.Tracks.Subtitle.Kind != player.TrackOff {
		t.Fatalf("%+v %+v", plan.Subtitles, plan.Tracks)
	}
	// Fewer files than streams: zipped, nothing out of range.
	plan.Subtitles = plan.Subtitles[:1]
	Apply(&plan, &TrackPreferences{})
	if plan.Subtitles[0].Selected {
		t.Fatal("server pick is the second file")
	}

	direct := pbPlan(t, pbDirect, nil)
	direct.Streams = trStreams(t, trFilm)
	direct.DefaultSubtitleStreamIndex = ptr[int32](4)
	Apply(&direct, &TrackPreferences{})
	if direct.Tracks != (player.TrackSelection{Audio: player.PickStream(1), Subtitle: player.PickStream(4)}) {
		t.Fatalf("%+v", direct.Tracks)
	}
}

// Account languages: 2 to 8 ASCII letters or hyphens, lowercased, once each; the
// mode matched without ASCII case only (not the Unicode folding of EqualFold).
func Test_go_account_languages_and_mode_matching(t *testing.T) {
	got := AccountTracksOf(trRaw(t, map[string]any{
		"AudioLanguagePreference":    "pt-BR,e,abcdefghi,zh_CN,é,  JPN ,jpn",
		"SubtitleLanguagePreference": 3.0,
		"PlayDefaultAudioTrack":      "true",
		"SubtitleMode":               " ſmart ",
	}))
	want := AccountTracks{AudioLanguages: List[string]{"pt-br", "jpn"}}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("%+v", got)
	}
	if m := AccountTracksOf(trRaw(t, map[string]any{"SubtitleMode": " hearingimpaired "})).SubtitleMode; pbDeref(m) != "HearingImpaired" {
		t.Fatal(m)
	}
	// Exact keys: another case is not read; a non-object is nothing.
	if got := AccountTracksOf(json.RawMessage(`{"subtitlemode":"Always"}`)); got.SubtitleMode != nil {
		t.Fatal(got)
	}
	if got := AccountTracksOf(json.RawMessage(`["x",{"SubtitleMode":"Always"}]`)); !reflect.DeepEqual(got, AccountTracks{}) {
		t.Fatal(got)
	}
	for _, raw := range []string{`null`, `not json`, `"Always"`} {
		if got := AccountTracksOf(json.RawMessage(raw)); !reflect.DeepEqual(got, AccountTracks{}) {
			t.Error(raw, got)
		}
	}
	if string(marshal(t, AccountTracks{})) != `{"audioLanguages":[],"playDefaultAudio":false,"subtitleLanguages":[],"subtitleMode":null}` {
		t.Fatal(string(marshal(t, AccountTracks{})))
	}
}

// ------------------------------------------------------------------- goldens

func Test_golden_tracks_choose(t *testing.T) {
	film, transcode := trStreams(t, trFilm), trStreams(t, trTranscode)
	type row struct {
		label  string
		method string
		audio  *int32
		sub    *int32
		prefs  string
	}
	rows := []row{
		{"film", "DirectPlay", ptr[int32](1), ptr[int32](4), `{}`},
		{"film", "DirectPlay", ptr[int32](1), nil, `{}`},
		{"film", "DirectPlay", ptr[int32](1), ptr[int32](4), `{"audioLanguage":"eng"}`},
		{"film", "DirectPlay", ptr[int32](1), ptr[int32](4), `{"audioLanguage":"jpn"}`},
		{"film", "DirectPlay", ptr[int32](1), ptr[int32](4), `{"subtitleMode":"off"}`},
		{"film", "DirectPlay", ptr[int32](1), ptr[int32](4), `{"subtitleMode":"forcedOnly"}`},
		{"film", "DirectPlay", ptr[int32](1), ptr[int32](4), `{"audioLanguage":"eng","subtitleMode":"forcedOnly"}`},
		{"film", "DirectPlay", ptr[int32](1), ptr[int32](4), `{"subtitleLanguage":"fre","subtitleMode":"always"}`},
		{"film", "DirectPlay", ptr[int32](1), ptr[int32](4), `{"audioLanguage":"eng","subtitleLanguage":"eng","subtitleMode":"always"}`},
		{"film", "DirectPlay", ptr[int32](1), ptr[int32](4), `{"subtitleLanguage":"spa","subtitleMode":"always"}`},
		{"film", "DirectPlay", ptr[int32](1), ptr[int32](4), `{"subtitleMode":"always"}`},
		{"film", "DirectStream", ptr[int32](1), ptr[int32](4), `{}`},
		{"transcode", "Transcode", ptr[int32](1), ptr[int32](3), `{"audioLanguage":"eng","subtitleLanguage":"eng","subtitleMode":"always"}`},
		{"transcode", "Transcode", ptr[int32](1), ptr[int32](4), `{}`},
		{"transcode", "Transcode", ptr[int32](1), ptr[int32](3), `{}`},
	}
	methods := map[string]PlayMethod{"DirectPlay": MethodDirectPlay, "DirectStream": MethodDirectStream, "Transcode": MethodTranscode}
	var out []map[string]any
	for _, r := range rows {
		streams := film
		if r.label == "transcode" {
			streams = transcode
		}
		prefs, err := trDecode(r.prefs)
		if err != nil {
			t.Fatal(err)
		}
		c := Choose(streams, methods[r.method], r.audio, r.sub, &prefs)
		var sub any
		switch c.Subtitle.Kind {
		case SubtitlePlayer:
			sub = "player"
		case SubtitleNone:
			sub = "off"
		default:
			sub = map[string]any{"stream": c.Subtitle.Index}
		}
		out = append(out, map[string]any{
			"streams": r.label, "method": r.method, "serverAudio": r.audio, "serverSubtitle": r.sub,
			"preferences": json.RawMessage(r.prefs), "audio": c.Audio, "subtitle": sub,
			"selection": c.Selection(methods[r.method]),
		})
	}
	assertTableGolden(t, "tracks.choose.json", out)
}

func Test_golden_tracks_preferences(t *testing.T) {
	var out []map[string]any
	for _, input := range []string{
		`{"audioLanguage":"fre","subtitleLanguage":"eng","subtitleMode":"always"}`,
		`{"audioLanguage":"fra"}`,
		`{"subtitleLanguage":"xx\n"}`,
		`{"subtitleMode":"forcedOnly","audioLanguage":"eng"}`,
		`{"subtitleMode":"smart"}`,
	} {
		row := map[string]any{"input": json.RawMessage(input)}
		p, err := trDecode(input)
		switch {
		case err != nil:
			row["decode"] = "refused"
		case p.Validate() != nil:
			row["decode"], row["validate"] = "ok", p.Validate()
		default:
			row["decode"], row["validate"] = "ok", "ok"
		}
		out = append(out, row)
	}
	assertTableGolden(t, "tracks.preferences.json", out)
}

func Test_golden_tracks_account_tracks(t *testing.T) {
	object := trJSON(t, trAccountObject)
	withoutPin := trJSON(t, trAccountObject)
	delete(withoutPin.(map[string]any), "ProfilePin")
	var out []map[string]any
	for _, c := range []struct {
		name          string
		config, shown any
	}{
		{"object", object, withoutPin},
		{"array", []any{object}, []any{withoutPin}},
		{"missing", nil, nil},
		{"empty_array", []any{}, []any{}},
		{"unknown_mode", trJSON(t, `{"SubtitleMode": "Sometimes", "AudioLanguagePreference": ""}`), trJSON(t, `{"SubtitleMode": "Sometimes", "AudioLanguagePreference": ""}`)},
	} {
		out = append(out, map[string]any{"case": c.name, "configuration": c.shown, "accountTracks": AccountTracksOf(trRaw(t, c.config))})
	}
	assertTableGolden(t, "tracks.account_tracks.json", out)
}

// Small helpers: Option equality, a stream without language, ranking ties.
func Test_go_track_helpers_edge_cases(t *testing.T) {
	if !sameIndex(nil, nil) || sameIndex(ptr[int32](1), nil) || sameIndex(nil, ptr[int32](1)) || !sameIndex(ptr[int32](2), ptr[int32](2)) {
		t.Fatal("sameIndex")
	}
	if streamLanguage(&MediaStreamDto{}) != nil || streamLanguage(&MediaStreamDto{Language: ptr("und")}) != nil {
		t.Fatal("streamLanguage")
	}
	a := MediaStreamDto{Index: ptr[int32](3), Codec: ptr("srt")}
	b := MediaStreamDto{Index: ptr[int32](2), Codec: ptr("srt")}
	if subtitleRank(&a, &b) != 1 || subtitleRank(&b, &a) != -1 || subtitleRank(&a, &a) != 0 {
		t.Fatal("subtitleRank")
	}
	// Equal keys: the first in file order wins, as min_by_key.
	streams := trStreams(t, `[{"Type":"Subtitle","Index":4,"Codec":"srt","Language":"fre"},{"Type":"Subtitle","Index":4,"Codec":"srt","Language":"fre","Title":"second"}]`)
	p := trPrefs(nil, ptr("fre"), SubtitleAlways)
	if c := Choose(streams, MethodDirectPlay, nil, nil, &p); c.Subtitle != subtitleStream(4) {
		t.Fatal(c.Subtitle)
	}
	if SubtitleForcedOnly.String() != "forcedOnly" || SubtitleMode(9).String() != "SubtitleMode(9)" {
		t.Fatal("String")
	}
}
