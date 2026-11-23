package brand

import (
	"bytes"
	"errors"
	"image/png"
	"slices"
	"testing"
)

// The ids of src/lib/brand/season.ts SEASON_IDS, and debugID, the hard hat of
// the debug build (src/lib/brand/logos.ts LogoId; not a season).
const debugID = "debug"

var seasonIDs = []string{
	"default", "new-year", "epiphany", "winter", "valentine", "april-fools", "easter",
	"music", "summer", "bastille", "autumn", "halloween", "christmas",
}

func Test_ids_are_the_seasons_and_debug(t *testing.T) {
	want := append(slices.Clone(seasonIDs), debugID)
	slices.Sort(want)
	if got := IDs(); !slices.Equal(got, want) {
		t.Fatalf("ids %v, attendu %v", got, want)
	}
}

func Test_every_icon_is_a_256_px_png(t *testing.T) {
	for _, id := range append(slices.Clone(seasonIDs), debugID) {
		data, err := PNG(id)
		if err != nil {
			t.Fatalf("%s : %v", id, err)
		}
		img, err := png.Decode(bytes.NewReader(data))
		if err != nil {
			t.Fatalf("%s : %v", id, err)
		}
		if b := img.Bounds(); b.Dx() != 256 || b.Dy() != 256 {
			t.Errorf("%s : %v", id, b)
		}
	}
}

func Test_unknown_ids_are_refused(t *testing.T) {
	for _, id := range []string{"", "Default", "Debug", "debug.png", "default.png", "../default", `..\default`, "icons/default", "default\x00", "noel"} {
		if Valid(id) {
			t.Errorf("%q accepté", id)
		}
		if _, err := PNG(id); !errors.Is(err, ErrUnknownID) {
			t.Errorf("%q : %v", id, err)
		}
		if err := new(WindowIcon).Apply(1, id); !errors.Is(err, ErrUnknownID) {
			t.Errorf("Apply %q : %v", id, err)
		}
	}
}

func Test_no_window(t *testing.T) {
	if err := new(WindowIcon).Apply(0, "default"); !errors.Is(err, ErrNoWindow) {
		t.Fatal(err)
	}
}
