//! Playback negotiation (ADR 0007): the libmpv device profile sent to PlaybackInfo,
//! the choice between Direct Play, Direct Stream and Transcode from the server's
//! answer, and the stream URL with its token removed (the token travels in a header).

use super::EmbyError;
use super::models::{
    DeviceProfile, DirectPlayProfile, MediaSourceInfo, PlayMethod, PlaybackInfoResponse,
    SubtitleProfile, TranscodingProfile,
};
use super::server_url::ServerUrl;

/// Local network only for now; becomes a setting with remote access.
pub const MAX_STREAMING_BITRATE: u64 = 400_000_000;

/// Query parameters that carry the token in server-built URLs (compared lowercase).
const TOKEN_PARAMS: [&str; 4] = ["api_key", "apikey", "x-emby-token", "accesstoken"];

/// What libmpv (FFmpeg) decodes. Declared, not tested one by one: see the playback
/// matrix in TESTING.md for verified combinations.
pub fn device_profile() -> DeviceProfile {
    let embed = |format| SubtitleProfile {
        format,
        method: "Embed",
    };
    DeviceProfile {
        name: "Viewer (libmpv)",
        max_streaming_bitrate: MAX_STREAMING_BITRATE,
        direct_play_profiles: vec![DirectPlayProfile {
            kind: "Video",
            container: "mkv,webm,mp4,m4v,mov,avi,ts,m2ts,mpegts,mpg,mpeg,wmv,asf,flv,3gp,ogv",
            video_codec: "h264,hevc,av1,vp8,vp9,mpeg4,mpeg2video,mpeg1video,vc1,wmv3,msmpeg4v3,h263,theora",
            audio_codec: "aac,ac3,eac3,dts,truehd,mlp,flac,opus,vorbis,mp3,mp2,alac,pcm_s16le,pcm_s24le,pcm_bluray,pcm_dvd,wmav2,wmapro",
        }],
        // Declared so the server says when and why a transcode would be needed;
        // transcoding itself is not started yet (ADR 0007).
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
        subtitle_profiles: [
            "ass", "ssa", "subrip", "srt", "pgssub", "dvdsub", "vtt", "mov_text",
        ]
        .into_iter()
        .map(embed)
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
        supports_direct_play,
        supports_direct_stream,
        supports_transcoding,
        direct_stream_url,
        transcoding_url,
        required_http_headers,
        ..
    } = source;
    let (method, raw) = match direct_stream_url {
        Some(url) if supports_direct_play => (PlayMethod::DirectPlay, url),
        Some(url) if supports_direct_stream => (PlayMethod::DirectStream, url),
        _ => match transcoding_url {
            Some(url) if supports_transcoding => {
                return Err(EmbyError::TranscodeRequired(transcode_reasons(&url)));
            }
            _ => return Err(EmbyError::NoCompatibleStream),
        },
    };
    Ok(PlaybackPlan {
        method,
        url: stream_url(server, &raw)?,
        headers: required_headers(required_http_headers.unwrap_or_default()),
        media_source_id: id,
        play_session_id,
        container,
    })
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

    #[test]
    fn transcode_is_reported_with_reasons_not_started() {
        let json = r#"{"MediaSources":[{"Id":"m","SupportsDirectPlay":false,
            "SupportsDirectStream":false,"SupportsTranscoding":true,
            "TranscodingUrl":"/videos/1/master.m3u8?api_key=SECRET&TranscodeReasons=VideoCodecNotSupported,AudioCodecNotSupported",
            "TranscodingSubProtocol":"hls","TranscodingContainer":"ts"}],"PlaySessionId":"p"}"#;
        let err = plan(&server(), source(json)).unwrap_err();
        let EmbyError::TranscodeRequired(reasons) = &err else {
            panic!("{err:?}")
        };
        assert_eq!(
            reasons,
            "codec vidéo non pris en charge, codec audio non pris en charge"
        );
        assert!(!err.to_string().contains("SECRET"));
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
        let json = serde_json::to_value(device_profile()).unwrap();
        assert_eq!(json["DirectPlayProfiles"][0]["Type"], "Video");
        assert!(
            json["DirectPlayProfiles"][0]["VideoCodec"]
                .as_str()
                .unwrap()
                .contains("hevc")
        );
        assert_eq!(json["TranscodingProfiles"][0]["Protocol"], "hls");
        assert_eq!(json["SubtitleProfiles"][0]["Method"], "Embed");
        assert_eq!(json["MaxStreamingBitrate"], MAX_STREAMING_BITRATE);
    }
}
