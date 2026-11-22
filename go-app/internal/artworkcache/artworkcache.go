// Package artworkcache is the disk cache of Emby artwork behind the `emby-img`
// protocol, port of src-tauri/src/emby/artwork_cache.rs. A request names
// one immutable image (its tag changes with the image), so the file name is the
// whole index: size and last use come from the file system.
//
// The layout is the Rust one, so an existing cache folder stays valid:
// `{root}/{serverId}/{itemId}/{Type}-{width}-{tag}.img`.
package artworkcache

import (
	"crypto/rand"
	"encoding/hex"
	"os"
	"path/filepath"
	"slices"
	"strconv"
	"sync"
	"sync/atomic"
	"time"

	"viewer/internal/emby"
)

const (
	// DefaultQuota is the default bound of the cache, all servers together.
	DefaultQuota uint64 = 512 * 1024 * 1024
	// evictToPercent: eviction goes down to this share of the quota, so it does not
	// run on every write.
	evictToPercent = 80
	// touchAfter: a read refreshes the file date at most this often.
	touchAfter = time.Hour
	// staleTemp: temporary files older than this were left by a crash.
	staleTemp = time.Hour
	extension = "img"
)

// Stats is `ArtworkCacheStats`, as the UI reads it.
type Stats struct {
	Entries    uint64 `json:"entries"`
	Bytes      uint64 `json:"bytes"`
	QuotaBytes uint64 `json:"quotaBytes"`
	// Hits and Misses count since the app started.
	Hits   uint64 `json:"hits"`
	Misses uint64 `json:"misses"`
}

// Cleared is `ArtworkCacheCleared`: what Clear removed.
type Cleared struct {
	Entries uint64 `json:"entries"`
	Bytes   uint64 `json:"bytes"`
}

// Cache is safe for concurrent use.
type Cache struct {
	root  string
	quota uint64
	// mu guards size and serializes evictions. size is the bytes on disk, read on
	// first use; sizeKnown is false until then (Rust `Option<u64>`).
	mu        sync.Mutex
	size      uint64
	sizeKnown bool
	hits      atomic.Uint64
	misses    atomic.Uint64
}

// New makes a cache rooted at root, bounded by quota bytes. Nothing is created
// on disk before the first Put.
func New(root string, quota uint64) *Cache {
	return &Cache{root: root, quota: quota}
}

// path is `{root}/{serverId}/{itemId}/{Type}-{width}-{tag}.img`: every part is
// checked by `emby.ParseImageRequest` (letters, digits, `-`, `_`), so no path can
// escape.
func (c *Cache) path(req emby.ImageRequest) string {
	return filepath.Join(c.root, req.ServerID, req.ItemID,
		req.ImageType.String()+"-"+strconv.FormatUint(uint64(req.MaxWidth), 10)+"-"+req.Tag+"."+extension)
}

// Get returns the content type and bytes of a cached image.
func (c *Cache) Get(req emby.ImageRequest) (contentType string, data []byte, ok bool) {
	path := c.path(req)
	if bytes, err := readFile(path); err == nil {
		if ct, isImage := sniff(bytes); isImage {
			touch(path)
			contentType, data, ok = ct, bytes, true
		} else {
			// Not an image we wrote: never served, and its room is given back.
			_ = os.Remove(path)
			c.forgetSize()
		}
	}
	if ok {
		c.hits.Add(1)
	} else {
		c.misses.Add(1)
	}
	return contentType, data, ok
}

// Put keeps a downloaded image and reports whether it was written. What is not an
// image, or is larger than a quarter of the quota, is refused (false, nil).
func (c *Cache) Put(req emby.ImageRequest, data []byte) (bool, error) {
	n := uint64(len(data))
	if _, isImage := sniff(data); !isImage || n > c.quota/4 {
		return false, nil
	}
	path := c.path(req)
	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return false, err
	}
	// Written aside, then renamed: a reader never sees half a file.
	temp, err := tempName(dir)
	if err != nil {
		return false, err
	}
	if err := os.WriteFile(temp, data, 0o644); err != nil {
		return false, err
	}
	var replaced uint64
	if info, err := os.Stat(path); err == nil {
		replaced = uint64(info.Size())
	}
	if err := os.Rename(temp, path); err != nil {
		_ = os.Remove(temp)
		return false, err
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	var total uint64
	if c.sizeKnown {
		total = saturatingSub(c.size, replaced) + n
	} else {
		// First write since the start: the scan already counts this file.
		_, total = c.scan()
	}
	if total > c.quota {
		total = c.evict()
	}
	c.size, c.sizeKnown = total, true
	return true, nil
}

// Stats reads the entries and bytes from the disk.
func (c *Cache) Stats() Stats {
	entries, bytes := c.scan()
	return Stats{
		Entries:    entries,
		Bytes:      bytes,
		QuotaBytes: c.quota,
		Hits:       c.hits.Load(),
		Misses:     c.misses.Load(),
	}
}

// Clear removes every cached image of every server, then the empty folders; files
// that are not ours stay. It returns the images and bytes removed.
func (c *Cache) Clear() Cleared {
	// Held throughout: no write counts its size against a half-emptied cache.
	c.mu.Lock()
	defer c.mu.Unlock()
	var out Cleared
	for _, f := range c.files() {
		if os.Remove(f.path) == nil {
			out.Entries++
			out.Bytes += f.size
		}
	}
	c.removeEmptyDirs()
	// Read again on the next write: an image kept meanwhile still counts.
	c.sizeKnown = false
	return out
}

func (c *Cache) forgetSize() {
	c.mu.Lock()
	c.sizeKnown = false
	c.mu.Unlock()
}

// scan is the number of images and their bytes.
func (c *Cache) scan() (entries, bytes uint64) {
	files := c.files()
	for _, f := range files {
		bytes += f.size
	}
	return uint64(len(files)), bytes
}

type cachedFile struct {
	path     string
	size     uint64
	modified time.Time
}

// files lists the cached images (path, bytes, last use); it removes temporary
// files left by a crash on the way.
func (c *Cache) files() []cachedFile {
	var files []cachedFile
	for _, serverDir := range subdirs(c.root) {
		for _, itemDir := range subdirs(serverDir) {
			entries, err := os.ReadDir(itemDir)
			if err != nil {
				continue
			}
			for _, entry := range entries {
				path := filepath.Join(itemDir, entry.Name())
				info, err := entry.Info()
				if err != nil {
					continue
				}
				modified := info.ModTime()
				switch ext := extensionOf(entry.Name()); {
				case ext == "tmp":
					if time.Since(modified) > staleTemp {
						_ = os.Remove(path)
					}
				case info.Mode().IsRegular() && ext == extension:
					files = append(files, cachedFile{path, uint64(info.Size()), modified})
				}
			}
		}
	}
	return files
}

// evict removes the least recently used images down to evictToPercent of the
// quota, then the empty folders; it returns the bytes left. The caller holds mu.
func (c *Cache) evict() uint64 {
	files := c.files()
	var total uint64
	for _, f := range files {
		total += f.size
	}
	target := c.quota / 100 * evictToPercent
	slices.SortStableFunc(files, func(a, b cachedFile) int { return a.modified.Compare(b.modified) })
	for _, f := range files {
		if total <= target {
			break
		}
		if os.Remove(f.path) == nil {
			total -= f.size
		}
	}
	c.removeEmptyDirs()
	return total
}

func (c *Cache) removeEmptyDirs() {
	for _, serverDir := range subdirs(c.root) {
		for _, itemDir := range subdirs(serverDir) {
			// Fails while the folder still holds files, which is the point.
			_ = os.Remove(itemDir)
		}
		_ = os.Remove(serverDir)
	}
}

// subdirs lists the sub-folders of dir; an unreadable dir has none.
func subdirs(dir string) []string {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return nil
	}
	var out []string
	for _, e := range entries {
		if e.IsDir() {
			out = append(out, filepath.Join(dir, e.Name()))
		}
	}
	return out
}

// extensionOf is Rust's `Path::extension` on a file name: the part after the last
// dot, unless the dot starts the name (".tmp" has none).
func extensionOf(name string) string {
	for i := len(name) - 1; i > 0; i-- {
		if name[i] == '.' {
			return name[i+1:]
		}
	}
	return ""
}

func tempName(dir string) (string, error) {
	var id [16]byte
	if _, err := rand.Read(id[:]); err != nil {
		return "", err
	}
	return filepath.Join(dir, "."+hex.EncodeToString(id[:])+".tmp"), nil
}

func saturatingSub(a, b uint64) uint64 {
	if a < b {
		return 0
	}
	return a - b
}

// touch marks a read image as recently used, at most once per touchAfter.
func touch(path string) {
	info, err := os.Stat(path)
	if err != nil || time.Since(info.ModTime()) <= touchAfter {
		return
	}
	// A zero access time leaves it unchanged: only the modification date moves.
	_ = os.Chtimes(path, time.Time{}, time.Now())
}

// sniff gives the content type from the first bytes: only the formats Emby serves.
func sniff(b []byte) (string, bool) {
	switch {
	case len(b) >= 3 && b[0] == 0xFF && b[1] == 0xD8 && b[2] == 0xFF:
		return "image/jpeg", true
	case len(b) >= 8 && string(b[:8]) == "\x89PNG\r\n\x1a\n":
		return "image/png", true
	case len(b) >= 4 && string(b[:4]) == "GIF8":
		return "image/gif", true
	case len(b) >= 12 && string(b[:4]) == "RIFF" && string(b[8:12]) == "WEBP":
		return "image/webp", true
	}
	return "", false
}
