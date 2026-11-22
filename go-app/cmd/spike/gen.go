package main

import (
	"fmt"
	"os"
	"path/filepath"
	"time"

	"viewer/internal/mpv"
)

// spikeASS is a hand-written ASS file: one cue for the whole clip, pinned to
// the top (\an8) so the HTML overlay bar at the bottom cannot hide it.
const spikeASS = `[Script Info]
ScriptType: v4.00+
PlayResX: 1280
PlayResY: 720

[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Default,Arial,56,&H0000FFFF,&H000000FF,&H00000000,&H64000000,-1,0,0,0,100,100,0,0,1,3,0,8,20,20,40,1

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
Dialogue: 0,0:00:00.00,0:01:00.00,Default,,0,0,0,,{\an8}Sous-titre ASS externe du spike
`

// generate writes spike.mkv (H.264 + AAC 440 Hz), audio2.mka (AAC 880 Hz,
// loaded as an external second audio track) and spike.ass, using libmpv's
// encoding mode. No ffmpeg binary is needed.
func generate(api *mpv.API, dir string) error {
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return err
	}
	if err := os.WriteFile(filepath.Join(dir, "spike.ass"), []byte(spikeASS), 0o644); err != nil {
		return err
	}
	// Video: try H.264 first so hwdec can be exercised; fall back if the build lacks libx264.
	videoErr := encode(api, map[string]string{
		"o":       filepath.Join(dir, "spike.mkv"),
		"of":      "matroska",
		"ovc":     "libx264",
		"ovcopts": "preset=veryfast,crf=23",
		"oac":     "aac",
		"vf":      "format=yuv420p",
	}, "av://lavfi:testsrc2=size=1280x720:rate=30:duration=60", "av://lavfi:sine=frequency=440:duration=60")
	if videoErr != nil {
		fmt.Fprintln(os.Stderr, "libx264 failed, retrying with mpeg4:", videoErr)
		videoErr = encode(api, map[string]string{
			"o":   filepath.Join(dir, "spike.mkv"),
			"of":  "matroska",
			"ovc": "mpeg4",
			"oac": "aac",
			"vf":  "format=yuv420p",
		}, "av://lavfi:testsrc2=size=1280x720:rate=30:duration=60", "av://lavfi:sine=frequency=440:duration=60")
		if videoErr != nil {
			return videoErr
		}
	}
	return encode(api, map[string]string{
		"o":   filepath.Join(dir, "audio2.mka"),
		"of":  "matroska",
		"oac": "aac",
		"vid": "no",
	}, "av://lavfi:sine=frequency=880:duration=60", "")
}

func encode(api *mpv.API, opts map[string]string, source, audioFile string) error {
	h, err := api.Create()
	if err != nil {
		return err
	}
	defer h.TerminateDestroy()
	for k, v := range map[string]string{"config": "no", "terminal": "no", "idle": "no", "keep-open": "no", "ao": "null"} {
		if err := h.SetOption(k, v); err != nil {
			return err
		}
	}
	for k, v := range opts {
		if err := h.SetOption(k, v); err != nil {
			return err
		}
	}
	if audioFile != "" {
		if err := h.SetOption("audio-files", audioFile); err != nil {
			return err
		}
	}
	if err := h.RequestLogMessages("warn"); err != nil {
		return err
	}
	if err := h.Initialize(); err != nil {
		return err
	}
	if err := h.Command("loadfile", source); err != nil {
		return err
	}
	deadline := time.Now().Add(120 * time.Second)
	var lastErr string
	for time.Now().Before(deadline) {
		ev := h.WaitEvent(1.0)
		switch ev.ID {
		case mpv.EventLogMessage:
			lastErr = ev.LogLevel + ": " + ev.LogText
			fmt.Fprint(os.Stderr, "mpv ", lastErr)
		case mpv.EventEndFile:
			if ev.EndReason == 4 { // MPV_END_FILE_REASON_ERROR
				return fmt.Errorf("encode %s: %s (%s)", opts["o"], api.ErrorString(ev.Error), lastErr)
			}
			return nil
		case mpv.EventShutdown:
			return nil
		}
	}
	return fmt.Errorf("encode %s: timeout", opts["o"])
}
