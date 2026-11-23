package main

import (
	"context"
	"encoding/json"
	"errors"
	"reflect"
	"strings"
	"testing"

	"viewer/internal/emby"
	"viewer/internal/player"
)

const (
	debridLink = "https://debrid.example/SECRETKEY/file.mkv"
	debridHdr  = "hdr-SECRET"
)

func stremioTitle() *emby.PlaybackTitle {
	season, index := uint32(2), uint32(5)
	series := "La Série"
	return &emby.PlaybackTitle{Name: "Pilote", SeriesName: &series, SeasonIndex: &season, Index: &index}
}

// stremio_play: no PlaybackInfo, no Begin, no Playing. A Shutdown to the reporter
// and the linked playback alone ends an Emby playback still going (reported as
// stopped, its lost stop resent and waited for) before the stream is loaded with
// the addon's URL and headers.
func Test_go_stremio_play_never_announces_an_emby_playback(t *testing.T) {
	rec := &recording{}
	headers := map[string]string{"User-Agent": "UA", "Referer": debridHdr}
	out, err := stremioPlay(context.Background(), rec, "80475", stremioTitle(), debridLink, headers)
	if err != nil {
		t.Fatal(err)
	}
	if got := strings.Join(rec.calls, ","); got != `engine,reporter {"type":"shutdown"},sync {"type":"shutdown"},resendStop,load` {
		t.Fatal(got)
	}
	want := player.PlaybackRequest{Source: debridLink, HTTPHeaders: player.List[player.Header]{{"Referer", debridHdr}, {"User-Agent", "UA"}}}
	if !reflect.DeepEqual(rec.request, want) {
		t.Fatalf("%+v", rec.request)
	}
	b, err := json.Marshal(out)
	if err != nil {
		t.Fatal(err)
	}
	const start = `{"itemId":"80475","mediaSourceId":"","resume":false,"method":"directPlay","container":null,` +
		`"transcodeReasons":null,"title":{"name":"Pilote","seriesName":"La Série","seasonIndex":2,"index":5,"indexEnd":null},` +
		`"bitrateCap":null,"localAddress":false,"origin":"stremio"}`
	if string(b) != start {
		t.Fatal(string(b))
	}
}

// A failure is the player error of emby_play, with a text of its own: never the
// link, whatever the engine said. Before the engine is there, the Emby playback
// still going is left alone.
func Test_go_stremio_play_failures_never_show_the_link(t *testing.T) {
	cases := []struct {
		name string
		rec  *recording
		url  string
		want string
	}{
		{"source", &recording{}, "C:\\Windows\\win.ini", ""},
		{"protocole", &recording{}, "ftp://debrid.example/SECRETKEY/file.mkv", ""},
		{"engine", &recording{engineErr: errors.New("libmpv " + debridLink)}, debridLink, "engine"},
		{"load", &recording{loadErr: errors.New("mpv " + debridLink + " " + debridHdr)}, debridLink,
			`engine,reporter {"type":"shutdown"},sync {"type":"shutdown"},resendStop,load`},
	}
	for _, c := range cases {
		_, err := stremioPlay(context.Background(), c.rec, "30207", nil, c.url, map[string]string{"X": debridHdr})
		if got := strings.Join(c.rec.calls, ","); got != c.want {
			t.Errorf("%s : %s", c.name, got)
		}
		if k, _ := emby.KindOf(err); k != emby.KindPlayer {
			t.Errorf("%s : %v", c.name, err)
		}
		b, _ := json.Marshal(err)
		for _, s := range []string{"SECRETKEY", "debrid.example", debridHdr, "win.ini"} {
			if strings.Contains(string(b), s) || strings.Contains(err.Error(), s) {
				t.Errorf("%s : %s", c.name, b)
			}
		}
	}
}

// stremio_play exists in release builds (the URL is Go's); player_open does not.
// An unknown stream is the `expired` of the list.
func Test_go_stremio_play_is_wired_in_release_builds(t *testing.T) {
	_, b, _, _ := testApp(t, false)
	if _, err := invoke(t, b, "stremio_play", `{"streamId":"stream_inconnu"}`); err != `{"kind":"expired","message":"La liste a expiré, rouvre-la."}` {
		t.Fatal(err)
	}
	if _, err := invoke(t, b, "stremio_play", `{"streamId":"stream_inconnu","source":"https://x.example/a.mkv"}`); !strings.Contains(err, "invalid args for command `stremio_play`") {
		t.Fatal(err)
	}
	if _, err := invoke(t, b, "player_open", `{"request":{"source":"https://x.example/a.mkv"}}`); err != `"source invalide : prototype indisponible dans cette version"` {
		t.Fatal(err)
	}
	// Signed out of Stremio: the detail page's text, before Emby is read.
	if _, err := invoke(t, b, "stremio_sources", `{"itemId":"30207"}`); err != `{"kind":"signedOut","message":"Connecte ton compte Stremio dans les Réglages."}` {
		t.Fatal(err)
	}
	if _, err := invoke(t, b, "stremio_addon_streams", `{"query":"query_x","addonId":"addon_x"}`); err != `{"kind":"expired","message":"La liste a expiré, rouvre-la."}` {
		t.Fatal(err)
	}
}
