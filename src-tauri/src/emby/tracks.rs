//! Audio and subtitle preferences kept on this PC (ADR 0035): which streams of the
//! source to play, from the server's own picks and the user's languages and mode.

use serde::Deserialize;

use super::EmbyError;
use super::models::{MediaStreamDto, PlayMethod};
use super::playback::PlaybackPlan;
use crate::player::{TrackPick, TrackSelection};

/// What the user chose in Réglages › Sous-titres. Languages are ISO 639-2/B codes
/// of [`LANGUAGES`]; `None` keeps the server's pick.
#[derive(Debug, Clone, Default, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct TrackPreferences {
    pub audio_language: Option<String>,
    pub subtitle_language: Option<String>,
    pub subtitle_mode: SubtitleMode,
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum SubtitleMode {
    /// The subtitle the server picks from the user's Emby settings.
    #[default]
    Server,
    Off,
    /// Forced subtitles only (signs, foreign dialogue), in the subtitle language or
    /// else the audio's.
    ForcedOnly,
    /// Full subtitles, forced ones when there are no others.
    Always,
}

/// Languages offered, each with the spellings found in files: `fre`, `fra` and `fr`
/// were all seen on the test server.
pub const LANGUAGES: [&[&str]; 12] = [
    &["fre", "fra", "fr"],
    &["eng", "en"],
    &["spa", "es"],
    &["ger", "deu", "de"],
    &["ita", "it"],
    &["por", "pt"],
    &["dut", "nld", "nl"],
    &["jpn", "ja"],
    &["kor", "ko"],
    &["chi", "zho", "zh"],
    &["rus", "ru"],
    &["ara", "ar"],
];

/// Text formats the subtitle style applies to (ADR 0035); image formats (PGS,
/// DVD) cannot be restyled, so text wins when both match.
const TEXT_SUBTITLES: [&str; 7] = ["subrip", "srt", "ass", "ssa", "vtt", "webvtt", "mov_text"];

impl TrackPreferences {
    /// Refuses a language the UI does not offer, before any request.
    pub fn validate(&self) -> Result<(), EmbyError> {
        for code in [&self.audio_language, &self.subtitle_language]
            .into_iter()
            .flatten()
        {
            if spellings(code).is_none() {
                return Err(EmbyError::InvalidResponse(format!(
                    "langue inconnue : {code}"
                )));
            }
        }
        Ok(())
    }
}

fn spellings(code: &str) -> Option<&'static [&'static str]> {
    LANGUAGES.into_iter().find(|l| l[0] == code)
}

/// Spellings of a stream's language, when it is one of [`LANGUAGES`].
fn stream_language(stream: &MediaStreamDto) -> Option<&'static [&'static str]> {
    let lang = stream.language.as_deref()?.to_ascii_lowercase();
    LANGUAGES.into_iter().find(|l| l.contains(&lang.as_str()))
}

fn speaks(stream: &MediaStreamDto, language: &[&str]) -> bool {
    stream_language(stream).is_some_and(|l| l[0] == language[0])
}

fn is_text(stream: &MediaStreamDto) -> bool {
    stream
        .codec
        .as_deref()
        .is_some_and(|c| TEXT_SUBTITLES.contains(&c.to_ascii_lowercase().as_str()))
}

/// The streams picked, as Emby `Index` values. `subtitle: None` means none.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Choice {
    pub audio: Option<i32>,
    pub subtitle: SubtitleChoice,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SubtitleChoice {
    /// Neither the server nor the preferences name one: the player decides.
    Player,
    Off,
    Stream(i32),
}

/// Picks the audio and subtitle streams of a source. Without a preference the
/// server's picks stand. When transcoding, only subtitles the server delivers as
/// files can be shown: the others would be burnt into the picture, which fails on
/// the test server (ADR 0009). When playing the file, the server's external
/// subtitle files are not loaded, so only the file's own count.
pub fn choose(
    streams: &[MediaStreamDto],
    method: PlayMethod,
    default_audio: Option<i32>,
    default_subtitle: Option<i32>,
    prefs: &TrackPreferences,
) -> Choice {
    let audio_streams: Vec<&MediaStreamDto> = streams
        .iter()
        .filter(|s| s.stream_type == "Audio" && !s.is_external && s.index.is_some())
        .collect();
    let server_audio = audio_streams
        .iter()
        .find(|s| default_audio.is_some() && s.index == default_audio)
        .copied();
    let wanted_audio = prefs.audio_language.as_deref().and_then(spellings);
    let audio = wanted_audio
        .and_then(|lang| {
            let speaking: Vec<_> = audio_streams.iter().filter(|s| speaks(s, lang)).collect();
            // The server's pick when it already speaks the language, then the file's default.
            speaking
                .iter()
                .find(|s| s.index == server_audio.and_then(|a| a.index))
                .or_else(|| speaking.iter().find(|s| s.is_default))
                .or_else(|| speaking.first())
                .map(|s| **s)
        })
        .or(server_audio);

    let candidates: Vec<&MediaStreamDto> = streams
        .iter()
        .filter(|s| s.stream_type == "Subtitle" && s.index.is_some())
        .filter(|s| match method {
            PlayMethod::Transcode => {
                s.delivery_method.as_deref() == Some("External") && s.delivery_url.is_some()
            }
            PlayMethod::DirectPlay | PlayMethod::DirectStream => !s.is_external,
        })
        .collect();
    // Text first, then the file's default flag, then the file's order.
    let best = |pick: &dyn Fn(&MediaStreamDto) -> bool| {
        candidates
            .iter()
            .filter(|s| pick(s))
            .min_by_key(|s| (!is_text(s), !s.is_default, s.index))
            .and_then(|s| s.index)
    };
    let wanted_subtitle = prefs.subtitle_language.as_deref().and_then(spellings);
    let subtitle = match prefs.subtitle_mode {
        SubtitleMode::Server => match default_subtitle {
            Some(i) if candidates.iter().any(|s| s.index == Some(i)) => SubtitleChoice::Stream(i),
            // None, or one this playback cannot show: as before ADR 0035.
            _ => SubtitleChoice::Player,
        },
        SubtitleMode::Off => SubtitleChoice::Off,
        SubtitleMode::ForcedOnly => wanted_subtitle
            .or_else(|| audio.and_then(stream_language))
            .and_then(|lang| best(&|s| s.is_forced && speaks(s, lang)))
            .map_or(SubtitleChoice::Off, SubtitleChoice::Stream),
        SubtitleMode::Always => match wanted_subtitle {
            Some(lang) => best(&|s| !s.is_forced && speaks(s, lang))
                .or_else(|| best(&|s| s.is_forced && speaks(s, lang))),
            None => default_subtitle
                .filter(|&i| {
                    candidates
                        .iter()
                        .any(|s| s.index == Some(i) && !s.is_forced)
                })
                .or_else(|| best(&|s| !s.is_forced)),
        }
        .map_or(SubtitleChoice::Off, SubtitleChoice::Stream),
    };
    Choice {
        audio: audio.and_then(|s| s.index),
        subtitle,
    }
}

impl Choice {
    /// What the player selects once the file is loaded. A transcode carries one
    /// audio track, the one asked of the server, and its subtitles come as files
    /// selected when added.
    pub fn selection(&self, method: PlayMethod) -> TrackSelection {
        let stream = |i: i32| TrackPick::Stream {
            index: i64::from(i),
        };
        let subtitle = match self.subtitle {
            SubtitleChoice::Off => TrackPick::Off,
            SubtitleChoice::Stream(i) if method != PlayMethod::Transcode => stream(i),
            _ => TrackPick::Auto,
        };
        let audio = match self.audio {
            Some(i) if method != PlayMethod::Transcode => stream(i),
            _ => TrackPick::Auto,
        };
        TrackSelection { audio, subtitle }
    }
}

/// Picks the tracks of a plan and marks them: the player's selection, and for a
/// transcode which subtitle file is selected when added.
pub fn apply(plan: &mut PlaybackPlan, prefs: &TrackPreferences) -> Choice {
    let choice = choose(
        &plan.streams,
        plan.method,
        plan.default_audio_stream_index,
        plan.default_subtitle_stream_index,
        prefs,
    );
    if plan.method == PlayMethod::Transcode {
        let wanted = match choice.subtitle {
            SubtitleChoice::Stream(i) => Some(i),
            SubtitleChoice::Player | SubtitleChoice::Off => None,
        };
        // Same filter and order as the files of `playback::plan`.
        let files = plan.streams.iter().filter(|s| {
            s.stream_type == "Subtitle"
                && s.delivery_method.as_deref() == Some("External")
                && s.delivery_url.is_some()
        });
        for (file, stream) in plan.subtitles.iter_mut().zip(files) {
            file.selected = wanted.is_some() && stream.index == wanted;
        }
    }
    plan.tracks = choice.selection(plan.method);
    choice
}

#[cfg(test)]
mod tests {
    use super::*;

    fn streams(json: &str) -> Vec<MediaStreamDto> {
        serde_json::from_str(json).unwrap()
    }

    /// Streams of a real film of the test server (Emby 4.10.1.0, PlaybackInfo):
    /// French and English audio, forced and full French subtitles, English ones, and
    /// PGS copies.
    fn film() -> Vec<MediaStreamDto> {
        streams(
            r#"[{"Type":"Video","Index":0,"Codec":"hevc"},
            {"Type":"Audio","Index":1,"Codec":"ac3","Language":"fre","IsDefault":true},
            {"Type":"Audio","Index":2,"Codec":"ac3","Language":"eng"},
            {"Type":"Subtitle","Index":3,"Codec":"subrip","Language":"fre","IsForced":true},
            {"Type":"Subtitle","Index":4,"Codec":"subrip","Language":"fre","IsForced":true,"IsDefault":true},
            {"Type":"Subtitle","Index":5,"Codec":"subrip","Language":"fre"},
            {"Type":"Subtitle","Index":6,"Codec":"subrip","Language":"eng"},
            {"Type":"Subtitle","Index":7,"Codec":"PGSSUB","Language":"fra"},
            {"Type":"Subtitle","Index":8,"Codec":"PGSSUB","Language":"en"},
            {"Type":"Subtitle","Index":9,"Codec":"subrip","Language":"spa","IsExternal":true}]"#,
        )
    }

    fn prefs(audio: Option<&str>, subtitle: Option<&str>, mode: SubtitleMode) -> TrackPreferences {
        TrackPreferences {
            audio_language: audio.map(str::to_owned),
            subtitle_language: subtitle.map(str::to_owned),
            subtitle_mode: mode,
        }
    }

    fn direct(p: &TrackPreferences) -> Choice {
        choose(&film(), PlayMethod::DirectPlay, Some(1), Some(4), p)
    }

    #[test]
    fn without_preferences_the_server_picks_stand() {
        let c = direct(&TrackPreferences::default());
        assert_eq!(c.audio, Some(1));
        assert_eq!(c.subtitle, SubtitleChoice::Stream(4));
        // No subtitle named by the server: the player decides, as before ADR 0035.
        let c = choose(
            &film(),
            PlayMethod::DirectPlay,
            Some(1),
            None,
            &TrackPreferences::default(),
        );
        assert_eq!(c.subtitle, SubtitleChoice::Player);
    }

    #[test]
    fn audio_language_wins_over_the_server_and_falls_back_to_it() {
        assert_eq!(
            direct(&prefs(Some("eng"), None, SubtitleMode::Server)).audio,
            Some(2)
        );
        // No Japanese track: the server's audio stays.
        assert_eq!(
            direct(&prefs(Some("jpn"), None, SubtitleMode::Server)).audio,
            Some(1)
        );
    }

    #[test]
    fn subtitle_modes() {
        assert_eq!(
            direct(&prefs(None, None, SubtitleMode::Off)).subtitle,
            SubtitleChoice::Off
        );
        // Forced only: in the audio's language when no subtitle language is set,
        // the file's default among them.
        assert_eq!(
            direct(&prefs(None, None, SubtitleMode::ForcedOnly)).subtitle,
            SubtitleChoice::Stream(4)
        );
        // English audio and forced only: no English forced track, so none.
        assert_eq!(
            direct(&prefs(Some("eng"), None, SubtitleMode::ForcedOnly)).subtitle,
            SubtitleChoice::Off
        );
        // Always in French: the full text track, not the forced ones nor the PGS.
        assert_eq!(
            direct(&prefs(None, Some("fre"), SubtitleMode::Always)).subtitle,
            SubtitleChoice::Stream(5)
        );
        // `en` in the file matches English; text before the PGS copy.
        assert_eq!(
            direct(&prefs(Some("eng"), Some("eng"), SubtitleMode::Always)).subtitle,
            SubtitleChoice::Stream(6)
        );
        // The server's external Spanish file is not loaded when playing the file.
        assert_eq!(
            direct(&prefs(None, Some("spa"), SubtitleMode::Always)).subtitle,
            SubtitleChoice::Off
        );
        // Always without a language: the server's pick is forced, so the first full one.
        assert_eq!(
            direct(&prefs(None, None, SubtitleMode::Always)).subtitle,
            SubtitleChoice::Stream(5)
        );
    }

    #[test]
    fn always_falls_back_to_forced_subtitles_in_the_language() {
        let only_forced: Vec<_> = film()
            .into_iter()
            .filter(|s| s.index != Some(5) && s.index != Some(7))
            .collect();
        let c = choose(
            &only_forced,
            PlayMethod::DirectPlay,
            Some(1),
            None,
            &prefs(None, Some("fre"), SubtitleMode::Always),
        );
        assert_eq!(c.subtitle, SubtitleChoice::Stream(4));
    }

    #[test]
    fn a_transcode_only_shows_subtitles_delivered_as_files() {
        let transcode = streams(
            r#"[{"Type":"Audio","Index":1,"Language":"fre","IsDefault":true},
            {"Type":"Audio","Index":2,"Language":"eng"},
            {"Type":"Subtitle","Index":3,"Codec":"subrip","Language":"fre","DeliveryMethod":"External","DeliveryUrl":"/Videos/1/s/Subtitles/3/0/Stream.subrip"},
            {"Type":"Subtitle","Index":4,"Codec":"PGSSUB","Language":"eng","DeliveryMethod":"Encode"}]"#,
        );
        let c = choose(
            &transcode,
            PlayMethod::Transcode,
            Some(1),
            Some(3),
            &prefs(Some("eng"), Some("eng"), SubtitleMode::Always),
        );
        assert_eq!(c.audio, Some(2));
        assert_eq!(c.subtitle, SubtitleChoice::Off, "PGS would be burnt in");
        // The server's pick that cannot be shown is left to the player, not burnt in.
        let c = choose(
            &transcode,
            PlayMethod::Transcode,
            Some(1),
            Some(4),
            &TrackPreferences::default(),
        );
        assert_eq!(c.subtitle, SubtitleChoice::Player);
    }

    #[test]
    fn player_selection_by_method() {
        let c = Choice {
            audio: Some(2),
            subtitle: SubtitleChoice::Stream(5),
        };
        assert_eq!(
            c.selection(PlayMethod::DirectPlay),
            TrackSelection {
                audio: TrackPick::Stream { index: 2 },
                subtitle: TrackPick::Stream { index: 5 }
            }
        );
        assert_eq!(
            c.selection(PlayMethod::Transcode),
            TrackSelection::default()
        );
        let off = Choice {
            audio: None,
            subtitle: SubtitleChoice::Off,
        };
        assert_eq!(
            off.selection(PlayMethod::Transcode).subtitle,
            TrackPick::Off
        );
        let player = Choice {
            audio: None,
            subtitle: SubtitleChoice::Player,
        };
        assert_eq!(
            player.selection(PlayMethod::DirectStream),
            TrackSelection::default()
        );
    }

    #[test]
    fn unknown_languages_are_refused() {
        assert!(
            prefs(Some("fre"), Some("eng"), SubtitleMode::Always)
                .validate()
                .is_ok()
        );
        assert!(
            prefs(Some("fra"), None, SubtitleMode::Server)
                .validate()
                .is_err()
        );
        assert!(
            prefs(None, Some("xx\n"), SubtitleMode::Server)
                .validate()
                .is_err()
        );
        let parsed: TrackPreferences =
            serde_json::from_str(r#"{"subtitleMode":"forcedOnly","audioLanguage":"eng"}"#).unwrap();
        assert_eq!(parsed, prefs(Some("eng"), None, SubtitleMode::ForcedOnly));
        assert!(serde_json::from_str::<TrackPreferences>(r#"{"subtitleMode":"smart"}"#).is_err());
    }
}
