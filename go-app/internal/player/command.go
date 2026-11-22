package player

import (
	"encoding/json"
	"errors"
	"fmt"
)

// CommandType is the `type` tag of a PlayerCommand, camelCase as serde writes it.
type CommandType string

const (
	CmdSetPaused        CommandType = "setPaused"
	CmdTogglePause      CommandType = "togglePause"
	CmdSeekAbsolute     CommandType = "seekAbsolute"
	CmdSeekRelative     CommandType = "seekRelative"
	CmdSetAudioTrack    CommandType = "setAudioTrack"
	CmdSetSubtitleTrack CommandType = "setSubtitleTrack"
	CmdSetVolume        CommandType = "setVolume"
	CmdSetSpeed         CommandType = "setSpeed"
	CmdSetNightMode     CommandType = "setNightMode"
	CmdSetSubtitleDelay CommandType = "setSubtitleDelay"
	CmdSetAudioDelay    CommandType = "setAudioDelay"
	CmdSetVideoFit      CommandType = "setVideoFit"
	CmdStop             CommandType = "stop"
)

// PlayerCommand is a command from the UI (`PlayerCommand` in player/mod.rs, serde
// internally tagged by `type`). Only the fields of Type are meaningful:
//   - setPaused: Paused;
//   - seekAbsolute, seekRelative, setSubtitleDelay (positive shows them later),
//     setAudioDelay (positive plays it later): Seconds; delays are held within ±10
//   - setAudioTrack, setSubtitleTrack: ID, nil disables the kind (only meaningful
//     for subtitles);
//   - setVolume: Percent; setSpeed: Speed, held within 0.25–4;
//   - setNightMode: On, a compressor in mpv's audio chain;
//   - setVideoFit: Fit.
type PlayerCommand struct {
	Type    CommandType
	Paused  bool
	Seconds float64
	ID      *int64
	Percent float64
	Speed   float64
	On      bool
	Fit     VideoFit
}

// commandFields lists, per variant, its field (empty: unit variant) and whether
// serde lets it be missing (an Option).
var commandFields = map[CommandType]struct {
	name     string
	optional bool
}{
	CmdSetPaused:        {"paused", false},
	CmdTogglePause:      {},
	CmdSeekAbsolute:     {"seconds", false},
	CmdSeekRelative:     {"seconds", false},
	CmdSetAudioTrack:    {"id", true},
	CmdSetSubtitleTrack: {"id", true},
	CmdSetVolume:        {"percent", false},
	CmdSetSpeed:         {"speed", false},
	CmdSetNightMode:     {"on", false},
	CmdSetSubtitleDelay: {"seconds", false},
	CmdSetAudioDelay:    {"seconds", false},
	CmdSetVideoFit:      {"fit", false},
	CmdStop:             {},
}

// UnmarshalJSON follows serde: the tag is exact, the variant's field is required
// (an Option may be missing, or null), unknown keys are ignored.
func (c *PlayerCommand) UnmarshalJSON(b []byte) error {
	var raw map[string]json.RawMessage
	if isNull(b) {
		return errors.New("PlayerCommand : null refusé")
	}
	if err := json.Unmarshal(b, &raw); err != nil {
		return fmt.Errorf("PlayerCommand : %w", err)
	}
	tagRaw, ok := raw["type"]
	if !ok {
		return errors.New("PlayerCommand : champ type manquant")
	}
	var tag string
	if err := strict(tagRaw, &tag); err != nil {
		return fmt.Errorf("PlayerCommand.type : %w", err)
	}
	spec, ok := commandFields[CommandType(tag)]
	if !ok {
		return fmt.Errorf("PlayerCommand : variante inconnue %q", tag)
	}
	out := PlayerCommand{Type: CommandType(tag)}
	if spec.name != "" {
		v, present := raw[spec.name]
		if !present && !spec.optional {
			return fmt.Errorf("PlayerCommand : champ %s manquant", spec.name)
		}
		if present {
			var err error
			switch spec.name {
			case "paused":
				err = strict(v, &out.Paused)
			case "seconds":
				err = strict(v, &out.Seconds)
			case "id":
				err = optional(v, &out.ID)
			case "percent":
				err = strict(v, &out.Percent)
			case "speed":
				err = strict(v, &out.Speed)
			case "on":
				err = strict(v, &out.On)
			case "fit":
				err = strict(v, &out.Fit)
			}
			if err != nil {
				return fmt.Errorf("PlayerCommand.%s : %w", spec.name, err)
			}
		}
	}
	*c = out
	return nil
}

// MarshalJSON writes the serde shape: the tag, then the variant's field.
func (c PlayerCommand) MarshalJSON() ([]byte, error) {
	spec, ok := commandFields[c.Type]
	if !ok {
		return nil, fmt.Errorf("PlayerCommand : variante inconnue %q", c.Type)
	}
	tag, _ := json.Marshal(string(c.Type))
	if spec.name == "" {
		return []byte(`{"type":` + string(tag) + `}`), nil
	}
	var value any
	switch spec.name {
	case "paused":
		value = c.Paused
	case "seconds":
		value = c.Seconds
	case "id":
		value = c.ID
	case "percent":
		value = c.Percent
	case "speed":
		value = c.Speed
	case "on":
		value = c.On
	case "fit":
		value = c.Fit
	}
	v, err := json.Marshal(value)
	if err != nil {
		return nil, err
	}
	return []byte(`{"type":` + string(tag) + `,"` + spec.name + `":` + string(v) + `}`), nil
}

// VideoFit is how the picture fills the window, as on the TV.
type VideoFit string

const (
	// FitOriginal: aspect kept, black bands where it does not match (mpv's default).
	FitOriginal VideoFit = "original"
	// FitFill: aspect kept, cropped until no band is left.
	FitFill VideoFit = "fill"
	// FitStretch: the whole window, the picture distorted to it.
	FitStretch VideoFit = "stretch"
)

// UnmarshalJSON accepts the three camelCase names only.
func (f *VideoFit) UnmarshalJSON(b []byte) error {
	var s string
	if err := strict(b, &s); err != nil {
		return fmt.Errorf("VideoFit : %w", err)
	}
	switch v := VideoFit(s); v {
	case FitOriginal, FitFill, FitStretch:
		*f = v
		return nil
	}
	return fmt.Errorf("VideoFit : variante inconnue %q", s)
}

// MpvProperties gives `panscan` and `keepaspect`, both always set so nothing of
// the previous fit lingers. `video-unscaled` stays `no`: it would disable `panscan`.
func (f VideoFit) MpvProperties() [2][2]string {
	switch f {
	case FitFill:
		return [2][2]string{{"panscan", "1"}, {"keepaspect", "yes"}}
	case FitStretch:
		return [2][2]string{{"panscan", "0"}, {"keepaspect", "no"}}
	}
	return [2][2]string{{"panscan", "0"}, {"keepaspect", "yes"}}
}
