package emby

import (
	"encoding/json"
	"fmt"
	"reflect"
	"slices"
	"strings"
	"testing"
)

func titleItem(t *testing.T, id, kind, name string, year *int32) BaseItemDto {
	t.Helper()
	b, _ := json.Marshal(map[string]any{"Id": id, "Type": kind, "Name": name, "ProductionYear": year})
	var item BaseItemDto
	if err := json.Unmarshal(b, &item); err != nil {
		t.Fatal(err)
	}
	return item
}

func ids(items []BaseItemDto) []string {
	out := []string{}
	for _, i := range items {
		out = append(out, i.ID)
	}
	return out
}

func get(p []Param, k string) (string, bool) {
	for _, x := range p {
		if x.Key == k {
			return x.Value, true
		}
	}
	return "", false
}

// emby/titles.rs collections_parse_like_the_tv
func Test_collections_parse_like_the_tv(t *testing.T) {
	raw := json.RawMessage(`[
		{"Name": "Dune - Saga", "Id": 120034},
		{"Name": "Dune - Saga", "Id": "120034"},
		{"Name": "  ", "Id": "5"},
		{"Name": "Sans id"},
		{"Name": "Mauvais", "Id": "../x"},
		{"Name": "B", "Id": "2"},
		{"Name": "C", "Id": "3"},
		{"Name": "D", "Id": "4"}
	]`)
	refs := CollectionRefs(raw)
	var got []string
	for _, c := range refs {
		got = append(got, c.ID)
	}
	if !reflect.DeepEqual(got, []string{"120034", "2", "3"}) {
		t.Fatal(got)
	}
	if refs[0].Name != "Dune - Saga" {
		t.Fatal(refs[0].Name)
	}
	if len(CollectionRefs(nil)) != 0 {
		t.Fatal("none")
	}
	if len(CollectionRefs(json.RawMessage(`{"Id": 1}`))) != 0 {
		t.Fatal("not a list")
	}
	for _, raw := range []string{`null`, ``, ` null `} {
		if got := CollectionRefs(json.RawMessage(raw)); got == nil || len(got) != 0 {
			t.Fatalf("%q: %v", raw, got)
		}
	}
}

// emby/titles.rs collection_titles_in_release_order
func Test_collection_titles_in_release_order(t *testing.T) {
	a := titleItem(t, "a", "Movie", "Dune 2", ptr(int32(2024)))
	a.PremiereDate = ptr("2024-02-27T23:00:00.0000000Z")
	b := titleItem(t, "b", "Movie", "Dune", ptr(int32(2021)))
	b.PremiereDate = ptr("2021-09-14T22:00:00.0000000Z")
	c := titleItem(t, "c", "Movie", "Dune 1984", ptr(int32(1984)))
	d := titleItem(t, "d", "Movie", "Inédit", nil)
	source := TitlesSource{Kind: TitlesCollection, ID: "9"}
	if got := ids(OrderTitles(source, []BaseItemDto{d, a, b, c})); !reflect.DeepEqual(got, []string{"c", "b", "a", "d"}) {
		t.Fatal(got)
	}
	p, err := TitlesParams(source)
	if err != nil {
		t.Fatal(err)
	}
	if !slices.Contains(p, Param{"ParentId", "9"}) || !slices.Contains(p, Param{"SortBy", "PremiereDate,ProductionYear,SortName"}) {
		t.Fatal(p)
	}
}

// emby/titles.rs similar_leaves_the_title_out
func Test_similar_leaves_the_title_out(t *testing.T) {
	source := TitlesSource{Kind: TitlesSimilar, ID: "a"}
	list := []BaseItemDto{titleItem(t, "a", "Movie", "Soi", nil), titleItem(t, "b", "Series", "B", nil)}
	for n := range 20 {
		list = append(list, titleItem(t, fmt.Sprintf("x%d", n), "Movie", "X", nil))
	}
	list = append(list, titleItem(t, "b", "Series", "B", nil))
	out := OrderTitles(source, list)
	if len(out) != SimilarLimit {
		t.Fatal(len(out))
	}
	if out[0].ID != "b" {
		t.Fatal(out[0].ID)
	}
	if _, err := TitlesParams(source); err == nil {
		t.Fatal("similar has no titles params")
	}
	if _, ok := SimilarParams("../x", "u"); ok {
		t.Fatal("bad id")
	}
	p, ok := SimilarParams("a", "u")
	if !ok || !slices.Contains(p, Param{"IncludeItemTypes", "Movie,Series"}) {
		t.Fatal(p)
	}
}

func person(t *testing.T, js string) PersonDto {
	t.Helper()
	var p PersonDto
	if err := json.Unmarshal([]byte(js), &p); err != nil {
		t.Fatal(err)
	}
	return p
}

// emby/titles.rs person_query_matches_the_tv
func Test_person_query_matches_the_tv(t *testing.T) {
	p, err := TitlesParams(TitlesSource{Kind: TitlesPerson, ID: "30209"})
	if err != nil {
		t.Fatal(err)
	}
	for k, want := range map[string]string{
		"PersonIds": "30209", "Recursive": "true", "IncludeItemTypes": "Movie,Series",
		"SortBy": "ProductionYear,SortName", "SortOrder": "Descending", "Limit": "100", "EnableImageTypes": "Primary",
	} {
		if v, ok := get(p, k); !ok || v != want {
			t.Errorf("%s = %q", k, v)
		}
	}
}

// emby/titles.rs a_bad_person_id_is_refused
func Test_a_bad_person_id_is_refused(t *testing.T) {
	for _, id := range []string{"", "../Users", "12 34", "a&b=c"} {
		if _, err := TitlesParams(TitlesSource{Kind: TitlesPerson, ID: id}); err == nil {
			t.Error(id)
		}
	}
}

// emby/titles.rs genre_and_studio_queries_filter_by_name_and_type
func Test_genre_and_studio_queries_filter_by_name_and_type(t *testing.T) {
	p, err := TitlesParams(TitlesSource{Kind: TitlesGenre, Name: " Science-Fiction ", ItemType: "Movie"})
	if err != nil {
		t.Fatal(err)
	}
	for k, want := range map[string]string{
		"Genres": "Science-Fiction", "IncludeItemTypes": "Movie", "Recursive": "true", "SortBy": "SortName", "Limit": "200",
	} {
		if v, ok := get(p, k); !ok || v != want {
			t.Errorf("%s = %q", k, v)
		}
	}
	if _, ok := get(p, "Studios"); ok {
		t.Error("Studios")
	}
	p, err = TitlesParams(TitlesSource{Kind: TitlesStudio, Name: "HBO", ItemType: "Series"})
	if err != nil {
		t.Fatal(err)
	}
	if !slices.Contains(p, Param{"Studios", "HBO"}) || !slices.Contains(p, Param{"IncludeItemTypes", "Series"}) {
		t.Fatal(p)
	}
}

// emby/titles.rs unusable_names_and_types_are_refused
func Test_unusable_names_and_types_are_refused(t *testing.T) {
	for _, c := range [][2]string{
		{"Drame|Comédie", "Movie"}, {"", "Movie"}, {"  ", "Series"}, {"Drame", "Episode"}, {"Drame", "Movie,Series"},
	} {
		if _, err := TitlesParams(TitlesSource{Kind: TitlesGenre, Name: c[0], ItemType: c[1]}); err == nil {
			t.Error(c)
		}
	}
	if _, err := TitlesParams(TitlesSource{Kind: TitlesStudio, Name: strings.Repeat("x", 101), ItemType: "Movie"}); err == nil {
		t.Error("long name")
	}
}

// emby/titles.rs sources_decode_from_the_ui_shape
func Test_sources_decode_from_the_ui_shape(t *testing.T) {
	var s TitlesSource
	if err := json.Unmarshal([]byte(`{"kind":"studio","name":"HBO","itemType":"Series"}`), &s); err != nil {
		t.Fatal(err)
	}
	if s != (TitlesSource{Kind: TitlesStudio, Name: "HBO", ItemType: "Series"}) {
		t.Fatalf("%+v", s)
	}
	var p TitlesSource
	if err := json.Unmarshal([]byte(`{"kind":"person","id":"1"}`), &p); err != nil {
		t.Fatal(err)
	}
	if p != (TitlesSource{Kind: TitlesPerson, ID: "1"}) {
		t.Fatalf("%+v", p)
	}
}

// serde's internally tagged enum: unknown kind, missing or null field refused.
func TestTitlesSourceDecodeRefusesLikeSerde(t *testing.T) {
	for _, bad := range []string{
		`{"kind":"actor","id":"1"}`, `{"id":"1"}`, `{"kind":"person"}`, `{"kind":"person","id":null}`,
		`{"kind":"genre","name":"Drame"}`, `{"kind":"genre","itemType":"Movie"}`, `{"Kind":"person","id":"1"}`,
		`{"kind":"person","id":1}`, `null`, `[]`, `{"kind":null,"id":"1"}`,
	} {
		var s TitlesSource
		if err := json.Unmarshal([]byte(bad), &s); err == nil {
			t.Errorf("%s accepted: %+v", bad, s)
		}
	}
	var s TitlesSource
	if err := json.Unmarshal([]byte(`{"kind":"similar","id":"7","extra":true}`), &s); err != nil || s.ID != "7" {
		t.Fatal(err, s)
	}
}

// emby/titles.rs person_titles_newest_first_then_a_to_z
func Test_person_titles_newest_first_then_a_to_z(t *testing.T) {
	out := OrderTitles(TitlesSource{Kind: TitlesPerson, ID: "1"}, []BaseItemDto{
		titleItem(t, "1", "Movie", "Zorro", ptr(int32(2020))),
		titleItem(t, "2", "Movie", "alpha", ptr(int32(2020))),
		titleItem(t, "3", "Series", "Sans année", nil),
		titleItem(t, "4", "Episode", "Pilote", ptr(int32(2024))),
		titleItem(t, "5", "Movie", "Récent", ptr(int32(2023))),
		titleItem(t, "1", "Movie", "Zorro", ptr(int32(2020))),
	})
	if got := ids(out); !reflect.DeepEqual(got, []string{"5", "2", "1", "3"}) {
		t.Fatal(got)
	}
}

// emby/titles.rs person_id_reads_a_string_or_a_number
func Test_person_id_reads_a_string_or_a_number(t *testing.T) {
	for js, want := range map[string]*string{
		`{"Id": " 30209 "}`: ptr("30209"),
		`{"Id": 30209}`:     ptr("30209"),
		`{"Id": null}`:      nil,
		`{"Id": "a/b"}`:     nil,
		`{"Id": true}`:      nil,
		`{}`:                nil,
	} {
		got := PersonID(person(t, js))
		if (got == nil) != (want == nil) || (got != nil && *got != *want) {
			t.Errorf("%s: %v", js, got)
		}
	}
}

// serde_json 1.0.154 prints a number with its own rules (checked against the crate): an integer as is, a float with a
// decimal point or an exponent. An id is only kept when the text is a usable path.
func TestNumericIDsAreReadAsSerdePrintsThem(t *testing.T) {
	for js, want := range map[string]*string{
		`{"Id": 1e3}`:                   nil, // 1000.0
		`{"Id": 1.0}`:                   nil,
		`{"Id": -5}`:                    ptr("-5"),
		`{"Id": 1e30}`:                  nil, // 1e+30
		`{"Id": 100000000000000000000}`: nil, // 1e+20
		`{"Id": 18446744073709551615}`:  ptr("18446744073709551615"),
		`{"Id": -9223372036854775808}`:  ptr("-9223372036854775808"),
	} {
		got := PersonID(person(t, js))
		if (got == nil) != (want == nil) || (got != nil && *got != *want) {
			t.Errorf("%s: %v", js, deref(got))
		}
	}
	for num, want := range map[string]string{
		"0": "0", "1.5": "1.5", "1e3": "1000.0", "0.0001": "0.0001", "0.00001": "0.00001", "0.000001": "1e-6",
		"123456789012345678": "123456789012345678", "1e16": "1e+16", "-0": "-0.0", "1E3": "1000.0", "1e15": "1000000000000000.0",
		"-0.0": "-0.0", "2.5e-7": "2.5e-7", "1.25e17": "1.25e+17", "18446744073709551616": "1.8446744073709552e+19",
	} {
		if got := serdeNumberText(json.Number(num)); got != want {
			t.Errorf("%s → %s, want %s", num, got, want)
		}
	}
}

func deref(s *string) string {
	if s == nil {
		return "<nil>"
	}
	return *s
}

// Rust's to_lowercase maps İ to two characters; the order follows it.
func TestRustLowercase(t *testing.T) {
	if rustLowercase("İSTANBUL") != "i̇stanbul" {
		t.Fatal(rustLowercase("İSTANBUL"))
	}
	if rustLowercase("Écran") != "écran" {
		t.Fatal("é")
	}
}

func TestGolden_titles_params(t *testing.T) {
	sources := []string{
		`{"kind":"person","id":"30209"}`,
		`{"kind":"person","id":"../Users"}`,
		`{"kind":"person","id":"12 34"}`,
		`{"kind":"genre","name":" Science-Fiction ","itemType":"Movie"}`,
		`{"kind":"genre","name":"Drame|Comédie","itemType":"Movie"}`,
		`{"kind":"genre","name":"Drame","itemType":"Episode"}`,
		`{"kind":"studio","name":"HBO","itemType":"Series"}`,
		`{"kind":"collection","id":"120034"}`,
		`{"kind":"similar","id":"30207"}`,
	}
	var rows []map[string]any
	for _, input := range sources {
		var source TitlesSource
		if err := json.Unmarshal([]byte(input), &source); err != nil {
			t.Fatal(err)
		}
		row := map[string]any{"source": json.RawMessage(input)}
		if p, err := TitlesParams(source); err != nil {
			row["error"] = err
		} else {
			row["params"] = pairs(p)
		}
		rows = append(rows, row)
	}
	assertTableGolden(t, "titles.params.json", rows)
}

func pairs(p []Param) [][2]string {
	out := [][2]string{}
	for _, x := range p {
		out = append(out, [2]string{x.Key, x.Value})
	}
	return out
}

func TestGolden_titles_similar_params(t *testing.T) {
	var rows []map[string]any
	for _, c := range [][2]string{{"a", "u1"}, {"30207", "4a1f"}, {"../x", "u1"}, {"", "u1"}} {
		row := map[string]any{"id": c[0], "userId": c[1], "params": nil}
		if p, ok := SimilarParams(c[0], c[1]); ok {
			row["params"] = pairs(p)
		}
		rows = append(rows, row)
	}
	assertTableGolden(t, "titles.similar_params.json", rows)
}

func TestGolden_titles_collection_refs(t *testing.T) {
	raw := json.RawMessage(`[
		{"Name": "Dune - Saga", "Id": 120034},
		{"Name": "Dune - Saga", "Id": "120034"},
		{"Name": "  ", "Id": "5"},
		{"Name": "Sans id"},
		{"Name": "Mauvais", "Id": "../x"},
		{"Name": "B", "Id": "2"},
		{"Name": "C", "Id": "3"},
		{"Name": "D", "Id": "4"}
	]`)
	rows := []map[string]any{
		{"case": "list", "collections": CollectionRefs(raw)},
		{"case": "none", "collections": CollectionRefs(nil)},
		{"case": "not_a_list", "collections": CollectionRefs(json.RawMessage(`{"Id": 1}`))},
	}
	assertTableGolden(t, "titles.collection_refs.json", map[string]any{"input": raw, "rows": rows})
}

func TestGolden_titles_order(t *testing.T) {
	item := func(id, kind, name string, year *int32, premiere *string) BaseItemDto {
		i := titleItem(t, id, kind, name, year)
		i.PremiereDate = premiere
		return i
	}
	person := []BaseItemDto{
		item("1", "Movie", "Zorro", ptr(int32(2020)), nil),
		item("2", "Movie", "alpha", ptr(int32(2020)), nil),
		item("3", "Series", "Sans année", nil, nil),
		item("4", "Episode", "Pilote", ptr(int32(2024)), nil),
		item("5", "Movie", "Récent", ptr(int32(2023)), nil),
		item("1", "Movie", "Zorro", ptr(int32(2020)), nil),
	}
	collection := []BaseItemDto{
		item("d", "Movie", "Inédit", nil, nil),
		item("a", "Movie", "Dune 2", ptr(int32(2024)), ptr("2024-02-27T23:00:00.0000000Z")),
		item("b", "Movie", "Dune", ptr(int32(2021)), ptr("2021-09-14T22:00:00.0000000Z")),
		item("c", "Movie", "Dune 1984", ptr(int32(1984)), nil),
	}
	similar := []BaseItemDto{item("a", "Movie", "Soi", nil, nil), item("b", "Series", "B", nil, nil)}
	for n := range 20 {
		similar = append(similar, item(fmt.Sprintf("x%d", n), "Movie", "X", nil, nil))
	}
	ordered := func(s TitlesSource, list []BaseItemDto) []string {
		return ids(OrderTitles(s, slices.Clone(list)))
	}
	assertTableGolden(t, "titles.order.json", []map[string]any{
		{"source": "person", "inputIds": ids(person), "orderedIds": ordered(TitlesSource{Kind: TitlesPerson, ID: "1"}, person)},
		{"source": "collection", "inputIds": ids(collection), "orderedIds": ordered(TitlesSource{Kind: TitlesCollection, ID: "9"}, collection)},
		{"source": "similar (title a left out, 12 kept)", "inputIds": ids(similar), "orderedIds": ordered(TitlesSource{Kind: TitlesSimilar, ID: "a"}, similar)},
	})
}

func TestGolden_titles_person_id(t *testing.T) {
	var rows []map[string]any
	for _, js := range []string{`{"Id":" 30209 "}`, `{"Id":30209}`, `{"Id":null}`, `{"Id":"a/b"}`, `{"Id":true}`, `{}`} {
		rows = append(rows, map[string]any{"person": json.RawMessage(js), "id": PersonID(person(t, js))})
	}
	assertTableGolden(t, "titles.person_id.json", rows)
}

// emby_titles: the order of the fake server's person_titles() answer, for each
// source (the mapping to MediaItem is session.rs, wave 2+: its shape is checked in
// models_test.go). Posters use image_ref with the fixture server id.
func TestGolden_titles_emby_titles_order_and_posters(t *testing.T) {
	var page QueryResult[BaseItemDto]
	if err := json.Unmarshal(readFixture(t, "person_titles.json"), &page); err != nil {
		t.Fatal(err)
	}
	for name, source := range map[string]TitlesSource{
		"titles.emby_titles.person.json":     {Kind: TitlesPerson, ID: "30209"},
		"titles.emby_titles.collection.json": {Kind: TitlesCollection, ID: "120034"},
		"titles.emby_titles.similar.json":    {Kind: TitlesSimilar, ID: "30207"},
	} {
		t.Run(name, func(t *testing.T) {
			var golden []MediaItem
			if err := json.Unmarshal(readGolden(t, name), &golden); err != nil {
				t.Fatal(err)
			}
			got := OrderTitles(source, slices.Clone(page.Items))
			if len(got) != len(golden) {
				t.Fatalf("%d titles, golden %d", len(got), len(golden))
			}
			for i, item := range got {
				if item.ID != golden[i].ID {
					t.Errorf("#%d: %s, golden %s", i, item.ID, golden[i].ID)
				}
				poster := ImageRefPtr(fixtureServerID, item.ID, ImagePrimary, tagOf(item.ImageTags, "Primary"), PosterWidths)
				if !reflect.DeepEqual(poster, golden[i].Poster) {
					t.Errorf("#%d poster %+v, golden %+v", i, poster, golden[i].Poster)
				}
			}
		})
	}
}

func tagOf(tags map[string]string, kind string) *string {
	if v, ok := tags[kind]; ok {
		return &v
	}
	return nil
}
