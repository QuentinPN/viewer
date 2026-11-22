package request

// Request artwork through the local `requestimg` protocol, port of
// src-tauri/src/request/images.rs. Models sent to the UI carry ready-made
// ImageRefs; Go fetches the image with the Bearer token, so the WebView never sees a
// token nor the server address.

import (
	"strconv"
	"strings"
	"sync/atomic"

	"viewer/internal/emby"
)

// Scheme is the protocol name; WebView2 on Windows serves it as
// `http://requestimg.localhost/`.
const Scheme = "requestimg"

// TauriOrigin is where WebView2 serves the `requestimg` protocol of the Tauri build,
// and the default origin of every URL built here.
const TauriOrigin = "http://requestimg.localhost"

// imageOrigin is the origin of the URLs imageRef writes. Tauri keeps the default; the
// Wails build, whose WebView only routes `http://wails.localhost` to Go, sets its own
// once with SetImageOrigin.
var imageOrigin atomic.Pointer[string]

// SetImageOrigin changes the origin (scheme and host, optionally a path prefix, no
// trailing slash) of every artwork URL built afterwards. Call it once at startup,
// before any service runs: URLs already handed to the UI keep their origin.
func SetImageOrigin(o string) { imageOrigin.Store(&o) }

func currentOrigin() string {
	if o := imageOrigin.Load(); o != nil {
		return *o
	}
	return TauriOrigin
}

// sizes the protocol accepts (`original` is never asked: unbounded size).
var sizes = [...]string{"w185", "w342", "w500", "w780", "w1280"}

var (
	posterSizes   = []string{"w185", "w342", "w500"}
	backdropSizes = []string{"w780", "w1280"}
	profileSizes  = []string{"w185"}
)

// extensions: raster formats only, an SVG could carry scripts.
var extensions = [...]string{"jpg", "jpeg", "png", "webp"}

const stemMax = 64

// cacheScope is the folder of the shared artwork cache holding Request images.
const cacheScope = "request"

// UrImage is one validated image request coming from the WebView: `/{size}/{file}`.
type UrImage struct {
	Size string
	File string
}

// ParseUrImage is `UrImage::parse`; false for anything else.
func ParseUrImage(path string) (UrImage, bool) {
	parts := strings.Split(strings.TrimLeft(path, "/"), "/")
	if len(parts) != 2 {
		return UrImage{}, false
	}
	size, file := parts[0], parts[1]
	known := false
	for _, s := range sizes {
		known = known || s == size
	}
	if !known || !isFile(file) {
		return UrImage{}, false
	}
	return UrImage{Size: size, File: file}, true
}

// CacheKey is the key in the shared artwork cache:
// `request/{stem}/Primary-{width}-{ext}`.
func (r UrImage) CacheKey() emby.ImageRequest {
	stem, ext := r.File, ""
	if i := strings.LastIndexByte(r.File, '.'); i >= 0 {
		stem, ext = r.File[:i], r.File[i+1:]
	}
	return emby.ImageRequest{
		ServerID:  cacheScope,
		ItemID:    stem,
		ImageType: emby.ImagePrimary,
		Tag:       ext,
		MaxWidth:  width(r.Size),
	}
}

func width(size string) uint32 {
	n, err := strconv.ParseUint(strings.TrimLeft(size, "w"), 10, 32)
	if err != nil {
		return 0
	}
	return uint32(n)
}

// isFile: `[A-Za-z0-9_-]{1,64}` then a raster extension.
func isFile(file string) bool {
	i := strings.LastIndexByte(file, '.')
	if i < 0 {
		return false
	}
	stem, ext := file[:i], file[i+1:]
	if stem == "" || len(stem) > stemMax {
		return false
	}
	for j := 0; j < len(stem); j++ {
		c := stem[j]
		if !((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-') {
			return false
		}
	}
	for _, e := range extensions {
		if e == ext {
			return true
		}
	}
	return false
}

// imageRef is `image_ref`: `src`/`srcset` for a TMDB path such as `/abc.jpg`, or nil
// when the path is missing or not a plain image file name.
func imageRef(path *string, sizes []string) *emby.ImageRef {
	return imageRefAt(currentOrigin(), path, sizes)
}

// imageRefAt is imageRef on an explicit origin.
func imageRefAt(origin string, path *string, sizes []string) *emby.ImageRef {
	if path == nil || len(sizes) == 0 {
		return nil
	}
	file, ok := strings.CutPrefix(*path, "/")
	if !ok || !isFile(file) {
		return nil
	}
	url := func(size string) string { return origin + "/" + size + "/" + file }
	set := make([]string, len(sizes))
	for i, s := range sizes {
		set[i] = url(s) + " " + strconv.FormatUint(uint64(width(s)), 10) + "w"
	}
	return &emby.ImageRef{Src: url(sizes[0]), Srcset: strings.Join(set, ", ")}
}

// decorator is `Decorate`: fills the ImageRefs of a model (and of the models it
// holds) from its paths.
type decorator interface{ decorate() }

func decorateAll[T any, P interface {
	*T
	decorator
}](items []T) {
	for i := range items {
		P(&items[i]).decorate()
	}
}

func (m *MediaSummary) decorate() {
	m.Poster = imageRef(m.PosterPath, posterSizes)
	m.Backdrop = imageRef(m.BackdropPath, backdropSizes)
}

func (c *Collection) decorate() {
	c.Poster = imageRef(c.PosterPath, posterSizes)
	c.Backdrop = imageRef(c.BackdropPath, backdropSizes)
}

func (c *CastMember) decorate() { c.Profile = imageRef(c.ProfilePath, profileSizes) }

func (c *CrewMember) decorate() { c.Profile = imageRef(c.ProfilePath, profileSizes) }

func (p *SearchPerson) decorate() { p.Profile = imageRef(p.ProfilePath, profileSizes) }

func (m *MediaDetails) decorate() {
	m.Poster = imageRef(m.PosterPath, posterSizes)
	m.Backdrop = imageRef(m.BackdropPath, backdropSizes)
	decorateAll(m.Cast)
	if m.Crew != nil {
		decorateAll(*m.Crew)
	}
	if m.Collection != nil {
		m.Collection.decorate()
	}
	decorateAll(m.Recommendations)
	decorateAll(m.Similar)
}

func (p *PagedMedia) decorate() { decorateAll(p.Results) }

func (r *MediaSearchResults) decorate() {
	decorateAll(r.Results)
	decorateAll(r.People)
}

func (d *DiscoverResponse) decorate() {
	for i := range d.Rows {
		decorateAll(d.Rows[i].Items)
	}
}

func (v *RequestView) decorate() {
	if v.Media != nil {
		v.Media.decorate()
	}
}

func (d *RequestDetail) decorate() { d.RequestView.decorate() }

func (p *RequestPage) decorate() { decorateAll(p.Results) }

// decorated is `decorated`: the model with its ImageRefs filled.
func decorated[T any, P interface {
	*T
	decorator
}](v T) T {
	P(&v).decorate()
	return v
}
