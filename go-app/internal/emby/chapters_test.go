package emby

import (
	"encoding/json"
	"reflect"
	"strings"
	"testing"
	"unicode/utf8"
)

func dto(ticks int64, name, marker *string) ChapterDto {
	return ChapterDto{StartPositionTicks: ticks, Name: name, MarkerType: marker}
}

// emby/chapters.rs maps_the_real_answer_of_a_film
func Test_maps_the_real_answer_of_a_film(t *testing.T) {
	ch := ptr("Chapter")
	found := Chapters([]ChapterDto{
		dto(0, ptr("Opening Credits"), ch),
		dto(2_421_170_000, ptr("Horn Island 1977"), ch),
		dto(5_489_650_000, ptr("Someone to Take the Fall"), ch),
		dto(7_002_000_000, ptr("Secret of Fly Fishing"), ch),
	}, ptr(int64(61_154_010_000)))
	want := List[Chapter]{
		{Start: 0.0, Name: "Opening Credits"},
		{Start: 242.117, Name: "Horn Island 1977"},
		{Start: 548.965, Name: "Someone to Take the Fall"},
		{Start: 700.2, Name: "Secret of Fly Fishing"},
	}
	if !reflect.DeepEqual(found, want) {
		t.Fatalf("%+v", found)
	}
}

// emby/chapters.rs keeps_regular_chapters_only_sorted_and_named
func Test_keeps_regular_chapters_only_sorted_and_named(t *testing.T) {
	found := Chapters([]ChapterDto{
		dto(6_000_000_000, nil, nil),
		dto(0, ptr("  Début\n du film "), ptr("Chapter")),
		dto(300_000_000, nil, ptr("IntroStart")),
		dto(900_000_000, nil, ptr("IntroEnd")),
		dto(3_000_000_000, ptr(""), ptr("Chapter")),
		dto(24_000_000_000, nil, ptr("CreditsStart")),
	}, nil)
	want := List[Chapter]{{0, "Début du film"}, {300, "Chapitre 2"}, {600, "Chapitre 3"}}
	if !reflect.DeepEqual(found, want) {
		t.Fatalf("%+v", found)
	}
}

// emby/chapters.rs drops_what_cannot_be_navigated
func Test_drops_what_cannot_be_navigated(t *testing.T) {
	found := Chapters([]ChapterDto{
		dto(-10, ptr("Avant"), nil),
		dto(0, ptr("A"), nil),
		dto(5_000_000, ptr("Doublon"), nil),
		dto(1_200_000_000, ptr("B"), nil),
		dto(2_000_000_000, ptr("Après la fin"), nil),
	}, ptr(int64(1_800_000_000)))
	var names []string
	for _, c := range found {
		names = append(names, c.Name)
	}
	if !reflect.DeepEqual(names, []string{"A", "B"}) {
		t.Fatal(names)
	}
	if len(Chapters([]ChapterDto{dto(0, ptr("Seul"), nil)}, nil)) != 0 {
		t.Fatal("one chapter")
	}
	if len(Chapters(nil, ptr(int64(1)))) != 0 {
		t.Fatal("none")
	}
	if len(Chapters([]ChapterDto{dto(0, nil, ptr("IntroStart")), dto(1, nil, ptr("IntroEnd"))}, nil)) != 0 {
		t.Fatal("markers only")
	}
}

// emby/chapters.rs long_names_are_cut
func Test_long_names_are_cut(t *testing.T) {
	found := Chapters([]ChapterDto{dto(0, ptr(strings.Repeat("x", 500)), nil), dto(600_000_000, nil, nil)}, nil)
	if n := utf8.RuneCountInString(found[0].Name); n != maxChapterNameChars {
		t.Fatal(n)
	}
	// Characters, not bytes, and control characters dropped after joining the words.
	found = Chapters([]ChapterDto{dto(0, ptr(strings.Repeat("é", 200)+"\u0007"), nil), dto(600_000_000, ptr("a\u0007b"), nil)}, nil)
	if utf8.RuneCountInString(found[0].Name) != maxChapterNameChars || found[1].Name != "ab" {
		t.Fatalf("%+v", found)
	}
	if got := Chapters([]ChapterDto{dto(0, ptr("\u0007"), nil), dto(600_000_000, nil, nil)}, nil); got[0].Name != "Chapitre 1" {
		t.Fatal(got[0].Name)
	}
}

func TestChaptersAreNeverNull(t *testing.T) {
	if s := string(marshal(t, Chapters(nil, nil))); s != "[]" {
		t.Fatal(s)
	}
}

func TestGolden_chapters_map(t *testing.T) {
	var rows []map[string]json.RawMessage
	if err := json.Unmarshal(readGolden(t, "chapters.map.json"), &rows); err != nil {
		t.Fatal(err)
	}
	type out struct {
		Case         string          `json:"case"`
		UnitTest     string          `json:"unitTest"`
		ChapterDtos  json.RawMessage `json:"chapterDtos"`
		RunTimeTicks *int64          `json:"runTimeTicks"`
		Chapters     List[Chapter]   `json:"chapters"`
	}
	var got []out
	for _, r := range rows {
		var o out
		for k, dst := range map[string]any{"case": &o.Case, "unitTest": &o.UnitTest, "runTimeTicks": &o.RunTimeTicks} {
			if err := json.Unmarshal(r[k], dst); err != nil {
				t.Fatal(k, err)
			}
		}
		o.ChapterDtos = r["chapterDtos"]
		var dtos []ChapterDto
		if err := json.Unmarshal(o.ChapterDtos, &dtos); err != nil {
			t.Fatal(err)
		}
		o.Chapters = Chapters(dtos, o.RunTimeTicks)
		got = append(got, o)
	}
	assertTableGolden(t, "chapters.map.json", got)
}

// Chapters of the browse_like() fake server's items, fed from the same fixtures.
func TestGolden_chapters_emby_chapters(t *testing.T) {
	for name, fixture := range map[string]string{
		"chapters.emby_chapters.film.json": "movie_detail.json",
		"chapters.emby_chapters.none.json": "series_detail.json",
	} {
		t.Run(name, func(t *testing.T) {
			var item BaseItemDto
			if err := json.Unmarshal(readFixture(t, fixture), &item); err != nil {
				t.Fatal(err)
			}
			assertTypedGolden(t, name, Chapters(item.Chapters, item.RunTimeTicks))
		})
	}
}
