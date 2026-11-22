package emby

// Emby artwork through the local `emby-img` protocol (ADR 0005), port of
// src-tauri/src/emby/images.rs. The UI gets ready-made URLs on that protocol; the
// native side fetches the image with the session's auth header.

import (
	"strconv"
	"strings"
)

// ImageScheme is the protocol name; WebView2 on Windows serves it as `http://emby-img.localhost/`.
const ImageScheme = "emby-img"

const (
	imageOrigin   = "http://emby-img.localhost"
	minImageWidth = 32
	maxImageWidth = 1920
)

// MaxImageBytes: larger answers are refused; Emby resizes to `maxWidth`, so this is never legit.
const MaxImageBytes = 10 * 1024 * 1024

// Widths offered in `srcset`; the WebView picks one for the density.
var (
	PosterWidths  = []uint32{240, 360, 480}
	LibraryWidths = []uint32{400, 640, 800}
	// BackdropWidths: detail page, the backdrop spans the window.
	BackdropWidths = []uint32{960, 1440, 1920}
	// LogoWidths: the logo replaces the title.
	LogoWidths = []uint32{320, 640}
	// ThumbWidths: 16:9 episode stills.
	ThumbWidths = []uint32{320, 480, 640}
	// PhotoWidths: round cast photos of a detail page (ADR 0121).
	PhotoWidths = []uint32{120, 240}
)

// ImageType is an Emby image type the protocol serves.
type ImageType int

const (
	ImagePrimary ImageType = iota
	ImageBackdrop
	ImageThumb
	ImageLogo
)

var imageTypeNames = [...]string{"Primary", "Backdrop", "Thumb", "Logo"}

// String is the Emby name (`as_str`).
func (t ImageType) String() string {
	if int(t) >= 0 && int(t) < len(imageTypeNames) {
		return imageTypeNames[t]
	}
	return "ImageType(" + strconv.Itoa(int(t)) + ")"
}

func parseImageType(v string) (ImageType, bool) {
	for i, n := range imageTypeNames {
		if n == v {
			return ImageType(i), true
		}
	}
	return 0, false
}

// ImageRequest is one validated artwork request coming from the WebView.
type ImageRequest struct {
	// ServerID: Emby server the URL was built for (ADR 0012), refused unless it is the current one.
	ServerID  string
	ItemID    string
	ImageType ImageType
	Tag       string
	MaxWidth  uint32
}

// ParseImageRequest parses `/{serverId}/{itemId}/{Type}?tag=…&maxWidth=…`. Anything
// else is refused (false), so the protocol cannot reach other server endpoints.
func ParseImageRequest(path string, query *string) (ImageRequest, bool) {
	parts := strings.Split(strings.TrimLeft(path, "/"), "/")
	if len(parts) != 3 {
		return ImageRequest{}, false
	}
	serverID, itemID, kind := parts[0], parts[1], parts[2]
	if !isImageID(serverID) || !isImageID(itemID) {
		return ImageRequest{}, false
	}
	imageType, ok := parseImageType(kind)
	if !ok {
		return ImageRequest{}, false
	}
	var tag *string
	var maxWidth *uint32
	q := ""
	if query != nil {
		q = *query
	}
	for _, pair := range strings.Split(q, "&") {
		if pair == "" {
			continue
		}
		k, v, found := strings.Cut(pair, "=")
		if !found {
			return ImageRequest{}, false
		}
		switch {
		case k == "tag" && isImageTag(v):
			tag = &v
		case k == "maxWidth":
			// As Rust: an unreadable width erases one read before.
			maxWidth = parseRustU32(v)
		default:
			return ImageRequest{}, false
		}
	}
	if maxWidth == nil || *maxWidth < minImageWidth || *maxWidth > maxImageWidth || tag == nil {
		return ImageRequest{}, false
	}
	return ImageRequest{ServerID: serverID, ItemID: itemID, ImageType: imageType, Tag: *tag, MaxWidth: *maxWidth}, true
}

// parseRustU32 is `str::parse::<u32>`: ASCII digits, one optional leading `+`.
func parseRustU32(v string) *uint32 {
	digits := strings.TrimPrefix(v, "+")
	if digits == "" || strings.ContainsFunc(digits, func(r rune) bool { return r < '0' || r > '9' }) {
		return nil
	}
	n, err := strconv.ParseUint(digits, 10, 32)
	if err != nil {
		return nil
	}
	w := uint32(n)
	return &w
}

// APIPath is the path on the Emby API (`/Items/{Id}/Images/{Type}`).
func (r ImageRequest) APIPath() string {
	return "/Items/" + r.ItemID + "/Images/" + r.ImageType.String()
}

// ImageRefFor is `image_ref`: `src`/`srcset` for an item image, or false when the
// item has no such image (a missing image still answers 200 with a generic picture,
// so `ImageTags` decides). The server id scopes the WebView's HTTP cache to one
// server (ADR 0012).
func ImageRefFor(serverID, itemID string, imageType ImageType, tag *string, widths []uint32) (ImageRef, bool) {
	if tag == nil || !isImageTag(*tag) {
		return ImageRef{}, false
	}
	if !isImageID(serverID) || !isImageID(itemID) || len(widths) == 0 {
		return ImageRef{}, false
	}
	url := func(w uint32) string {
		return imageOrigin + "/" + serverID + "/" + itemID + "/" + imageType.String() +
			"?tag=" + *tag + "&maxWidth=" + strconv.FormatUint(uint64(w), 10)
	}
	set := make([]string, len(widths))
	for i, w := range widths {
		set[i] = url(w) + " " + strconv.FormatUint(uint64(w), 10) + "w"
	}
	return ImageRef{Src: url(widths[0]), Srcset: strings.Join(set, ", ")}, true
}

// ImageRefPtr is ImageRefFor as an `Option<ImageRef>` field value.
func ImageRefPtr(serverID, itemID string, imageType ImageType, tag *string, widths []uint32) *ImageRef {
	r, ok := ImageRefFor(serverID, itemID, imageType, tag, widths)
	if !ok {
		return nil
	}
	return &r
}

func isImageID(v string) bool {
	return v != "" && len(v) <= 64 && !strings.ContainsFunc(v, func(r rune) bool { return !isASCIIAlnum(r) && r != '-' })
}

func isImageTag(v string) bool {
	return v != "" && len(v) <= 128 &&
		!strings.ContainsFunc(v, func(r rune) bool { return !isASCIIAlnum(r) && r != '_' && r != '-' })
}

func isASCIIAlnum(r rune) bool {
	return (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') || (r >= '0' && r <= '9')
}
