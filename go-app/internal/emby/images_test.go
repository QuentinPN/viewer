package emby

import "testing"

// emby/images.rs builds_srcset_from_image_tags
func Test_builds_srcset_from_image_tags(t *testing.T) {
	r, ok := ImageRefFor("srv1", "74668", ImagePrimary, ptr("ab12_6384"), []uint32{240, 480})
	if !ok {
		t.Fatal("no image")
	}
	if r.Src != "http://emby-img.localhost/srv1/74668/Primary?tag=ab12_6384&maxWidth=240" {
		t.Fatal(r.Src)
	}
	want := "http://emby-img.localhost/srv1/74668/Primary?tag=ab12_6384&maxWidth=240 240w, " +
		"http://emby-img.localhost/srv1/74668/Primary?tag=ab12_6384&maxWidth=480 480w"
	if r.Srcset != want {
		t.Fatal(r.Srcset)
	}
	if _, ok := ImageRefFor("srv1", "1", ImagePrimary, nil, []uint32{240}); ok {
		t.Fatal("no tag")
	}
	if _, ok := ImageRefFor("srv1", "1", ImagePrimary, ptr("a\"b"), []uint32{240}); ok {
		t.Fatal("quote in tag")
	}
}

// emby/images.rs parses_only_well_formed_requests
func Test_parses_only_well_formed_requests(t *testing.T) {
	ok, found := ParseImageRequest("/srv1/74668/Primary", ptr("tag=ab12_6384&maxWidth=360"))
	if !found {
		t.Fatal("refused")
	}
	if ok.APIPath() != "/Items/74668/Images/Primary" || ok.ServerID != "srv1" || ok.MaxWidth != 360 {
		t.Fatalf("%+v", ok)
	}
	for _, c := range []struct {
		path  string
		query *string
	}{
		{"/srv1/74668/Primary", ptr("tag=x")},                        // no width
		{"/srv1/74668/Primary", ptr("maxWidth=360")},                 // no tag
		{"/srv1/74668/Primary", ptr("tag=x&maxWidth=8000")},          // too large
		{"/srv1/74668/Primary", ptr("tag=x&maxWidth=360&api_key=y")}, // extra param
		{"/srv1/74668/Chapter", ptr("tag=x&maxWidth=360")},           // type not allowed
		{"/srv1/../Users/Primary", ptr("tag=x&maxWidth=360")},        // traversal
		{"/srv1/74668/Primary/0", ptr("tag=x&maxWidth=360")},         // extra segment
		{"/srv1/74668/Primary", ptr("tag=a%2Fb&maxWidth=360")},       // encoded chars
		{"/srv1/74668/Primary", nil},
		{"/74668/Primary", ptr("tag=x&maxWidth=360")}, // no server segment
	} {
		if r, found := ParseImageRequest(c.path, c.query); found {
			t.Errorf("%s?%v accepted: %+v", c.path, c.query, r)
		}
	}
}

// Rust quirks kept: a later bad maxWidth erases an earlier good one; a `+` sign is
// accepted by `u32::from_str`; leading slashes are all trimmed.
func TestImageRequestRustQuirks(t *testing.T) {
	if _, ok := ParseImageRequest("/s/1/Primary", ptr("maxWidth=360&tag=x&maxWidth=abc")); ok {
		t.Fatal("bad width after a good one must refuse")
	}
	if r, ok := ParseImageRequest("/s/1/Primary", ptr("maxWidth=abc&tag=x&maxWidth=360")); !ok || r.MaxWidth != 360 {
		t.Fatal("a good width after a bad one is kept")
	}
	if r, ok := ParseImageRequest("/s/1/Primary", ptr("tag=x&maxWidth=+360")); !ok || r.MaxWidth != 360 {
		t.Fatal("+360")
	}
	if _, ok := ParseImageRequest("//s/1/Primary", ptr("tag=x&maxWidth=360")); !ok {
		t.Fatal("leading slashes trimmed")
	}
	if _, ok := ParseImageRequest("/s/1/Primary", ptr("&&tag=x&maxWidth=360&")); !ok {
		t.Fatal("empty pairs skipped")
	}
	if _, ok := ParseImageRequest("/s/1/Primary", ptr("tag&maxWidth=360")); ok {
		t.Fatal("pair without =")
	}
	if _, ok := ParseImageRequest("/s/1/Primary", ptr("tag=x&maxWidth=-1")); ok {
		t.Fatal("negative width")
	}
}

type imageRefRow struct {
	Case      string    `json:"case,omitempty"`
	Widths    string    `json:"widths,omitempty"`
	ImageType string    `json:"imageType"`
	ServerID  string    `json:"serverId"`
	ItemID    string    `json:"itemId"`
	Tag       *string   `json:"tag"`
	Result    *ImageRef `json:"result"`
}

func TestGolden_images_image_ref(t *testing.T) {
	widths := []struct {
		name string
		w    []uint32
	}{
		{"poster", PosterWidths}, {"library", LibraryWidths}, {"backdrop", BackdropWidths},
		{"logo", LogoWidths}, {"thumb", ThumbWidths}, {"photo", PhotoWidths},
	}
	types := []ImageType{ImagePrimary, ImageBackdrop, ImageThumb, ImageLogo}
	refOrNil := func(server, item string, kind ImageType, tag *string, w []uint32) *ImageRef {
		r, ok := ImageRefFor(server, item, kind, tag, w)
		if !ok {
			return nil
		}
		return &r
	}
	var rows []imageRefRow
	for _, w := range widths {
		for _, kind := range types {
			rows = append(rows, imageRefRow{
				Widths: w.name, ImageType: kind.String(), ServerID: "srv1", ItemID: "74668", Tag: ptr("ab12_6384"),
				Result: refOrNil("srv1", "74668", kind, ptr("ab12_6384"), w.w),
			})
		}
	}
	for _, c := range []struct {
		name, server, item string
		tag                *string
		w                  []uint32
	}{
		{"no_tag", "srv1", "1", nil, PosterWidths},
		{"tag_with_quote", "srv1", "1", ptr("a\"b"), PosterWidths},
		{"unsafe_item_id", "srv1", "../x", ptr("t"), PosterWidths},
		{"unsafe_server_id", "a/b", "1", ptr("t"), PosterWidths},
		{"no_widths", "srv1", "1", ptr("t"), nil},
	} {
		rows = append(rows, imageRefRow{
			Case: c.name, ImageType: "Primary", ServerID: c.server, ItemID: c.item, Tag: c.tag,
			Result: refOrNil(c.server, c.item, ImagePrimary, c.tag, c.w),
		})
	}
	assertTableGolden(t, "images.image_ref.json", rows)
}

type parsedRequest struct {
	ServerID  string `json:"serverId"`
	ItemID    string `json:"itemId"`
	ImageType string `json:"imageType"`
	Tag       string `json:"tag"`
	MaxWidth  uint32 `json:"maxWidth"`
	APIPath   string `json:"apiPath"`
}

func TestGolden_images_request_parse(t *testing.T) {
	requests := []struct {
		path  string
		query *string
	}{
		{"/srv1/74668/Primary", ptr("tag=ab12_6384&maxWidth=360")},
		{"/srv1/74668/Backdrop", ptr("maxWidth=1920&tag=t-1")},
		{"/srv1/74668/Thumb", ptr("tag=x&maxWidth=32")},
		{"/srv1/74668/Logo", ptr("tag=x&maxWidth=640")},
		{"/srv1/74668/Primary", ptr("tag=x")},
		{"/srv1/74668/Primary", ptr("maxWidth=360")},
		{"/srv1/74668/Primary", ptr("tag=x&maxWidth=8000")},
		{"/srv1/74668/Primary", ptr("tag=x&maxWidth=31")},
		{"/srv1/74668/Primary", ptr("tag=x&maxWidth=360&api_key=y")},
		{"/srv1/74668/Chapter", ptr("tag=x&maxWidth=360")},
		{"/srv1/../Users/Primary", ptr("tag=x&maxWidth=360")},
		{"/srv1/74668/Primary/0", ptr("tag=x&maxWidth=360")},
		{"/srv1/74668/Primary", ptr("tag=a%2Fb&maxWidth=360")},
		{"/srv1/74668/Primary", nil},
		{"/74668/Primary", ptr("tag=x&maxWidth=360")},
	}
	type row struct {
		Path   string         `json:"path"`
		Query  *string        `json:"query"`
		Parsed *parsedRequest `json:"parsed"`
	}
	var rows []row
	for _, r := range requests {
		out := row{Path: r.path, Query: r.query}
		if p, ok := ParseImageRequest(r.path, r.query); ok {
			out.Parsed = &parsedRequest{
				ServerID: p.ServerID, ItemID: p.ItemID, ImageType: p.ImageType.String(),
				Tag: p.Tag, MaxWidth: p.MaxWidth, APIPath: p.APIPath(),
			}
		}
		rows = append(rows, out)
	}
	assertTableGolden(t, "images.request_parse.json", rows)
}
