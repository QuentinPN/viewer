package emby

// tests.rs, probe errors to the next episode of a series (lines 1064–1930), through
// EmbyService.

import (
	"encoding/json"
	"errors"
	"slices"
	"strings"
	"testing"
)

// brwLastSplit is `server.requests().into_iter().last()` split at `?`.
func brwLastSplit(t *testing.T, server *fakeEmby) (fakeRequest, string, string) {
	t.Helper()
	req := server.last(t)
	path, query, ok := strings.Cut(req.Path, "?")
	if !ok {
		t.Fatalf("pas de requête dans %s", req.Path)
	}
	return req, path, query
}

func brwHasToken(t *testing.T, r fakeRequest) {
	t.Helper()
	if !r.hasToken() {
		t.Fatalf("pas de jeton dans l'en-tête de %s : %q", r.Path, r.Auth)
	}
}

func Test_malformed_or_foreign_server_responses_are_rejected(t *testing.T) {
	server := startFakeEmby(t, func(fakeRequest) (int, string) { return 200, "<html>Emby</html>" })
	fx := newFixture(t)
	_, err := fx.service.Probe(bg, server.URL)
	wantKind(t, err, KindInvalidResponse)

	// AuthenticateByName answered for a different server id than the probe.
	server = startFakeEmby(t, func(r fakeRequest) (int, string) {
		if r.Path == "/emby/System/Info/Public" {
			return 200, fakeFixture("public_info")
		}
		return 200, strings.ReplaceAll(fakeFixture("auth_result"), fakeServerID, "another")
	})
	_, err = fx.service.SignIn(bg, server.URL, "testeur", fakePassword)
	wantKind(t, err, KindInvalidResponse)
	if storedToken(t, fx) != nil {
		t.Fatal("jeton stocké")
	}

	server = startFakeEmby(t, func(fakeRequest) (int, string) { return 503, "" })
	_, err = fx.service.Probe(bg, server.URL)
	var e EmbyError
	if !errors.As(err, &e) || e.Kind != KindServer || e.Status != 503 {
		t.Fatalf("%v", err)
	}
}

func Test_unreachable_server_is_reported(t *testing.T) {
	// Bind then drop to get a local port with nothing listening.
	url := closedPortURL(t)
	fx := newFixture(t)
	_, err := fx.service.Probe(bg, url)
	wantKind(t, err, KindUnreachable)
}

func Test_libraries_keep_films_and_series_with_local_image_urls(t *testing.T) {
	server, fx, _ := signedInBrowser(t)
	libs, err := fx.service.Libraries(bg)
	if err != nil {
		t.Fatal(err)
	}

	var names []string
	for _, l := range libs {
		names = append(names, l.Name)
	}
	if !slices.Equal(names, []string{"Anime", "Films"}) {
		t.Fatal(names)
	}
	raw := marshal(t, libs)
	var js []map[string]any
	if err := json.Unmarshal(raw, &js); err != nil {
		t.Fatal(err)
	}
	if js[0]["kind"] != "tvShows" || js[1]["kind"] != "movies" {
		t.Fatalf("%s", raw)
	}
	image := libs[0].Image
	if image == nil || !strings.HasPrefix(image.Src, "http://emby-img.localhost/"+fakeServerID+"/10/Primary?tag=56f6b8b2_6384&maxWidth=") {
		t.Fatalf("%+v", image)
	}
	if libs[1].Image != nil {
		t.Fatal("no tag, no image")
	}

	brwHasToken(t, server.last(t))
	if !strings.Contains(server.last(t).Auth, `Token="`+fakeToken+`"`) {
		t.Fatal(server.last(t).Auth)
	}
	// Nothing the UI receives points at the server or carries the token.
	text := string(raw)
	if strings.Contains(text, server.URL) || strings.Contains(text, fakeToken) {
		t.Fatal(text)
	}
}

func Test_items_page_queries_one_sorted_page_and_maps_cards(t *testing.T) {
	server, fx, _ := signedInBrowser(t)
	page, err := fx.service.Items(bg, "5", 50, 50, ItemsOptions{})
	if err != nil {
		t.Fatal(err)
	}

	req, path, _ := brwLastSplit(t, server)
	if path != "/emby/Users/"+fakeUserID+"/Items" {
		t.Fatal(path)
	}
	wantPairs(t, req.Path, "ParentId=5", "Recursive=true", "IncludeItemTypes=Movie", "SortBy=SortName",
		"SortOrder=Ascending", "StartIndex=50", "Limit=50", "EnableUserData=true")
	if strings.Contains(req.Path, fakeToken) {
		t.Fatal(req.Path)
	}

	if page.StartIndex != 50 || page.Total != 707 {
		t.Fatalf("%d %d", page.StartIndex, page.Total)
	}
	if page.NextIndex != 53 {
		t.Fatalf("counts the dropped item too: %d", page.NextIndex)
	}
	if len(page.Items) != 2 {
		t.Fatalf("the item with an unsafe id is dropped: %d", len(page.Items))
	}
	movie := page.Items[0]
	if movie.Name != "2 Coeurs" || movie.Year == nil || *movie.Year != 2020 || movie.RuntimeMinutes == nil || *movie.RuntimeMinutes != 101 || !movie.Played {
		t.Fatalf("%+v", movie)
	}
	if movie.Poster == nil || !strings.Contains(movie.Poster.Srcset, " 480w") {
		t.Fatalf("%+v", movie.Poster)
	}
	if page.Items[1].Poster != nil {
		t.Fatal("poster inattendu")
	}
}

func Test_items_refuse_unknown_library_and_clamp_page_size(t *testing.T) {
	server, fx, _ := signedInBrowser(t)
	// Emby would answer an unknown ParentId with the whole server: never send one.
	_, err := fx.service.Items(bg, "999", 0, 50, ItemsOptions{})
	wantErr(t, err, ErrNotFound)
	for _, r := range server.requests() {
		if strings.Contains(r.Path, "/Items") {
			t.Fatal(r.Path)
		}
	}

	if _, err := fx.service.Items(bg, "10", 0, 100_000, ItemsOptions{}); err != nil {
		t.Fatal(err)
	}
	req := server.last(t)
	if !strings.Contains(req.Path, "Limit=200") || !strings.Contains(req.Path, "IncludeItemTypes=Series") {
		t.Fatal(req.Path)
	}
}

func Test_items_send_the_chosen_sort_and_filters(t *testing.T) {
	server, fx, _ := signedInBrowser(t)
	queryOf := func(options ItemsOptions) []string {
		t.Helper()
		if _, err := fx.service.Items(bg, "5", 0, 60, options); err != nil {
			t.Fatal(err)
		}
		_, _, query := brwLastSplit(t, server)
		return strings.Split(query, "&")
	}

	for _, p := range queryOf(ItemsOptions{}) {
		if strings.HasPrefix(p, "Filters=") || strings.HasPrefix(p, "Genres=") {
			t.Fatal(p)
		}
	}

	q := queryOf(ItemsOptions{
		Sort: SortDateAdded, Descending: true, Watch: WatchUnplayed, Favorites: true,
		Genre: ptr("Science-Fiction & Fantastique"), Decade: ptr[int32](1990),
	})
	for _, expected := range []string{
		"Years=1990%2C1991%2C1992%2C1993%2C1994%2C1995%2C1996%2C1997%2C1998%2C1999",
		"SortBy=DateCreated%2CSortName",
		"SortOrder=Descending",
		"Filters=IsUnplayed%2CIsFavorite",
		"Genres=Science-Fiction+%26+Fantastique",
		"ParentId=5",
		"StartIndex=0",
	} {
		if !hasPair(q, expected) {
			t.Errorf("%s in %v", expected, q)
		}
	}

	for _, c := range []struct {
		sort   SortKey
		sortBy string
	}{
		{SortName, "SortBy=SortName"},
		{SortYear, "SortBy=ProductionYear%2CSortName"},
		{SortRating, "SortBy=CommunityRating%2CSortName"},
	} {
		if q := queryOf(ItemsOptions{Sort: c.sort}); !hasPair(q, c.sortBy) {
			t.Errorf("%s in %v", c.sortBy, q)
		}
	}
	for _, c := range []struct {
		watch   WatchFilter
		filters string
	}{
		{WatchPlayed, "Filters=IsPlayed"},
		{WatchInProgress, "Filters=IsResumable"},
	} {
		if q := queryOf(ItemsOptions{Watch: c.watch}); !hasPair(q, c.filters) {
			t.Errorf("%s in %v", c.filters, q)
		}
	}
}

func Test_items_refuse_a_genre_the_server_cannot_filter_on(t *testing.T) {
	server, fx, _ := signedInBrowser(t)
	before := len(server.requests())
	for _, genre := range []string{"", "  ", "Drame|Romance", "a\u0007b", strings.Repeat("x", 101)} {
		_, err := fx.service.Items(bg, "5", 0, 60, ItemsOptions{Genre: ptr(genre)})
		if k, _ := KindOf(err); k != KindInvalidResponse {
			t.Errorf("%q: %v", genre, err)
		}
	}
	if len(server.requests()) != before {
		t.Fatal("nothing sent")
	}
}

func Test_items_refuse_a_decade_the_server_cannot_filter_on(t *testing.T) {
	server, fx, _ := signedInBrowser(t)
	before := len(server.requests())
	for _, decade := range []int32{1995, 1870, 2200, -10} {
		options := ItemsOptions{Decade: ptr(decade)}
		_, err := fx.service.Items(bg, "5", 0, 60, options)
		if k, _ := KindOf(err); k != KindInvalidResponse {
			t.Errorf("%d: %v", decade, err)
		}
		_, err = fx.service.SortNames(bg, "5", options)
		if k, _ := KindOf(err); k != KindInvalidResponse {
			t.Errorf("%d: %v", decade, err)
		}
		_, err = fx.service.RandomItem(bg, "5", options)
		if k, _ := KindOf(err); k != KindInvalidResponse {
			t.Errorf("%d: %v", decade, err)
		}
	}
	if len(server.requests()) != before {
		t.Fatal("nothing sent")
	}
}

func Test_sort_names_follow_the_grid_query_without_paging_or_images(t *testing.T) {
	server, fx, _ := signedInBrowser(t)
	options := ItemsOptions{Sort: SortName, Descending: true, Watch: WatchUnplayed, Genre: ptr("Drame"), Decade: ptr[int32](2010)}
	names, err := fx.service.SortNames(bg, "5", options)
	if err != nil {
		t.Fatal(err)
	}
	// Nothing dropped, so positions stay the server's; a blank sort name falls back.
	want := []SortNameEntry{{"201", "Grand Bleu"}, {"../202", "Sans nom de tri"}, {"203", "Vide"}}
	if !slices.Equal([]SortNameEntry(names), want) {
		t.Fatalf("%v", names)
	}

	req, path, query := brwLastSplit(t, server)
	if path != "/emby/Users/"+fakeUserID+"/Items" {
		t.Fatal(path)
	}
	wantPairs(t, req.Path, "ParentId=5", "Recursive=true", "IncludeItemTypes=Movie", "SortBy=SortName",
		"SortOrder=Descending", "Fields=SortName", "EnableImages=false", "EnableUserData=false",
		"Filters=IsUnplayed", "Genres=Drame")
	q := strings.Split(query, "&")
	if !slices.ContainsFunc(q, func(p string) bool { return strings.HasPrefix(p, "Years=2010%2C") }) {
		t.Fatal(query)
	}
	for _, p := range q {
		if strings.HasPrefix(p, "StartIndex=") || strings.HasPrefix(p, "Limit=") {
			t.Fatal(query)
		}
	}
	if strings.Contains(req.Path, fakeToken) {
		t.Fatal(req.Path)
	}

	_, err = fx.service.SortNames(bg, "999", ItemsOptions{})
	wantErr(t, err, ErrNotFound)
}

func Test_random_item_is_drawn_by_the_server_with_the_grid_filters(t *testing.T) {
	server, fx, _ := signedInBrowser(t)
	options := ItemsOptions{Watch: WatchPlayed, Favorites: true, Genre: ptr("Drame")}
	item, err := fx.service.RandomItem(bg, "5", options)
	if err != nil || item == nil {
		t.Fatal(item, err)
	}
	if item.ID != "101" || item.Year == nil || *item.Year != 2020 {
		t.Fatalf("%+v", item)
	}

	req, _, query := brwLastSplit(t, server)
	wantPairs(t, req.Path, "ParentId=5", "SortBy=Random", "Limit=1", "Filters=IsPlayed%2CIsFavorite", "Genres=Drame")
	for _, p := range strings.Split(query, "&") {
		if strings.HasPrefix(p, "SortOrder=") || strings.HasPrefix(p, "StartIndex=") {
			t.Fatal(query)
		}
	}
	if strings.Contains(req.Path, fakeToken) {
		t.Fatal(req.Path)
	}

	// Nothing matches: no title, no error.
	none, err := fx.service.RandomItem(bg, "5", ItemsOptions{})
	if err != nil || none != nil {
		t.Fatal(none, err)
	}
}

func Test_production_years_are_read_once_without_filters(t *testing.T) {
	server, fx, _ := signedInBrowser(t)
	years, err := fx.service.ProductionYears(bg, "5")
	if err != nil {
		t.Fatal(err)
	}
	if !slices.Equal([]int32(years), []int32{2020, 1994}) {
		t.Fatalf("newest first, no repeat, none impossible: %v", years)
	}

	req, _, query := brwLastSplit(t, server)
	wantPairs(t, req.Path, "ParentId=5", "Recursive=true", "IncludeItemTypes=Movie", "Fields=ProductionYear", "EnableImages=false")
	for _, p := range strings.Split(query, "&") {
		if strings.HasPrefix(p, "Filters=") || strings.HasPrefix(p, "Limit=") {
			t.Fatal(query)
		}
	}

	_, err = fx.service.ProductionYears(bg, "999")
	wantErr(t, err, ErrNotFound)
}

func Test_genres_of_a_library_are_listed_without_unusable_names(t *testing.T) {
	server, fx, _ := signedInBrowser(t)
	genres, err := fx.service.Genres(bg, "5")
	if err != nil {
		t.Fatal(err)
	}
	if !slices.Equal([]string(genres), []string{"Action", "Science-Fiction"}) {
		t.Fatal(genres)
	}

	req, path, _ := brwLastSplit(t, server)
	if path != "/emby/Genres" {
		t.Fatal(path)
	}
	wantPairs(t, req.Path, "UserId="+fakeUserID, "ParentId=5", "IncludeItemTypes=Movie", "Recursive=true")
	if strings.Contains(req.Path, fakeToken) {
		t.Fatal(req.Path)
	}

	// Unknown library: nothing asked about it.
	_, err = fx.service.Genres(bg, "999")
	wantErr(t, err, ErrNotFound)
}

func Test_favorites_row_lists_films_and_series_by_title(t *testing.T) {
	server, fx, _ := signedInBrowser(t)
	items, err := fx.service.Favorites(bg)
	if err != nil {
		t.Fatal(err)
	}
	var ids []string
	for _, i := range items {
		ids = append(ids, i.ID)
	}
	if !slices.Equal(ids, []string{"30207", "80473"}) {
		t.Fatal(ids)
	}
	if c := items[1].UnplayedCount; c == nil || *c != 18 {
		t.Fatalf("%v", c)
	}

	req, path, query := brwLastSplit(t, server)
	if path != "/emby/Users/"+fakeUserID+"/Items" {
		t.Fatal(path)
	}
	wantPairs(t, req.Path, "Filters=IsFavorite", "Recursive=true", "IncludeItemTypes=Movie%2CSeries", "SortBy=SortName", "Limit=16")
	if strings.Contains(query, "ParentId") || strings.Contains(req.Path, fakeToken) {
		t.Fatal(req.Path)
	}
}

func Test_hero_resumes_first_then_fills_with_latest(t *testing.T) {
	server, fx, _ := signedInBrowser(t)
	hero, err := fx.service.Hero(bg)
	if err != nil {
		t.Fatal(err)
	}
	var ids []string
	var reasons []HeroReason
	for _, h := range hero {
		ids = append(ids, h.Detail.ID)
		reasons = append(reasons, h.Reason)
	}
	// Resumed film and series, then latest; duplicates, no backdrop and the sixth dropped.
	if !slices.Equal(ids, []string{"30207", "70185", "80473", "80500", "30301"}) {
		t.Fatal(ids)
	}
	if !slices.Equal(reasons, []HeroReason{HeroResume, HeroResume, HeroLatest, HeroLatest, HeroLatest}) {
		t.Fatal(reasons)
	}

	film := hero[0]
	if film.PlayID == nil || *film.PlayID != "30207" || film.Resume == nil || film.Resume.PositionSeconds != 1831 {
		t.Fatalf("%+v", film)
	}
	if !strings.Contains(film.Backdrop.Src, "/30207/Backdrop?") || film.Episode != nil {
		t.Fatalf("%+v", film)
	}

	// A resumed episode stands for its series, with the series' artwork.
	series := hero[1]
	if series.Detail.ItemType != "Series" || series.Detail.Name != "La casa de papel" {
		t.Fatalf("%+v", series.Detail)
	}
	if series.Detail.Poster == nil || !strings.Contains(series.Detail.Poster.Src, "/70185/Primary?") {
		t.Fatalf("%+v", series.Detail.Poster)
	}
	if series.PlayID == nil || *series.PlayID != "70227" || series.Resume == nil || series.Resume.PositionSeconds != 600 {
		t.Fatalf("%+v", series)
	}
	episode := series.Episode
	if episode == nil || episode.Name != "Le Professeur" || episode.SeasonIndex == nil || *episode.SeasonIndex != 5 || episode.Index == nil || *episode.Index != 2 {
		t.Fatalf("%+v", episode)
	}
	if series.Overview == nil || *series.Overview != "Le casse continue." {
		t.Fatalf("%v", series.Overview)
	}
	if !strings.Contains(series.Backdrop.Src, "/70185/Backdrop?") || series.Logo == nil || !strings.Contains(series.Logo.Src, "/70185/Logo?") {
		t.Fatalf("%+v", series)
	}

	// A latest series plays its first episode not watched, a fully watched one nothing.
	dark := hero[2]
	if dark.PlayID == nil || *dark.PlayID != "80475" || dark.Episode == nil || dark.Episode.Name != "Secrets" {
		t.Fatalf("%+v", dark)
	}
	if dark.Resume == nil || dark.Resume.PositionSeconds != 300 {
		t.Fatalf("%+v", dark.Resume)
	}
	if c := dark.Detail.UnplayedCount; c == nil || *c != 18 {
		t.Fatalf("%v", c)
	}
	if dark.Overview == nil || *dark.Overview != "Winden." || dark.Logo == nil {
		t.Fatalf("%+v", dark)
	}
	if hero[3].PlayID != nil || hero[3].Episode != nil {
		t.Fatalf("%+v", hero[3])
	}
	if hero[4].PlayID == nil || *hero[4].PlayID != "30301" || hero[4].Resume != nil || hero[4].Logo != nil {
		t.Fatalf("%+v", hero[4])
	}

	requests := server.requests()
	i := slices.IndexFunc(requests, func(r fakeRequest) bool { return strings.Contains(r.Path, "ImageTypes=Backdrop") })
	if i < 0 {
		t.Fatal("no latest request")
	}
	latest := requests[i]
	if path, _ := splitQuery(latest.Path); path != "/emby/Users/"+fakeUserID+"/Items" {
		t.Fatal(path)
	}
	wantPairs(t, latest.Path, "Recursive=true", "IncludeItemTypes=Movie%2CSeries", "IsPlayed=false",
		"SortBy=DateCreated", "SortOrder=Descending", "Limit=10")
	i = slices.IndexFunc(requests, func(r fakeRequest) bool { return strings.HasPrefix(r.Path, "/emby/Shows/80473/Episodes") })
	if i < 0 {
		t.Fatal("no episodes request")
	}
	parts := strings.FieldsFunc(requests[i].Path, func(r rune) bool { return r == '?' || r == '&' })
	for _, expected := range []string{"IsPlayed=false", "Limit=1", "UserId=" + fakeUserID} {
		if !slices.Contains(parts, expected) {
			t.Errorf("%s in %s", expected, requests[i].Path)
		}
	}
	// No lookup for films nor for the resumed series.
	for _, r := range requests {
		if strings.HasPrefix(r.Path, "/emby/Shows/70185") {
			t.Fatal(r.Path)
		}
	}
	noTokenInURLs(t, server)
}

func Test_hero_with_a_revoked_token_ends_the_session(t *testing.T) {
	_, fx, revoked := signedInBrowser(t)
	revoked.Store(true)
	_, err := fx.service.Hero(bg)
	wantErr(t, err, ErrSessionExpired)
}

func Test_favorite_is_set_and_cleared_with_the_server_answer(t *testing.T) {
	server, fx, _ := signedInBrowser(t)
	fav, err := fx.service.SetFavorite(bg, "30207", true)
	if err != nil || !fav {
		t.Fatal(fav, err)
	}
	req := server.last(t)
	if req.Method != "POST" || req.Path != "/emby/Users/"+fakeUserID+"/FavoriteItems/30207" {
		t.Fatalf("%s %s", req.Method, req.Path)
	}

	fav, err = fx.service.SetFavorite(bg, "30207", false)
	if err != nil || fav {
		t.Fatal(fav, err)
	}
	req = server.last(t)
	if req.Method != "DELETE" || req.Path != "/emby/Users/"+fakeUserID+"/FavoriteItems/30207" {
		t.Fatalf("%s %s", req.Method, req.Path)
	}

	_, err = fx.service.SetFavorite(bg, "99999", true)
	wantErr(t, err, ErrNotFound)

	// An id that is not one never reaches the server.
	before := len(server.requests())
	for _, id := range []string{"", "../30207", "30207?x=1"} {
		_, err := fx.service.SetFavorite(bg, id, true)
		if k, _ := KindOf(err); k != KindInvalidResponse {
			t.Errorf("%s: %v", id, err)
		}
	}
	if len(server.requests()) != before {
		t.Fatal("sent")
	}
}

func Test_favorite_with_a_revoked_token_ends_the_session(t *testing.T) {
	_, fx, revoked := signedInBrowser(t)
	revoked.Store(true)
	_, err := fx.service.SetFavorite(bg, "30207", true)
	wantErr(t, err, ErrSessionExpired)
}

func Test_played_is_set_and_cleared_then_read_back(t *testing.T) {
	server, fx, _ := signedInBrowser(t)
	// The state returned is the one read back: `movie_detail` is half watched.
	state, err := fx.service.SetPlayed(bg, "30207", true)
	if err != nil {
		t.Fatal(err)
	}
	if state.ItemID != "30207" || state.Resume == nil || state.Resume.Fraction == nil {
		t.Fatalf("%+v", state)
	}
	requests := server.requests()
	mark, read := requests[len(requests)-2], requests[len(requests)-1]
	if mark.Method != "POST" {
		t.Fatal(mark.Method)
	}
	path, query := splitQuery(mark.Path)
	if path != "/emby/Users/"+fakeUserID+"/PlayedItems/30207" {
		t.Fatal(path)
	}
	date, ok := strings.CutPrefix(query, "DatePlayed=")
	if !ok || len(date) != 14 || strings.Trim(date, "0123456789") != "" {
		t.Fatal(query)
	}
	brwHasToken(t, mark)
	if read.Method != "GET" || !strings.HasPrefix(read.Path, "/emby/Users/"+fakeUserID+"/Items/30207") {
		t.Fatalf("%s %s", read.Method, read.Path)
	}

	if _, err := fx.service.SetPlayed(bg, "30207", false); err != nil {
		t.Fatal(err)
	}
	requests = server.requests()
	mark = requests[len(requests)-2]
	if mark.Method != "DELETE" || mark.Path != "/emby/Users/"+fakeUserID+"/PlayedItems/30207" {
		t.Fatalf("%s %s", mark.Method, mark.Path)
	}
	brwHasToken(t, mark)

	_, err = fx.service.SetPlayed(bg, "99999", true)
	wantErr(t, err, ErrNotFound)

	before := len(server.requests())
	for _, id := range []string{"", "../30207", "30207?x=1"} {
		_, err := fx.service.SetPlayed(bg, id, true)
		if k, _ := KindOf(err); k != KindInvalidResponse {
			t.Errorf("%s: %v", id, err)
		}
	}
	if len(server.requests()) != before {
		t.Fatal("sent")
	}
	noTokenInURLs(t, server)
}

func Test_played_change_keeps_the_answer_when_it_cannot_be_read_back(t *testing.T) {
	server, fx, _ := signedInBrowser(t)
	state, err := fx.service.SetPlayed(bg, "30299", true)
	if err != nil {
		t.Fatal(err)
	}
	if state.ItemID != "30299" || !state.Played || state.Resume != nil {
		t.Fatalf("%+v", state)
	}
	state, err = fx.service.HideFromResume(bg, "30299")
	if err != nil {
		t.Fatal(err)
	}
	if state.Played {
		t.Fatalf("%+v", state)
	}
	// The answer carries no runtime: a position without a fraction.
	if state.Resume == nil || state.Resume.PositionSeconds != 1831 || state.Resume.Fraction != nil {
		t.Fatalf("%+v", state.Resume)
	}
	noTokenInURLs(t, server)
}

func Test_hide_from_resume_posts_hide_true(t *testing.T) {
	server, fx, _ := signedInBrowser(t)
	state, err := fx.service.HideFromResume(bg, "30207")
	if err != nil {
		t.Fatal(err)
	}
	if state.ItemID != "30207" {
		t.Fatal(state.ItemID)
	}
	requests := server.requests()
	hide := requests[len(requests)-2]
	if hide.Method != "POST" || hide.Path != "/emby/Users/"+fakeUserID+"/Items/30207/HideFromResume?Hide=true" {
		t.Fatalf("%s %s", hide.Method, hide.Path)
	}
	brwHasToken(t, hide)

	_, err = fx.service.HideFromResume(bg, "99999")
	wantErr(t, err, ErrNotFound)
	before := len(server.requests())
	_, err = fx.service.HideFromResume(bg, "../30207")
	wantKind(t, err, KindInvalidResponse)
	if len(server.requests()) != before {
		t.Fatal("sent")
	}
	noTokenInURLs(t, server)
}

func Test_watched_changes_with_a_revoked_token_end_the_session(t *testing.T) {
	_, fx, revoked := signedInBrowser(t)
	revoked.Store(true)
	_, err := fx.service.SetPlayed(bg, "30207", true)
	wantErr(t, err, ErrSessionExpired)
	_, fx, revoked = signedInBrowser(t)
	revoked.Store(true)
	_, err = fx.service.HideFromResume(bg, "30207")
	wantErr(t, err, ErrSessionExpired)
}

func Test_playlists_are_listed_by_name_with_unusable_entries_dropped(t *testing.T) {
	server, fx, _ := signedInBrowser(t)
	list, err := fx.service.Playlists(bg)
	if err != nil {
		t.Fatal(err)
	}
	if !slices.Equal([]Playlist(list), []Playlist{{"60001", "À revoir"}, {"60002", "Sans titre"}}) {
		t.Fatalf("%v", list)
	}
	sent, path, query := brwLastSplit(t, server)
	if sent.Method != "GET" || path != "/emby/Users/"+fakeUserID+"/Items" {
		t.Fatalf("%s %s", sent.Method, path)
	}
	if !strings.Contains(query, "IncludeItemTypes=Playlist") || !strings.Contains(query, "Recursive=true") {
		t.Fatal(query)
	}
	brwHasToken(t, sent)
	noTokenInURLs(t, server)
}

func Test_home_playlists_keep_video_ones_once_with_their_count(t *testing.T) {
	server, fx, _ := signedInBrowser(t)
	cards, err := fx.service.HomePlaylists(bg)
	if err != nil {
		t.Fatal(err)
	}
	if len(cards) != 2 || cards[0].ID != "60001" || cards[0].Name != "À revoir" || cards[0].Count == nil || *cards[0].Count != 3 ||
		cards[1].ID != "60002" || cards[1].Name != "Sans titre" || cards[1].Count != nil {
		t.Fatalf("%+v", cards)
	}
	if cards[0].Poster == nil || cards[1].Poster != nil {
		t.Fatalf("%+v", cards)
	}
	if r := cards[0].AspectRatio; r == nil || *r != 1.0 {
		t.Fatalf("%v", r)
	}
	_, path, query := brwLastSplit(t, server)
	if path != "/emby/Users/"+fakeUserID+"/Items" {
		t.Fatal(path)
	}
	for _, part := range []string{"IncludeItemTypes=Playlist", "Recursive=true", "SortBy=SortName", "Limit=50", "Fields=ChildCount%2CPrimaryImageAspectRatio"} {
		if !strings.Contains(query, part) {
			t.Errorf("%s in %s", part, query)
		}
	}
	if strings.Contains(query, "MediaTypes") {
		t.Fatal(query)
	}
	noTokenInURLs(t, server)
}

func Test_playlist_entries_are_films_and_episodes_once_in_order(t *testing.T) {
	server, fx, revoked := signedInBrowser(t)
	entries, err := fx.service.PlaylistItems(bg, "60001")
	if err != nil {
		t.Fatal(err)
	}
	var ids []string
	for _, e := range entries {
		ids = append(ids, e.ID)
	}
	if !slices.Equal(ids, []string{"70229", "30207"}) {
		t.Fatal(ids)
	}
	if n := entries[0].SeriesName; n == nil || *n != "La casa de papel" || !entries[0].Played || entries[1].Resume == nil {
		t.Fatalf("%+v", entries)
	}
	sent, path, query := brwLastSplit(t, server)
	if sent.Method != "GET" || path != "/emby/Playlists/60001/Items" {
		t.Fatalf("%s %s", sent.Method, path)
	}
	if !strings.Contains(query, "UserId="+fakeUserID) || !strings.Contains(query, "Limit=300") {
		t.Fatal(query)
	}

	before := len(server.requests())
	for _, bad := range []string{"", "../60001", "60001&UserId=x"} {
		_, err := fx.service.PlaylistItems(bg, bad)
		wantKind(t, err, KindInvalidResponse)
	}
	if len(server.requests()) != before {
		t.Fatal("sent")
	}

	_, err = fx.service.PlaylistItems(bg, "69999")
	wantErr(t, err, ErrNotFound)

	revoked.Store(true)
	_, err = fx.service.PlaylistItems(bg, "60001")
	wantErr(t, err, ErrSessionExpired)
	if storedToken(t, fx) != nil {
		t.Fatal("jeton gardé")
	}
}

func Test_an_item_is_added_to_a_playlist(t *testing.T) {
	server, fx, _ := signedInBrowser(t)
	added, err := fx.service.AddToPlaylist(bg, "60001", "30207")
	if err != nil || added != 1 {
		t.Fatal(added, err)
	}
	sent := server.last(t)
	if sent.Method != "POST" || sent.Path != "/emby/Playlists/60001/Items?Ids=30207&UserId="+fakeUserID {
		t.Fatalf("%s %s", sent.Method, sent.Path)
	}
	brwHasToken(t, sent)

	_, err = fx.service.AddToPlaylist(bg, "69999", "30207")
	wantErr(t, err, ErrNotFound)
	before := len(server.requests())
	for _, c := range [][2]string{{"", "30207"}, {"../60001", "30207"}, {"60001", "30207&Ids=1"}} {
		_, err := fx.service.AddToPlaylist(bg, c[0], c[1])
		wantKind(t, err, KindInvalidResponse)
	}
	if len(server.requests()) != before {
		t.Fatal("sent")
	}
	noTokenInURLs(t, server)
}

func Test_a_video_playlist_is_created_with_the_item(t *testing.T) {
	server, fx, _ := signedInBrowser(t)
	created, err := fx.service.CreatePlaylist(bg, "  Soirée ciné ", "30207")
	if err != nil {
		t.Fatal(err)
	}
	if created.ID != "60010" || created.Name != "Soirée ciné" || created.Added != 1 {
		t.Fatalf("%+v", created)
	}
	sent := server.last(t)
	if sent.Method != "POST" || sent.Path != "/emby/Playlists?Name=Soir%C3%A9e+cin%C3%A9&Ids=30207&MediaType=Video&UserId="+fakeUserID {
		t.Fatalf("%s %s", sent.Method, sent.Path)
	}
	brwHasToken(t, sent)

	// Names and ids are checked before anything is sent.
	before := len(server.requests())
	for _, c := range [][2]string{{"   ", "30207"}, {"Liste\nde nuit", "30207"}, {strings.Repeat("a", 201), "30207"}, {"Soirée", "../30207"}} {
		_, err := fx.service.CreatePlaylist(bg, c[0], c[1])
		wantKind(t, err, KindInvalidResponse)
	}
	if len(server.requests()) != before {
		t.Fatal("sent")
	}
	if _, err := fx.service.CreatePlaylist(bg, strings.Repeat("é", 200), "30207"); err != nil {
		t.Fatal(err)
	}
	noTokenInURLs(t, server)
}

func Test_playlist_calls_with_a_revoked_token_end_the_session(t *testing.T) {
	_, fx, revoked := signedInBrowser(t)
	revoked.Store(true)
	_, err := fx.service.AddToPlaylist(bg, "60001", "30207")
	wantErr(t, err, ErrSessionExpired)
	if fx.service.CurrentUser() != nil {
		t.Fatal("utilisateur gardé")
	}
	for call := range 2 {
		_, fx, revoked := signedInBrowser(t)
		revoked.Store(true)
		var err error
		if call == 0 {
			_, err = fx.service.Playlists(bg)
		} else {
			_, err = fx.service.CreatePlaylist(bg, "Soirée", "30207")
		}
		wantErr(t, err, ErrSessionExpired)
		if storedToken(t, fx) != nil {
			t.Fatal("jeton gardé")
		}
	}
}

func Test_next_episode_of_a_series_is_its_first_unwatched_one(t *testing.T) {
	server, fx, _ := signedInBrowser(t)
	next, err := fx.service.SeriesNextEpisode(bg, "80473")
	if err != nil || next == nil {
		t.Fatal(next, err)
	}
	if next.ID != "80475" || next.Resume == nil || next.Played {
		t.Fatalf("%+v", next)
	}
	_, path, query := brwLastSplit(t, server)
	if path != "/emby/Shows/80473/Episodes" || !strings.Contains(query, "IsPlayed=false") || !strings.Contains(query, "UserId="+fakeUserID) {
		t.Fatal(path, query)
	}

	none, err := fx.service.SeriesNextEpisode(bg, "80500")
	if err != nil || none != nil {
		t.Fatal(none, err)
	}
	before := len(server.requests())
	_, err = fx.service.SeriesNextEpisode(bg, "../80473")
	wantKind(t, err, KindInvalidResponse)
	if len(server.requests()) != before {
		t.Fatal("sent")
	}
	noTokenInURLs(t, server)
}
