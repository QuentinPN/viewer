//! Emby artwork through the local `emby-img` protocol (ADR 0005). The UI gets ready
//! made URLs on that protocol; Rust fetches the image with the session's auth header.

use super::models::ImageRef;

/// Registered with Tauri; WebView2 on Windows serves it as `http://emby-img.localhost/`.
pub const SCHEME: &str = "emby-img";
const ORIGIN: &str = "http://emby-img.localhost";
const MIN_WIDTH: u32 = 32;
const MAX_WIDTH: u32 = 1920;
/// Larger answers are refused: Emby resizes to `maxWidth`, so this is never legit.
pub const MAX_BYTES: usize = 10 * 1024 * 1024;

/// Poster widths offered in `srcset`; the WebView picks one for the density.
pub const POSTER_WIDTHS: &[u32] = &[240, 360, 480];
pub const LIBRARY_WIDTHS: &[u32] = &[400, 640, 800];

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ImageType {
    Primary,
    Backdrop,
    Thumb,
    Logo,
}

impl ImageType {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Primary => "Primary",
            Self::Backdrop => "Backdrop",
            Self::Thumb => "Thumb",
            Self::Logo => "Logo",
        }
    }

    fn parse(value: &str) -> Option<Self> {
        [Self::Primary, Self::Backdrop, Self::Thumb, Self::Logo]
            .into_iter()
            .find(|t| t.as_str() == value)
    }
}

/// One validated artwork request coming from the WebView.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ImageRequest {
    pub item_id: String,
    pub image_type: ImageType,
    pub tag: String,
    pub max_width: u32,
}

impl ImageRequest {
    /// Parses `/{itemId}/{Type}?tag=…&maxWidth=…`. Anything else is refused, so the
    /// protocol cannot be used to reach other server endpoints.
    pub fn parse(path: &str, query: Option<&str>) -> Option<Self> {
        let mut parts = path.trim_start_matches('/').split('/');
        let (Some(item_id), Some(kind), None) = (parts.next(), parts.next(), parts.next()) else {
            return None;
        };
        if !is_id(item_id) {
            return None;
        }
        let image_type = ImageType::parse(kind)?;
        let (mut tag, mut max_width) = (None, None);
        for pair in query
            .unwrap_or_default()
            .split('&')
            .filter(|p| !p.is_empty())
        {
            match pair.split_once('=')? {
                ("tag", v) if is_tag(v) => tag = Some(v.to_owned()),
                ("maxWidth", v) => max_width = v.parse::<u32>().ok(),
                _ => return None,
            }
        }
        let max_width = max_width.filter(|w| (MIN_WIDTH..=MAX_WIDTH).contains(w))?;
        Some(Self {
            item_id: item_id.to_owned(),
            image_type,
            tag: tag?,
            max_width,
        })
    }

    /// Path on the Emby API (`/Items/{Id}/Images/{Type}`).
    pub fn api_path(&self) -> String {
        format!(
            "/Items/{}/Images/{}",
            self.item_id,
            self.image_type.as_str()
        )
    }
}

/// `src`/`srcset` for an item image, or `None` when the item has no such image: a
/// missing image still answers 200 with a generic picture, so `ImageTags` decides.
pub fn image_ref(
    item_id: &str,
    image_type: ImageType,
    tag: Option<&str>,
    widths: &[u32],
) -> Option<ImageRef> {
    let tag = tag.filter(|t| is_tag(t))?;
    if !is_id(item_id) || widths.is_empty() {
        return None;
    }
    let url = |w: u32| {
        format!(
            "{ORIGIN}/{item_id}/{}?tag={tag}&maxWidth={w}",
            image_type.as_str()
        )
    };
    Some(ImageRef {
        src: url(widths[0]),
        srcset: widths
            .iter()
            .map(|&w| format!("{} {w}w", url(w)))
            .collect::<Vec<_>>()
            .join(", "),
    })
}

fn is_id(v: &str) -> bool {
    !v.is_empty() && v.len() <= 64 && v.chars().all(|c| c.is_ascii_alphanumeric() || c == '-')
}

fn is_tag(v: &str) -> bool {
    !v.is_empty()
        && v.len() <= 128
        && v.chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-')
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn builds_srcset_from_image_tags() {
        let r = image_ref("74668", ImageType::Primary, Some("ab12_6384"), &[240, 480]).unwrap();
        assert_eq!(
            r.src,
            "http://emby-img.localhost/74668/Primary?tag=ab12_6384&maxWidth=240"
        );
        assert_eq!(
            r.srcset,
            "http://emby-img.localhost/74668/Primary?tag=ab12_6384&maxWidth=240 240w, \
             http://emby-img.localhost/74668/Primary?tag=ab12_6384&maxWidth=480 480w"
        );
        assert_eq!(image_ref("1", ImageType::Primary, None, &[240]), None);
        assert_eq!(
            image_ref("1", ImageType::Primary, Some("a\"b"), &[240]),
            None
        );
    }

    #[test]
    fn parses_only_well_formed_requests() {
        let ok = ImageRequest::parse("/74668/Primary", Some("tag=ab12_6384&maxWidth=360")).unwrap();
        assert_eq!(ok.api_path(), "/Items/74668/Images/Primary");
        assert_eq!(ok.max_width, 360);

        for (path, query) in [
            ("/74668/Primary", Some("tag=x")),               // no width
            ("/74668/Primary", Some("maxWidth=360")),        // no tag
            ("/74668/Primary", Some("tag=x&maxWidth=8000")), // too large
            ("/74668/Primary", Some("tag=x&maxWidth=360&api_key=y")), // extra param
            ("/74668/Chapter", Some("tag=x&maxWidth=360")),  // type not allowed
            ("/../Users/Primary", Some("tag=x&maxWidth=360")), // traversal
            ("/74668/Primary/0", Some("tag=x&maxWidth=360")), // extra segment
            ("/74668/Primary", Some("tag=a%2Fb&maxWidth=360")), // encoded chars
            ("/74668/Primary", None),
        ] {
            assert_eq!(ImageRequest::parse(path, query), None, "{path}?{query:?}");
        }
    }
}
