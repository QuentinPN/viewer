package request

import (
	"strings"
	"testing"
)

// The origin is a parameter: the default is Tauri's, the Wails build serves the same
// paths under its own origin.
func Test_go_image_ref_on_another_origin(t *testing.T) {
	if got := currentOrigin(); got != TauriOrigin {
		t.Fatal(got)
	}
	file := "/abc.jpg"
	r := imageRefAt("http://wails.localhost/requestimg", &file, []string{"w185", "w342"})
	if r == nil || r.Src != "http://wails.localhost/requestimg/w185/abc.jpg" ||
		r.Srcset != "http://wails.localhost/requestimg/w185/abc.jpg 185w, http://wails.localhost/requestimg/w342/abc.jpg 342w" {
		t.Fatalf("%+v", r)
	}
	if req, ok := ParseUrImage(strings.TrimPrefix(r.Src, "http://wails.localhost/requestimg")); !ok || req.Size != "w185" || req.File != "abc.jpg" {
		t.Fatalf("%+v %v", req, ok)
	}
}

// SetImageOrigin reaches the models: what the Wails build sets at startup is what
// every decorated ImageRef carries. No test of this package runs in parallel.
func Test_go_set_image_origin_reaches_decorated_models(t *testing.T) {
	SetImageOrigin("http://wails.localhost/requestimg")
	t.Cleanup(func() { SetImageOrigin(TauriOrigin) })
	poster := "/abc.jpg"
	m := decorated(MediaSummary{PosterPath: &poster})
	if m.Poster == nil || m.Poster.Src != "http://wails.localhost/requestimg/w185/abc.jpg" {
		t.Fatalf("%+v", m.Poster)
	}
}
