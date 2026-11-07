//! Playback negotiation (ADR 0007, ADR 0009): the libmpv device profile sent to
//! PlaybackInfo, the choice between Direct Play, Direct Stream and an HLS transcode
//! from the server's answer, and stream and subtitle URLs with their token removed
//! (the token travels in a header).

use super::EmbyError;
use super::models::{
    DeviceProfile, DirectPlayProfile, MediaSourceInfo, PlayMethod, PlaybackInfoResponse,
    PlaybackTitle, SubtitleProfile, TranscodingProfile,
};
use super::quality::StreamingLimit;
use super::server_url::ServerUrl;
use crate::player::ExternalSubtitle;

/// Sent when no cap is chosen ("Maximale", ADR 0037).
pub const MAX_STREAMING_BITRATE: u64 = 400_000_000;

/// Text formats a transcode can deliver as separate files instead of burning them
/// into the picture (burn-in fails on the test server, ADR 0009).
const EXTERNAL_TEXT_SUBTITLES: [&str; 5] = ["ass", "ssa", "subrip", "srt", "vtt"];

/// Query parameters that carry the token in server-built URLs (compared lowercase).
const TOKEN_PARAMS: [&str; 4] = ["api_key", "apikey", "x-emby-token", "accesstoken"];

/// What libmpv (FFmpeg) decodes. Declared, not tested one by one: see the playback
/// matrix in TESTING.md for verified combinations.
/// `max_streaming_bitrate` above a file's bitrate makes the server transcode it.
pub fn device_profile(max_streaming_bitrate: u64) -> DeviceProfile {
    let subtitle = |method| move |format| SubtitleProfile { format, method };
    DeviceProfile {
        name: "Viewer (libmpv)",
        max_streaming_bitrate,
        direct_play_profiles: vec![DirectPlayProfile {
            kind: "Video",
            container: "mkv,webm,mp4,m4v,mov,avi,ts,m2ts,mpegts,mpg,mpeg,wmv,asf,flv,3gp,ogv",
            video_codec: "h264,hevc,av1,vp8,vp9,mpeg4,mpeg2video,mpeg1video,vc1,wmv3,msmpeg4v3,h263,theora",
            audio_codec: "aac,ac3,eac3,dts,truehd,mlp,flac,opus,vorbis,mp3,mp2,alac,pcm_s16le,pcm_s24le,pcm_bluray,pcm_dvd,wmav2,wmapro",
        }],
        // HLS in MPEG-TS, read by mpv; audio copied when it fits (ADR 0009).
        transcoding_profiles: vec![TranscodingProfile {
            kind: "Video",
            container: "ts",
            protocol: "hls",
            video_codec: "h264",
            audio_codec: "aac,ac3,eac3",
            context: "Streaming",
            max_audio_channels: "8",
            break_on_non_key_frames: true,
        }],
        // Embedded when playing the file; text tracks of a transcode come as
        // separate files (MPEG-TS cannot carry them).
        subtitle_profiles: [
            "ass", "ssa", "subrip", "srt", "pgssub", "dvdsub", "vtt", "mov_text",
        ]
        .into_iter()
        .map(subtitle("Embed"))
        .chain(
            EXTERNAL_TEXT_SUBTITLES
                .into_iter()
                .map(subtitle("External")),
        )
        .collect(),
    }
}

/// The source chosen for the player. The URL holds no token.
#[derive(Debug, Clone, PartialEq)]
pub struct PlaybackPlan {
    pub method: PlayMethod,
    pub url: String,
    /// Extra headers the server requires for this source (never authentication).
    pub headers: Vec<(String, String)>,
    pub media_source_id: String,
    pub play_session_id: Option<String>,
    pub container: Option<String>,
    pub run_time_ticks: Option<i64>,
    /// Where the player starts: 0, or the server's resume position (ADR 0008).
    pub start_ticks: i64,
    /// French reasons, for a transcode only.
    pub transcode_reasons: Option<String>,
    /// Text subtitles a transcode delivers as files, URLs without token.
    pub subtitles: Vec<ExternalSubtitle>,
    /// Set by the session from the item, not by PlaybackInfo (ADR 0017).
    pub title: Option<PlaybackTitle>,
    /// Set by the session from the bitrate setting (ADR 0037).
    pub bitrate_limit: Option<StreamingLimit>,
}

/// Picks the first source the server offers and the best way to play it.
pub fn plan(server: &ServerUrl, response: PlaybackInfoResponse) -> Result<PlaybackPlan, EmbyError> {
    match response.error_code.as_deref() {
        None => {}
        Some("NotAllowed") => {
            return Err(EmbyError::PlaybackRefused(
                "lecture non autorisée pour ce compte".into(),
            ));
        }
        Some("RateLimitExceeded") => {
            return Err(EmbyError::PlaybackRefused(
                "trop de lectures en cours sur le serveur".into(),
            ));
        }
        Some(_) => return Err(EmbyError::NoCompatibleStream),
    }
    let play_session_id = response.play_session_id;
    let source = response
        .media_sources
        .into_iter()
        .next()
        .ok_or(EmbyError::NoCompatibleStream)?;
    let MediaSourceInfo {
        id,
        container,
        run_time_ticks,
        supports_direct_play,
        supports_direct_stream,
        supports_transcoding,
        direct_stream_url,
        transcoding_url,
        transcoding_container,
        required_http_headers,
        default_subtitle_stream_index,
        media_streams,
        ..
    } = source;
    let (method, raw) = match (direct_stream_url, transcoding_url) {
        // A transcode also fills `DirectStreamUrl` with the transcoding URL, so the
        // flags decide, not which URL is present.
        (Some(url), _) if supports_direct_play => (PlayMethod::DirectPlay, url),
        (Some(url), _) if supports_direct_stream => (PlayMethod::DirectStream, url),
        (_, Some(url)) if supports_transcoding => (PlayMethod::Transcode, url),
        _ => return Err(EmbyError::NoCompatibleStream),
    };
    let transcode = method == PlayMethod::Transcode;
    let subtitles = if transcode {
        external_subtitles(server, &media_streams, default_subtitle_stream_index)?
    } else {
        Vec::new()
    };
    Ok(PlaybackPlan {
        method,
        url: stream_url(server, &raw)?,
        headers: required_headers(required_http_headers.unwrap_or_default()),
        media_source_id: id,
        play_session_id,
        container: if transcode {
            transcoding_container
        } else {
            container
        },
        run_time_ticks: run_time_ticks.filter(|&t| t > 0),
        start_ticks: 0,
        transcode_reasons: transcode.then(|| transcode_reasons(&raw)),
        subtitles,
        title: None,
        bitrate_limit: None,
    })
}

/// Subtitles the server delivers as separate files (`External`), in its order;
/// the one it picked from the user's preferences is selected.
fn external_subtitles(
    server: &ServerUrl,
    streams: &[super::models::MediaStreamDto],
    default_index: Option<i32>,
) -> Result<Vec<ExternalSubtitle>, EmbyError> {
    streams
        .iter()
        .filter(|s| s.stream_type == "Subtitle" && s.delivery_method.as_deref() == Some("External"))
        .filter_map(|s| s.delivery_url.as_deref().map(|url| (s, url)))
        .map(|(s, url)| {
            Ok(ExternalSubtitle {
                source: stream_url(server, url)?,
                title: s.title.clone(),
                lang: s.language.clone(),
                selected: default_index.is_some() && s.index == default_index,
            })
        })
        .collect()
}

/// Absolute stream URL from a server-relative `/videos/…` path, without any
/// token-bearing parameter. Anything else (absolute URL, other path) is refused.
pub fn stream_url(server: &ServerUrl, raw: &str) -> Result<String, EmbyError> {
    let invalid = || EmbyError::InvalidResponse("adresse de flux inattendue".into());
    if !raw.starts_with('/') || raw.starts_with("//") || raw.contains('\\') {
        return Err(invalid());
    }
    let base = url::Url::parse(&server.api("/")).map_err(|_| invalid())?;
    // `Url` resolves `..` and `%2e%2e`, so the prefix check sees the final path.
    let mut url = url::Url::parse(&server.api(raw)).map_err(|_| invalid())?;
    let videos = format!("{}videos/", base.path());
    if url.origin() != base.origin()
        || !url
            .path()
            .to_ascii_lowercase()
            .starts_with(&videos.to_ascii_lowercase())
    {
        return Err(invalid());
    }
    let kept: Vec<(String, String)> = url
        .query_pairs()
        .filter(|(k, _)| !TOKEN_PARAMS.contains(&k.to_ascii_lowercase().as_str()))
        .map(|(k, v)| (k.into_owned(), v.into_owned()))
        .collect();
    url.set_query(None);
    url.set_fragment(None);
    if !kept.is_empty() {
        url.query_pairs_mut().extend_pairs(kept);
    }
    Ok(url.into())
}

/// Server-required headers that are safe to hand to mpv: plain names, no control
/// characters, nothing that could replace authentication.
fn required_headers(headers: std::collections::HashMap<String, String>) -> Vec<(String, String)> {
    let mut out: Vec<(String, String)> = headers
        .into_iter()
        .filter(|(name, value)| {
            let lower = name.to_ascii_lowercase();
            !name.is_empty()
                && name.chars().all(|c| c.is_ascii_alphanumeric() || c == '-')
                && !lower.contains("auth")
                && !lower.contains("token")
                && !lower.contains("cookie")
                && !value.chars().any(|c| c.is_control() || c == ',')
        })
        .collect();
    out.sort();
    out
}

/// French reasons from `TranscodeReasons=` in a transcoding URL.
fn transcode_reasons(raw: &str) -> String {
    let query = raw.split_once('?').map(|(_, q)| q).unwrap_or_default();
    let reasons: Vec<&str> = url::form_urlencoded::parse(query.as_bytes())
        .filter(|(k, _)| k.eq_ignore_ascii_case("TranscodeReasons"))
        .flat_map(|(_, v)| {
            v.split(',')
                .map(|r| reason_label(r.trim()))
                .collect::<Vec<_>>()
        })
        .collect();
    if reasons.is_empty() {
        "raison non précisée".into()
    } else {
        reasons.join(", ")
    }
}

fn reason_label(reason: &str) -> &'static str {
    match reason {
        "ContainerNotSupported" => "conteneur non pris en charge",
        "VideoCodecNotSupported" => "codec vidéo non pris en charge",
        "AudioCodecNotSupported" => "codec audio non pris en charge",
        "SubtitleCodecNotSupported" => "sous-titres non pris en charge",
        "ContainerBitrateExceedsLimit" | "VideoBitrateNotSupported" => "débit trop élevé",
        "AudioChannelsNotSupported" => "nombre de canaux audio non pris en charge",
        "DirectPlayError" => "échec de la lecture directe",
        _ => "autre raison",
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn server() -> ServerUrl {
        ServerUrl::parse("http://192.168.1.20:8096").unwrap()
    }

    fn source(json: &str) -> PlaybackInfoResponse {
        serde_json::from_str(json).unwrap()
    }

    // Shape of a real Emby 4.10.1.0 answer (ids changed, token replaced).
    const DIRECT: &str = r#"{"MediaSources":[{"Protocol":"File","Id":"mediasource_30207",
        "Type":"Default","Container":"mkv","SupportsTranscoding":true,
        "SupportsDirectStream":true,"SupportsDirectPlay":true,"RequiredHttpHeaders":{},
        "DirectStreamUrl":"/videos/30207/original.mkv?DeviceId=dev&MediaSourceId=mediasource_30207&PlaySessionId=abc&api_key=SECRET",
        "AddApiKeyToDirectStreamUrl":false,"DefaultAudioStreamIndex":1}],"PlaySessionId":"abc"}"#;

    #[test]
    fn direct_play_without_token_in_url() {
        let plan = plan(&server(), source(DIRECT)).unwrap();
        assert_eq!(plan.method, PlayMethod::DirectPlay);
        assert_eq!(
            plan.url,
            "http://192.168.1.20:8096/emby/videos/30207/original.mkv?DeviceId=dev&MediaSourceId=mediasource_30207&PlaySessionId=abc"
        );
        assert!(!plan.url.contains("SECRET"));
        assert_eq!(plan.media_source_id, "mediasource_30207");
        assert_eq!(plan.play_session_id.as_deref(), Some("abc"));
        assert_eq!(plan.container.as_deref(), Some("mkv"));
        assert!(plan.headers.is_empty());
    }

    #[test]
    fn direct_stream_when_only_that_is_allowed() {
        let json = DIRECT.replace(
            r#""SupportsDirectPlay":true"#,
            r#""SupportsDirectPlay":false"#,
        );
        assert_eq!(
            plan(&server(), source(&json)).unwrap().method,
            PlayMethod::DirectStream
        );
    }

    // Shape of a real Emby 4.10.1.0 transcode (bitrate above the limit): the
    // transcoding URL is in both fields, text subtitles are `External`.
    const TRANSCODE: &str = r#"{"MediaSources":[{"Id":"mediasource_30207","Container":"mkv",
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
        "PlaySessionId":"p"}"#;

    #[test]
    fn transcode_plays_hls_with_external_text_subtitles() {
        let plan = plan(&server(), source(TRANSCODE)).unwrap();
        assert_eq!(plan.method, PlayMethod::Transcode);
        assert_eq!(
            plan.url,
            "http://192.168.1.20:8096/emby/videos/30207/master.m3u8?PlaySessionId=p&TranscodeReasons=ContainerBitrateExceedsLimit"
        );
        assert_eq!(plan.container.as_deref(), Some("ts"));
        assert_eq!(plan.transcode_reasons.as_deref(), Some("débit trop élevé"));
        let subs: Vec<_> = plan
            .subtitles
            .iter()
            .map(|s| {
                (
                    s.source.as_str(),
                    s.lang.as_deref(),
                    s.title.as_deref(),
                    s.selected,
                )
            })
            .collect();
        assert_eq!(
            subs,
            [
                (
                    "http://192.168.1.20:8096/emby/Videos/30207/mediasource_30207/Subtitles/3/0/Stream.subrip",
                    Some("fre"),
                    Some("Forcés"),
                    false
                ),
                (
                    "http://192.168.1.20:8096/emby/Videos/30207/mediasource_30207/Subtitles/4/0/Stream.subrip",
                    Some("eng"),
                    None,
                    true
                ),
            ]
        );
        assert!(!format!("{plan:?}").contains("SECRET"));
    }

    #[test]
    fn transcode_reasons_in_french_and_no_subtitles_when_playing_the_file() {
        let json = TRANSCODE.replace(
            "ContainerBitrateExceedsLimit",
            "VideoCodecNotSupported,AudioCodecNotSupported",
        );
        assert_eq!(
            plan(&server(), source(&json))
                .unwrap()
                .transcode_reasons
                .as_deref(),
            Some("codec vidéo non pris en charge, codec audio non pris en charge")
        );
        let direct = TRANSCODE.replace(
            r#""SupportsDirectPlay":false"#,
            r#""SupportsDirectPlay":true"#,
        );
        let plan = plan(&server(), source(&direct)).unwrap();
        assert_eq!(plan.method, PlayMethod::DirectPlay);
        assert!(plan.subtitles.is_empty() && plan.transcode_reasons.is_none());
        assert_eq!(plan.container.as_deref(), Some("mkv"));
    }

    #[test]
    fn subtitle_urls_off_the_server_are_refused() {
        let json = TRANSCODE.replace(
            "/Videos/30207/mediasource_30207/Subtitles/4/0/Stream.subrip",
            "http://evil.example/sub.srt",
        );
        assert!(plan(&server(), source(&json)).is_err());
    }

    #[test]
    fn server_error_codes_and_empty_answers() {
        let refused = plan(&server(), source(r#"{"ErrorCode":"NotAllowed"}"#)).unwrap_err();
        assert_eq!(refused.kind(), "playbackRefused");
        let limit = plan(&server(), source(r#"{"ErrorCode":"RateLimitExceeded"}"#)).unwrap_err();
        assert_eq!(limit.kind(), "playbackRefused");
        for json in [
            r#"{"ErrorCode":"NoCompatibleStream"}"#,
            r#"{"MediaSources":[]}"#,
            r#"{"MediaSources":[{"Id":"m","SupportsDirectPlay":true}]}"#,
            r#"{"MediaSources":[{"Id":"m","SupportsDirectPlay":false,"SupportsDirectStream":false,
                "SupportsTranscoding":false,"DirectStreamUrl":"/videos/1/original.mkv"}]}"#,
        ] {
            let err = plan(&server(), source(json)).unwrap_err();
            assert_eq!(err.kind(), "noCompatibleStream", "{json}");
        }
    }

    #[test]
    fn stream_url_stays_under_videos_on_the_server() {
        let s = server();
        for raw in [
            "http://evil.example/videos/1/original.mkv",
            "//evil.example/videos/1/original.mkv",
            "/Users/1/Items",
            "/videos/../Users/1",
            "/videos/%2e%2e/Users/1",
            "videos/1/original.mkv",
            "/videos\\..\\Users",
        ] {
            assert!(stream_url(&s, raw).is_err(), "{raw}");
        }
        assert_eq!(
            stream_url(
                &s,
                "/Videos/1/stream.mkv?Static=true&ApiKey=x&X-Emby-Token=y&AccessToken=z#f"
            )
            .unwrap(),
            "http://192.168.1.20:8096/emby/Videos/1/stream.mkv?Static=true"
        );
    }

    #[test]
    fn only_plain_required_headers_are_kept() {
        let headers = [
            ("Referer", "x"),
            ("X-Emby-Token", "t"),
            ("Authorization", "a"),
            ("Bad Name", "v"),
            ("X-Ok", "line\r\nInjected: 1"),
            ("Cookie", "c"),
        ]
        .into_iter()
        .map(|(k, v)| (k.to_owned(), v.to_owned()))
        .collect();
        assert_eq!(
            required_headers(headers),
            vec![("Referer".to_owned(), "x".to_owned())]
        );
    }

    #[test]
    fn profile_serializes_as_emby_expects() {
        let json = serde_json::to_value(device_profile(MAX_STREAMING_BITRATE)).unwrap();
        assert_eq!(json["DirectPlayProfiles"][0]["Type"], "Video");
        assert!(
            json["DirectPlayProfiles"][0]["VideoCodec"]
                .as_str()
                .unwrap()
                .contains("hevc")
        );
        assert_eq!(json["TranscodingProfiles"][0]["Protocol"], "hls");
        assert_eq!(json["SubtitleProfiles"][0]["Method"], "Embed");
        let external: Vec<_> = json["SubtitleProfiles"]
            .as_array()
            .unwrap()
            .iter()
            .filter(|p| p["Method"] == "External")
            .map(|p| p["Format"].as_str().unwrap())
            .collect();
        assert_eq!(external, EXTERNAL_TEXT_SUBTITLES);
        assert_eq!(json["MaxStreamingBitrate"], MAX_STREAMING_BITRATE);
    }
}
