//! « Bande-annonce » of a title page of Demander (ADR 0127): the `trailerKey`
//! of Request is a YouTube video id, opened in the default browser. The
//! WebView passes the key only; the address is built and checked here.

use serde::Serialize;

/// Longest key accepted; YouTube ids are 11 characters.
const KEY_MAX: usize = 64;

#[derive(Debug, PartialEq, Eq, thiserror::Error)]
pub enum TrailerError {
    #[error("bande-annonce invalide")]
    Invalid,
    #[error("navigateur non ouvert : {0}")]
    Open(String),
}

impl Serialize for TrailerError {
    fn serialize<S: serde::Serializer>(&self, s: S) -> Result<S::Ok, S::Error> {
        s.serialize_str(&self.to_string())
    }
}

/// The YouTube page of `key` when it is a plain video id (ASCII letters,
/// digits, `-`, `_`), so nothing can be added to the address.
pub fn trailer_page(key: &str) -> Option<String> {
    let plain = !key.is_empty()
        && key.len() <= KEY_MAX
        && key
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_');
    plain.then(|| format!("https://www.youtube.com/watch?v={key}"))
}

/// Opens the YouTube page of `key` in the default browser. Nothing else goes
/// to YouTube: no token, no identity.
#[tauri::command]
pub fn ur_open_trailer(key: String) -> Result<(), TrailerError> {
    let page = trailer_page(&key).ok_or(TrailerError::Invalid)?;
    crate::diagnostics::open_in_explorer(std::ffi::OsStr::new(&page))
        .map_err(|e| TrailerError::Open(e.kind().to_string()))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_video_id_opens_its_youtube_page() {
        assert_eq!(
            trailer_page("9YGuN8sQpL0").as_deref(),
            Some("https://www.youtube.com/watch?v=9YGuN8sQpL0")
        );
        assert_eq!(
            trailer_page("uygf0l_WzjA").as_deref(),
            Some("https://www.youtube.com/watch?v=uygf0l_WzjA")
        );
    }

    #[test]
    fn anything_else_is_refused() {
        for key in [
            "",
            "abc&list=x",
            "abc def",
            "abc\n",
            "../x",
            "https://evil.example/x",
            "é",
            &"a".repeat(KEY_MAX + 1),
        ] {
            assert_eq!(trailer_page(key), None, "{key:?}");
        }
        assert_eq!(ur_open_trailer("a b".into()), Err(TrailerError::Invalid));
    }
}
