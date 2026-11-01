//! Disk cache of Emby artwork behind the `emby-img` protocol (ADR 0019). A request
//! names one immutable image (its tag changes with the image), so the file name is
//! the whole index: size and last use come from the file system.

use std::fs;
use std::io;
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{Duration, SystemTime};

use serde::Serialize;

use super::images::ImageRequest;

/// Default bound of the cache, all servers together.
pub const DEFAULT_QUOTA: u64 = 512 * 1024 * 1024;
/// Eviction goes down to this share of the quota, so it does not run on every write.
const EVICT_TO_PERCENT: u64 = 80;
/// A read refreshes the file date at most this often.
const TOUCH_AFTER: Duration = Duration::from_secs(3600);
/// Temporary files older than this were left by a crash.
const STALE_TEMP: Duration = Duration::from_secs(3600);
const EXTENSION: &str = "img";

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ArtworkCacheStats {
    pub entries: u64,
    pub bytes: u64,
    pub quota_bytes: u64,
    /// Since the app started.
    pub hits: u64,
    pub misses: u64,
}

/// What `ArtworkCache::clear` removed.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ArtworkCacheCleared {
    pub entries: u64,
    pub bytes: u64,
}

pub struct ArtworkCache {
    root: PathBuf,
    quota: u64,
    /// Bytes on disk, read on first use; also serializes evictions.
    size: Mutex<Option<u64>>,
    hits: AtomicU64,
    misses: AtomicU64,
}

impl ArtworkCache {
    pub fn new(root: PathBuf, quota: u64) -> Self {
        Self {
            root,
            quota,
            size: Mutex::new(None),
            hits: AtomicU64::new(0),
            misses: AtomicU64::new(0),
        }
    }

    /// `{root}/{serverId}/{itemId}/{Type}-{width}-{tag}.img`: every part is checked by
    /// `ImageRequest::parse` (letters, digits, `-`, `_`), so no path can escape.
    fn path(&self, req: &ImageRequest) -> PathBuf {
        self.root
            .join(&req.server_id)
            .join(&req.item_id)
            .join(format!(
                "{}-{}-{}.{EXTENSION}",
                req.image_type.as_str(),
                req.max_width,
                req.tag
            ))
    }

    /// Content type and bytes of a cached image.
    pub fn get(&self, req: &ImageRequest) -> Option<(String, Vec<u8>)> {
        let path = self.path(req);
        let found = fs::read(&path).ok().and_then(|bytes| {
            let Some(content_type) = sniff(&bytes) else {
                // Not an image we wrote: never served, and its room is given back.
                let _ = fs::remove_file(&path);
                self.forget_size();
                return None;
            };
            touch(&path);
            Some((content_type.to_owned(), bytes))
        });
        let counter = if found.is_some() {
            &self.hits
        } else {
            &self.misses
        };
        counter.fetch_add(1, Ordering::Relaxed);
        found
    }

    /// Keeps a downloaded image; returns whether it was written.
    pub fn put(&self, req: &ImageRequest, bytes: &[u8]) -> io::Result<bool> {
        let len = bytes.len() as u64;
        if sniff(bytes).is_none() || len > self.quota / 4 {
            return Ok(false);
        }
        let path = self.path(req);
        let dir = path.parent().unwrap_or(&self.root);
        fs::create_dir_all(dir)?;
        // Written aside, then renamed: a reader never sees half a file.
        let temp = dir.join(format!(".{}.tmp", uuid::Uuid::new_v4().simple()));
        fs::write(&temp, bytes)?;
        let replaced = fs::metadata(&path).map(|m| m.len()).unwrap_or(0);
        if let Err(e) = fs::rename(&temp, &path) {
            let _ = fs::remove_file(&temp);
            return Err(e);
        }
        let mut size = self.size.lock().unwrap_or_else(|p| p.into_inner());
        let total = match *size {
            Some(before) => before.saturating_sub(replaced) + len,
            // First write since the start: the scan already counts this file.
            None => self.scan().1,
        };
        *size = Some(if total > self.quota {
            self.evict()
        } else {
            total
        });
        Ok(true)
    }

    pub fn stats(&self) -> ArtworkCacheStats {
        let (entries, bytes) = self.scan();
        ArtworkCacheStats {
            entries,
            bytes,
            quota_bytes: self.quota,
            hits: self.hits.load(Ordering::Relaxed),
            misses: self.misses.load(Ordering::Relaxed),
        }
    }

    /// Removes every cached image of every server, then the empty folders; files
    /// that are not ours stay. Returns the images and bytes removed (ADR 0021).
    pub fn clear(&self) -> (u64, u64) {
        // Held throughout: no write counts its size against a half-emptied cache.
        let mut size = self.size.lock().unwrap_or_else(|p| p.into_inner());
        let (mut entries, mut bytes) = (0, 0);
        for (path, len, _) in self.files() {
            if fs::remove_file(&path).is_ok() {
                entries += 1;
                bytes += len;
            }
        }
        self.remove_empty_dirs();
        // Read again on the next write: an image kept meanwhile still counts.
        *size = None;
        (entries, bytes)
    }

    fn forget_size(&self) {
        *self.size.lock().unwrap_or_else(|p| p.into_inner()) = None;
    }

    /// Number of images and their bytes.
    fn scan(&self) -> (u64, u64) {
        let files = self.files();
        (files.len() as u64, files.iter().map(|f| f.1).sum())
    }

    /// Cached images as (path, bytes, last use); removes temporary files left by a
    /// crash on the way.
    fn files(&self) -> Vec<(PathBuf, u64, SystemTime)> {
        let mut files = Vec::new();
        for item_dir in subdirs(&self.root).iter().flat_map(|d| subdirs(d)) {
            let Ok(entries) = fs::read_dir(&item_dir) else {
                continue;
            };
            for entry in entries.flatten() {
                let path = entry.path();
                let Ok(meta) = entry.metadata() else { continue };
                let modified = meta.modified().unwrap_or(SystemTime::UNIX_EPOCH);
                if is_temp(&path) {
                    if modified.elapsed().is_ok_and(|age| age > STALE_TEMP) {
                        let _ = fs::remove_file(&path);
                    }
                } else if meta.is_file() && path.extension().is_some_and(|e| e == EXTENSION) {
                    files.push((path, meta.len(), modified));
                }
            }
        }
        files
    }

    /// Removes the least recently used images down to `EVICT_TO_PERCENT` of the
    /// quota, then the empty folders; returns the bytes left.
    fn evict(&self) -> u64 {
        let mut files = self.files();
        let mut total: u64 = files.iter().map(|f| f.1).sum();
        let target = self.quota / 100 * EVICT_TO_PERCENT;
        files.sort_by_key(|f| f.2);
        for (path, len, _) in files {
            if total <= target {
                break;
            }
            if fs::remove_file(&path).is_ok() {
                total -= len;
            }
        }
        self.remove_empty_dirs();
        total
    }

    fn remove_empty_dirs(&self) {
        for server_dir in subdirs(&self.root) {
            for item_dir in subdirs(&server_dir) {
                // Fails while the folder still holds files, which is the point.
                let _ = fs::remove_dir(&item_dir);
            }
            let _ = fs::remove_dir(&server_dir);
        }
    }
}

fn subdirs(dir: &Path) -> Vec<PathBuf> {
    fs::read_dir(dir)
        .map(|entries| {
            entries
                .flatten()
                .filter(|e| e.file_type().is_ok_and(|t| t.is_dir()))
                .map(|e| e.path())
                .collect()
        })
        .unwrap_or_default()
}

fn is_temp(path: &Path) -> bool {
    path.extension().is_some_and(|e| e == "tmp")
}

/// Marks a read image as recently used, at most once per `TOUCH_AFTER`.
fn touch(path: &Path) {
    let stale = fs::metadata(path)
        .and_then(|m| m.modified())
        .ok()
        .and_then(|t| t.elapsed().ok())
        .is_some_and(|age| age > TOUCH_AFTER);
    if stale {
        let _ = fs::File::options()
            .write(true)
            .open(path)
            .and_then(|f| f.set_modified(SystemTime::now()));
    }
}

/// Content type from the first bytes: only the formats Emby serves.
fn sniff(bytes: &[u8]) -> Option<&'static str> {
    match bytes {
        [0xFF, 0xD8, 0xFF, ..] => Some("image/jpeg"),
        [0x89, b'P', b'N', b'G', 0x0D, 0x0A, 0x1A, 0x0A, ..] => Some("image/png"),
        [b'G', b'I', b'F', b'8', ..] => Some("image/gif"),
        [
            b'R',
            b'I',
            b'F',
            b'F',
            _,
            _,
            _,
            _,
            b'W',
            b'E',
            b'B',
            b'P',
            ..,
        ] => Some("image/webp"),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const JPEG: &[u8] = &[0xFF, 0xD8, 0xFF, 0xE0, 1, 2, 3, 4];

    fn root() -> PathBuf {
        std::env::temp_dir().join(format!("viewer-artwork-{}", uuid::Uuid::new_v4()))
    }

    fn req(item: &str, width: u32) -> ImageRequest {
        ImageRequest::parse(
            &format!("/srv1/{item}/Primary"),
            Some(&format!("tag=ab12_6384&maxWidth={width}")),
        )
        .unwrap()
    }

    fn age(cache: &ArtworkCache, req: &ImageRequest, secs: u64) {
        fs::File::options()
            .write(true)
            .open(cache.path(req))
            .unwrap()
            .set_modified(SystemTime::now() - Duration::from_secs(secs))
            .unwrap();
    }

    #[test]
    fn keeps_and_serves_images_by_request() {
        let cache = ArtworkCache::new(root(), DEFAULT_QUOTA);
        assert_eq!(cache.get(&req("1", 360)), None);
        assert!(cache.put(&req("1", 360), JPEG).unwrap());
        assert_eq!(
            cache.get(&req("1", 360)),
            Some(("image/jpeg".into(), JPEG.to_vec()))
        );
        // Another width is another image.
        assert_eq!(cache.get(&req("1", 480)), None);
        assert_eq!(
            cache.path(&req("1", 360)),
            cache
                .root
                .join("srv1")
                .join("1")
                .join("Primary-360-ab12_6384.img")
        );
        let stats = cache.stats();
        assert_eq!(
            (stats.entries, stats.bytes, stats.hits, stats.misses),
            (1, JPEG.len() as u64, 1, 2)
        );
    }

    #[test]
    fn refuses_what_is_not_an_image() {
        let cache = ArtworkCache::new(root(), DEFAULT_QUOTA);
        assert!(
            !cache
                .put(&req("1", 360), b"<html>pas une image</html>")
                .unwrap()
        );
        assert_eq!(cache.stats().entries, 0);
        // A file changed on disk is dropped, not served.
        cache.put(&req("2", 360), JPEG).unwrap();
        fs::write(cache.path(&req("2", 360)), b"garbage").unwrap();
        assert_eq!(cache.get(&req("2", 360)), None);
        assert!(!cache.path(&req("2", 360)).exists());
    }

    #[test]
    fn recognizes_the_formats_emby_serves() {
        assert_eq!(sniff(JPEG), Some("image/jpeg"));
        assert_eq!(sniff(b"\x89PNG\r\n\x1a\n...."), Some("image/png"));
        assert_eq!(sniff(b"GIF89a"), Some("image/gif"));
        assert_eq!(sniff(b"RIFF\0\0\0\0WEBPVP8 "), Some("image/webp"));
        assert_eq!(sniff(b"RIFF\0\0\0\0WAVE"), None);
        assert_eq!(sniff(b""), None);
    }

    #[test]
    fn quota_drops_the_least_recently_used() {
        let image = |n: u8| {
            let mut bytes = JPEG.to_vec();
            bytes.resize(100, n);
            bytes
        };
        // Room for 4 images of 100 bytes; eviction goes down to 80 % (3 images).
        let cache = ArtworkCache::new(root(), 400);
        for (item, secs) in [("1", 4000), ("2", 9000), ("3", 6000), ("4", 5000)] {
            cache.put(&req(item, 360), &image(1)).unwrap();
            age(&cache, &req(item, 360), secs);
        }
        // Reading "2" makes it recent: "3" becomes the oldest.
        assert!(cache.get(&req("2", 360)).is_some());
        assert!(cache.put(&req("5", 360), &image(2)).unwrap());
        let stats = cache.stats();
        assert_eq!((stats.entries, stats.bytes), (3, 300));
        for (item, kept) in [
            ("1", true),
            ("2", true),
            ("3", false),
            ("4", false),
            ("5", true),
        ] {
            assert_eq!(cache.path(&req(item, 360)).exists(), kept, "{item}");
        }
        assert!(!cache.root.join("srv1").join("3").exists());
        // Too large for the quota: not kept at all.
        let mut large = image(3);
        large.push(0);
        assert!(!cache.put(&req("6", 360), &large).unwrap());
    }

    #[test]
    fn clear_removes_every_image_and_keeps_other_files() {
        let dir = root();
        let cache = ArtworkCache::new(dir.clone(), DEFAULT_QUOTA);
        cache.put(&req("1", 360), JPEG).unwrap();
        cache.put(&req("2", 360), JPEG).unwrap();
        let other = ImageRequest::parse("/srv2/9/Backdrop", Some("tag=cd_1&maxWidth=720")).unwrap();
        cache.put(&other, JPEG).unwrap();
        // Not ours: never removed.
        let foreign = dir.join("srv1").join("1").join("notes.txt");
        fs::write(&foreign, b"x").unwrap();

        assert_eq!(cache.clear(), (3, 3 * JPEG.len() as u64));
        assert_eq!(cache.get(&req("1", 360)), None);
        assert!(foreign.is_file());
        assert!(
            !dir.join("srv1").join("2").exists(),
            "empty folders removed"
        );
        assert!(!dir.join("srv2").exists());
        assert_eq!((cache.stats().entries, cache.stats().bytes), (0, 0));

        // Still usable, and its size is counted again from the disk.
        assert!(cache.put(&req("1", 360), JPEG).unwrap());
        assert_eq!(cache.stats().entries, 1);
        assert_eq!(cache.clear(), (1, JPEG.len() as u64));
        assert_eq!(cache.clear(), (0, 0), "nothing left");
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn stale_temporary_files_are_removed() {
        let cache = ArtworkCache::new(root(), DEFAULT_QUOTA);
        cache.put(&req("1", 360), JPEG).unwrap();
        let dir = cache.root.join("srv1").join("1");
        let (old, fresh) = (dir.join(".old.tmp"), dir.join(".fresh.tmp"));
        fs::write(&old, JPEG).unwrap();
        fs::write(&fresh, JPEG).unwrap();
        fs::File::options()
            .write(true)
            .open(&old)
            .unwrap()
            .set_modified(SystemTime::now() - Duration::from_secs(7200))
            .unwrap();
        assert_eq!(cache.stats().entries, 1);
        assert!(!old.exists() && fresh.exists());
    }
}
