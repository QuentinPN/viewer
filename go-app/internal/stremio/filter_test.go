package stremio

import (
	"encoding/json"
	"slices"
	"testing"
)

func TestServes(t *testing.T) {
	var addons []Addon
	for _, e := range collectionEntries(t) {
		a, err := ParseAddon(e.TransportURL, e.Manifest)
		if err != nil {
			t.Fatalf("%s : %v", e.TransportURL, err)
		}
		addons = append(addons, a)
	}
	serving := func(ct ContentType, id string) []string {
		var names []string
		for _, a := range addons {
			if a.Serves(ct, id) {
				names = append(names, a.Name)
			}
		}
		return names
	}
	// TMDB Collections: object-form stream resource, movie only. Local Files: no
	// manifest-level idPrefixes. Lumio, Wastream: plain "stream", manifest-level
	// idPrefixes. The others: object form with their own types and idPrefixes.
	wantMovie := []string{
		"TMDB Collections", "Local Files (without catalog support)", "Lumio · Profil", "AIOSources",
		"StreamFusionReborn", "Wastream | AD", "Comet | AD", "TorrentsDB AD",
	}
	if got := serving(Movie, "tt0111161"); !slices.Equal(got, wantMovie) {
		t.Errorf("film : %q", got)
	}
	wantSeries := wantMovie[1:]
	if got := serving(Series, "tt0944947:1:2"); !slices.Equal(got, wantSeries) {
		t.Errorf("série : %q", got)
	}
	// The TMDB addon and Cinemeta declare no stream resource.
	for _, a := range addons {
		if a.Name == "The Movie Database Addon" || a.Name == "Cinemeta" {
			if a.Serves(Movie, "tt0111161") || a.Serves(Series, "tt0944947:1:2") || a.Serves(Movie, "tmdb:1") {
				t.Errorf("%s sert des flux", a.Name)
			}
		}
	}
	if got := serving(Movie, "kitsu:1"); !slices.Equal(got, []string{"Lumio · Profil", "Wastream | AD", "Comet | AD", "TorrentsDB AD"}) {
		t.Errorf("kitsu : %q", got)
	}
}

func TestServesRules(t *testing.T) {
	cases := []struct {
		name     string
		manifest string
		ct       ContentType
		id       string
		want     bool
	}{
		{"kitsu only", `{"resources":["stream"],"types":["movie","series"],"idPrefixes":["kitsu"]}`, Movie, "tt0111161", false},
		{"no prefixes", `{"resources":["stream"],"types":["movie"]}`, Movie, "tt0111161", true},
		{"type not listed", `{"resources":["stream"],"types":["series"],"idPrefixes":["tt"]}`, Movie, "tt0111161", false},
		{"object types override", `{"resources":[{"name":"stream","types":["series"]}],"types":["movie"]}`, Movie, "tt0111161", false},
		{"object prefixes override", `{"resources":[{"name":"stream","idPrefixes":["tt"]}],"types":["movie"],"idPrefixes":["kitsu"]}`, Movie, "tt0111161", true},
		{"object falls back to manifest", `{"resources":[{"name":"stream"}],"types":["movie"],"idPrefixes":["tt"]}`, Movie, "tt0111161", true},
		{"empty prefixes accept nothing", `{"resources":[{"name":"stream","types":["movie"],"idPrefixes":[]}],"types":["movie"]}`, Movie, "tt0111161", false},
		{"no stream resource", `{"resources":["catalog","meta"],"types":["movie"],"idPrefixes":["tt"]}`, Movie, "tt0111161", false},
		{"unknown resource forms skipped", `{"resources":[1,null,{"types":["movie"]},"stream"],"types":["movie"]}`, Movie, "tt0111161", true},
		{"no resources", `{"types":["movie"]}`, Movie, "tt0111161", false},
	}
	for _, c := range cases {
		var m map[string]any
		if err := json.Unmarshal([]byte(c.manifest), &m); err != nil {
			t.Fatal(err)
		}
		m["id"], m["version"], m["name"] = "x", "1", "X"
		raw, _ := json.Marshal(m)
		a, err := ParseAddon("https://addon.example/x/manifest.json", raw)
		if err != nil {
			t.Fatalf("%s : %v", c.name, err)
		}
		if got := a.Serves(c.ct, c.id); got != c.want {
			t.Errorf("%s : %v", c.name, got)
		}
	}
}
