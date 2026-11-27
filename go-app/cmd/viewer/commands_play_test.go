package main

import (
	"context"
	"encoding/json"
	"errors"
	"strconv"
	"strings"
	"testing"

	"viewer/internal/emby"
	"viewer/internal/player"
	"viewer/internal/stremio"
)

// recording is a playback that writes down each call, in order.
type recording struct {
	calls     []string
	url       string
	engineErr error
	loadErr   error
	// request is the last one loaded.
	request player.PlaybackRequest
	// saved are the Stremio positions, by target.
	saved map[string]stremio.ResumePoint
}

func (r *recording) add(c string) { r.calls = append(r.calls, c) }

func (r *recording) plan() emby.PrepareResult {
	return emby.PrepareResult{Plan: emby.PlaybackPlan{Method: emby.MethodDirectPlay, URL: r.url, MediaSourceID: "src1"}}
}
func (r *recording) ResendStop(context.Context) { r.add("resendStop") }
func (r *recording) PrepareSource(context.Context, string, *string, bool, bool, emby.TrackPreferences) (emby.PrepareResult, error) {
	r.add("prepare")
	return r.plan(), nil
}
func (r *recording) PrepareAtCap(context.Context, string, string, *uint64, int64, emby.TrackPreferences) (emby.PrepareResult, error) {
	r.add("prepareAtCap")
	return r.plan(), nil
}
func (r *recording) Begin(emby.ActivePlayback)  { r.add("begin") }
func (r *recording) Switch(emby.ActivePlayback) { r.add("switch") }
func (r *recording) ReportEvent(ev player.PlayerEvent) {
	b, _ := json.Marshal(ev)
	r.add("reporter " + string(b))
}
func (r *recording) SyncEvent(ev player.PlayerEvent) {
	b, _ := json.Marshal(ev)
	r.add("sync " + string(b))
}
func (r *recording) Playing(_, src string, direct bool) {
	if direct {
		r.add("playing " + src + " direct")
	} else {
		r.add("playing " + src)
	}
}
func (r *recording) EndStremio() { r.add("endStremio") }
func (r *recording) SavedStremio(target string) (stremio.ResumePoint, bool) {
	p, ok := r.saved[target]
	return p, ok
}
func (r *recording) TrackStremio(target string, start *float64) {
	from := "-"
	if start != nil {
		from = strconv.FormatFloat(*start, 'f', -1, 64)
	}
	r.add("track " + target + " " + from)
}
func (r *recording) Engine() (loader, error) {
	r.add("engine")
	if r.engineErr != nil {
		return nil, r.engineErr
	}
	return r, nil
}
func (r *recording) Load(request *player.PlaybackRequest, _ player.MediaSource) error {
	r.add("load")
	r.request = *request
	return r.loadErr
}

func playArgs(t *testing.T, cmd, raw string) tauriArgs {
	t.Helper()
	a, err := readArgs(cmd, json.RawMessage(raw))
	if err != nil {
		t.Fatal(err)
	}
	return a
}

const stream = "http://emby.test/Videos/1/stream.mkv"

// emby_play: resend_stop before prepare, begin and the sync after; any failure
// once announced sends Shutdown to the reporter alone (commands.rs:671).
func Test_go_emby_play_keeps_the_order_of_commands_rs(t *testing.T) {
	args := `{"itemId":"i1","resume":true}`
	cases := []struct {
		name string
		rec  *recording
		want string
	}{
		{"ok", &recording{url: stream}, "resendStop,prepare,begin,playing src1 direct,engine,endStremio,load"},
		{"source", &recording{url: "ftp://x"}, `resendStop,prepare,begin,playing src1 direct,reporter {"type":"shutdown"}`},
		{"engine", &recording{url: stream, engineErr: errors.New("libmpv")}, `resendStop,prepare,begin,playing src1 direct,engine,reporter {"type":"shutdown"}`},
		{"load", &recording{url: stream, loadErr: errors.New("mpv")}, `resendStop,prepare,begin,playing src1 direct,engine,endStremio,load,reporter {"type":"shutdown"}`},
	}
	for _, c := range cases {
		out, err := embyPlay(context.Background(), c.rec, playArgs(t, "emby_play", args))
		if got := strings.Join(c.rec.calls, ","); got != c.want {
			t.Errorf("%s : %s", c.name, got)
		}
		if c.name == "ok" {
			if start, ok := out.(emby.PlaybackStart); err != nil || !ok || start.ItemID != "i1" || !start.Resume {
				t.Errorf("ok : %+v %v", out, err)
			}
		} else if k, _ := emby.KindOf(err); k != emby.KindPlayer {
			t.Errorf("%s : %v", c.name, err)
		}
	}
}

// emby_change_quality: switch after the source and the engine, before load; no
// Shutdown on failure (the stream playing goes on).
func Test_go_change_quality_switches_before_load(t *testing.T) {
	args := `{"itemId":"i1","mediaSourceId":"src1","cap":null,"positionSeconds":12.5}`
	cases := []struct {
		name string
		rec  *recording
		want string
	}{
		{"ok", &recording{url: stream}, "prepareAtCap,engine,switch,endStremio,load"},
		{"source", &recording{url: "ftp://x"}, "prepareAtCap"},
		{"engine", &recording{url: stream, engineErr: errors.New("libmpv")}, "prepareAtCap,engine"},
		{"load", &recording{url: stream, loadErr: errors.New("mpv")}, "prepareAtCap,engine,switch,endStremio,load"},
	}
	for _, c := range cases {
		out, err := embyChangeQuality(context.Background(), c.rec, playArgs(t, "emby_change_quality", args))
		if got := strings.Join(c.rec.calls, ","); got != c.want {
			t.Errorf("%s : %s", c.name, got)
		}
		if c.name == "ok" {
			if start, ok := out.(emby.PlaybackStart); err != nil || !ok || !start.Resume {
				t.Errorf("ok : %+v %v", out, err)
			}
		} else if k, _ := emby.KindOf(err); k != emby.KindPlayer {
			t.Errorf("%s : %v", c.name, err)
		}
	}
}
