//! Request artwork through the local `requestimg` protocol (ADR 0125). Models
//! sent to the UI carry ready-made `ImageRef`s; Rust fetches the image with the
//! Bearer token, so the WebView never sees a token nor the server address.

use super::models::{
    CastMember, Collection, CrewMember, DiscoverResponse, MediaDetails, MediaSearchResults,
    MediaSummary, PagedMedia, RequestDetail, RequestPage, RequestView, SearchPerson,
};
use crate::emby::images::ImageType;
use crate::emby::{images::ImageRequest, models::ImageRef};

/// Registered with Tauri; WebView2 on Windows serves it as `http://requestimg.localhost/`.
pub const SCHEME: &str = "requestimg";
const ORIGIN: &str = "http://requestimg.localhost";
/// Sizes the protocol accepts (`original` is never asked: unbounded size).
const SIZES: [&str; 5] = ["w185", "w342", "w500", "w780", "w1280"];
const POSTER_SIZES: &[&str] = &["w185", "w342", "w500"];
const BACKDROP_SIZES: &[&str] = &["w780", "w1280"];
const PROFILE_SIZES: &[&str] = &["w185"];
/// Raster formats only: an SVG could carry scripts.
const EXTENSIONS: [&str; 4] = ["jpg", "jpeg", "png", "webp"];
const STEM_MAX: usize = 64;
/// Folder of the shared artwork cache holding Request images.
const CACHE_SCOPE: &str = "request";

/// One validated image request coming from the WebView: `/{size}/{file}`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct UrImage {
    pub size: &'static str,
    pub file: String,
}

impl UrImage {
    pub fn parse(path: &str) -> Option<Self> {
        let mut parts = path.trim_start_matches('/').split('/');
        let (Some(size), Some(file), None) = (parts.next(), parts.next(), parts.next()) else {
            return None;
        };
        let size = SIZES.into_iter().find(|s| *s == size)?;
        is_file(file).then(|| Self {
            size,
            file: file.to_owned(),
        })
    }

    /// Key in the shared artwork cache (ADR 0019): `request/{stem}/…-{width}-{ext}`.
    pub fn cache_key(&self) -> ImageRequest {
        let (stem, ext) = self.file.rsplit_once('.').unwrap_or((&self.file, ""));
        ImageRequest {
            server_id: CACHE_SCOPE.to_owned(),
            item_id: stem.to_owned(),
            image_type: ImageType::Primary,
            tag: ext.to_owned(),
            max_width: width(self.size),
        }
    }
}

fn width(size: &str) -> u32 {
    size.trim_start_matches('w').parse().unwrap_or(0)
}

/// `[A-Za-z0-9_-]{1,64}` then a raster extension.
fn is_file(file: &str) -> bool {
    let Some((stem, ext)) = file.rsplit_once('.') else {
        return false;
    };
    !stem.is_empty()
        && stem.len() <= STEM_MAX
        && stem
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-')
        && EXTENSIONS.contains(&ext)
}

/// `src`/`srcset` for a TMDB path such as `/abc.jpg`, or `None` when the path is
/// missing or not a plain image file name.
pub fn image_ref(path: Option<&str>, sizes: &[&str]) -> Option<ImageRef> {
    let file = path?.strip_prefix('/')?;
    if !is_file(file) || sizes.is_empty() {
        return None;
    }
    let url = |size: &str| format!("{ORIGIN}/{size}/{file}");
    Some(ImageRef {
        src: url(sizes[0]),
        srcset: sizes
            .iter()
            .map(|s| format!("{} {}w", url(s), width(s)))
            .collect::<Vec<_>>()
            .join(", "),
    })
}

/// Fills the `ImageRef`s of a model (and of the models it holds) from its paths.
pub trait Decorate {
    fn decorate(&mut self);
}

impl<T: Decorate> Decorate for Vec<T> {
    fn decorate(&mut self) {
        self.iter_mut().for_each(Decorate::decorate);
    }
}

impl<T: Decorate> Decorate for Option<T> {
    fn decorate(&mut self) {
        if let Some(v) = self {
            v.decorate();
        }
    }
}

impl Decorate for MediaSummary {
    fn decorate(&mut self) {
        self.poster = image_ref(self.poster_path.as_deref(), POSTER_SIZES);
        self.backdrop = image_ref(self.backdrop_path.as_deref(), BACKDROP_SIZES);
    }
}

impl Decorate for Collection {
    fn decorate(&mut self) {
        self.poster = image_ref(self.poster_path.as_deref(), POSTER_SIZES);
        self.backdrop = image_ref(self.backdrop_path.as_deref(), BACKDROP_SIZES);
    }
}

impl Decorate for CastMember {
    fn decorate(&mut self) {
        self.profile = image_ref(self.profile_path.as_deref(), PROFILE_SIZES);
    }
}

impl Decorate for CrewMember {
    fn decorate(&mut self) {
        self.profile = image_ref(self.profile_path.as_deref(), PROFILE_SIZES);
    }
}

impl Decorate for SearchPerson {
    fn decorate(&mut self) {
        self.profile = image_ref(self.profile_path.as_deref(), PROFILE_SIZES);
    }
}

impl Decorate for MediaDetails {
    fn decorate(&mut self) {
        self.poster = image_ref(self.poster_path.as_deref(), POSTER_SIZES);
        self.backdrop = image_ref(self.backdrop_path.as_deref(), BACKDROP_SIZES);
        self.cast.decorate();
        self.crew.decorate();
        self.collection.decorate();
        self.recommendations.decorate();
        self.similar.decorate();
    }
}

impl Decorate for PagedMedia {
    fn decorate(&mut self) {
        self.results.decorate();
    }
}

impl Decorate for MediaSearchResults {
    fn decorate(&mut self) {
        self.results.decorate();
        self.people.decorate();
    }
}

impl Decorate for DiscoverResponse {
    fn decorate(&mut self) {
        for row in &mut self.rows {
            row.items.decorate();
        }
    }
}

impl Decorate for RequestView {
    fn decorate(&mut self) {
        self.media.decorate();
    }
}

impl Decorate for RequestDetail {
    fn decorate(&mut self) {
        self.request.decorate();
    }
}

impl Decorate for RequestPage {
    fn decorate(&mut self) {
        self.results.decorate();
    }
}

/// Returns the model with its `ImageRef`s filled.
pub fn decorated<T: Decorate>(mut value: T) -> T {
    value.decorate();
    value
}
