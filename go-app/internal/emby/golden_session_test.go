package emby

// golden.rs `session_cases`, `browse_cases` and the service half of `error_cases`,
// replayed through EmbyService on the same fake servers. The fake server address is
// written http://emby.test and the token TOKEN_PLACEHOLDER, as golden.rs does; a
// token left in a value fails the test.

import (
	"encoding/json"
	"errors"
	"strings"
	"testing"
)

// goldenFix rewrites the fake server addresses and the token as golden.rs does.
func goldenFix(t *testing.T, urls ...string) func([]byte) []byte {
	return func(b []byte) []byte {
		t.Helper()
		out := string(b)
		for _, u := range urls {
			out = strings.ReplaceAll(out, u, "http://emby.test")
		}
		out = strings.ReplaceAll(out, fakeToken, "TOKEN_PLACEHOLDER")
		if strings.Contains(out, fakeToken) || strings.Contains(out, "SECRET") {
			t.Fatalf("secret dans le golden : %s", out)
		}
		return []byte(out)
	}
}

// goldenOf compares v, rewritten by fix, to the golden name (key order kept).
func goldenOf(t *testing.T, fix func([]byte) []byte, name string, v any, err error) {
	t.Helper()
	if err != nil {
		t.Errorf("%s: %v", name, err)
		return
	}
	got := fix(marshal(t, v))
	if e := orderedEqual(readGolden(t, name), got); e != nil {
		t.Errorf("%s: %v\ngo: %s", name, e, got)
	}
}

// goldenErr compares the EmbyError of a refused call to the golden name.
func goldenErr(t *testing.T, fix func([]byte) []byte, name string, err error) {
	t.Helper()
	var e EmbyError
	if !errors.As(err, &e) {
		t.Errorf("%s: pas une EmbyError : %v", name, err)
		return
	}
	goldenOf(t, fix, name, e, nil)
}

func TestGolden_session_cases(t *testing.T) {
	server := startFakeEmby(t, fakeEmbyLike(204))
	fix := goldenFix(t, server.URL)
	fx := newFixture(t)
	s := fx.service

	probed, err := s.Probe(bg, server.URL)
	goldenOf(t, fix, "models.emby_probe.json", probed, err)
	user, err := s.SignIn(bg, server.URL, "testeur", fakePassword)
	goldenOf(t, fix, "models.emby_sign_in.json", user, err)
	info, err := s.ServerInfo(bg)
	goldenOf(t, fix, "models.emby_server_info.json", info, err)
	restored, err := s.Restore(bg)
	goldenOf(t, fix, "models.emby_restore.authenticated.json", restored, err)
	goldenOf(t, fix, "models.emby_last_server.json", s.LastServerURL(), nil)
	if err := s.ChangeServer(); err != nil {
		t.Fatal(err)
	}
	goldenOf(t, fix, "models.emby_shelf.json", s.Shelf(), nil)

	empty := newFixture(t)
	signedOut, err := empty.service.Restore(bg)
	goldenOf(t, fix, "models.emby_restore.signed_out.json", signedOut, err)

	// golden.rs builds the last two statuses by hand; here they come from Restore: a
	// revoked token, then a server that never answers.
	revoked := newFixture(t)
	mustSignIn(t, revoked, server.URL)
	if err := revoked.store.Save(CredentialAccount(fakeServerID, fakeUserID), NewAccessToken("revoked")); err != nil {
		t.Fatal(err)
	}
	status, err := reopen(revoked.store, revoked.path).service.Restore(bg)
	goldenOf(t, fix, "models.emby_restore.authentication_required.json", status, err)

	hung := hungServer(t)
	offline := newFixture(t)
	mustSignIn(t, offline, server.URL)
	offline = shortTimeouts(retarget(t, offline, hung))
	status, err = offline.service.Restore(bg)
	goldenOf(t, goldenFix(t, server.URL, hung), "models.emby_restore.server_unreachable.json", status, err)
}

func TestGolden_browse_cases(t *testing.T) {
	server, fx, revoked := signedInBrowser(t)
	fix := goldenFix(t, server.URL)
	s := fx.service
	g := func(name string, v any, err error) { t.Helper(); goldenOf(t, fix, name, v, err) }

	libraries, err := s.Libraries(bg)
	g("models.emby_libraries.json", libraries, err)
	page, err := s.Items(bg, "5", 50, 50, DefaultItemsOptions())
	g("models.emby_items.movies_page.json", page, err)
	names, err := s.SortNames(bg, "5", DefaultItemsOptions())
	g("models.emby_sort_names.json", names, err)
	drama := DefaultItemsOptions()
	drama.Watch, drama.Favorites, drama.Genre = WatchPlayed, true, ptr("Drame")
	found, err := s.RandomItem(bg, "5", drama)
	g("models.emby_random_item.found.json", found, err)
	none, err := s.RandomItem(bg, "5", DefaultItemsOptions())
	g("models.emby_random_item.none.json", none, err)
	years, err := s.ProductionYears(bg, "5")
	g("models.emby_production_years.json", years, err)
	genres, err := s.Genres(bg, "5")
	g("models.emby_genres.json", genres, err)
	resume, err := s.ContinueWatching(bg)
	g("models.emby_continue_watching.json", resume, err)
	nextUp, err := s.NextUp(bg)
	g("models.emby_next_up.json", nextUp, err)
	hero, err := s.Hero(bg)
	g("models.emby_hero.json", hero, err)
	favorites, err := s.Favorites(bg)
	g("models.emby_favorites.json", favorites, err)
	latest, err := s.Latest(bg, "5")
	g("models.emby_latest.json", latest, err)
	search, err := s.Search(bg, " Heures\u0007 chrono\n")
	g("models.emby_search.json", search, err)
	for _, c := range []struct {
		variant string
		on      bool
	}{{"set", true}, {"cleared", false}} {
		v, err := s.SetFavorite(bg, "30207", c.on)
		g("models.emby_set_favorite."+c.variant+".json", v, err)
	}
	for _, c := range []struct {
		variant string
		on      bool
	}{{"played", true}, {"unplayed", false}} {
		v, err := s.SetPlayed(bg, "30207", c.on)
		g("models.emby_set_played."+c.variant+".json", v, err)
	}
	hidden, err := s.HideFromResume(bg, "30207")
	g("models.emby_hide_from_resume.json", hidden, err)
	some, err := s.SeriesNextEpisode(bg, "80473")
	g("models.emby_series_next_episode.some.json", some, err)
	noEpisode, err := s.SeriesNextEpisode(bg, "80500")
	g("models.emby_series_next_episode.none.json", noEpisode, err)

	playlists, err := s.Playlists(bg)
	g("models.emby_playlists.json", playlists, err)
	home, err := s.HomePlaylists(bg)
	g("models.emby_home_playlists.json", home, err)
	entries, err := s.PlaylistItems(bg, "60001")
	g("models.emby_playlist_items.json", entries, err)
	added, err := s.AddToPlaylist(bg, "60001", "30207")
	g("models.emby_add_to_playlist.json", added, err)
	created, err := s.CreatePlaylist(bg, " Soirée ciné ", "30207")
	g("models.emby_create_playlist.json", created, err)

	for _, c := range []struct{ variant, id string }{
		{"movie", "30207"}, {"series", "80473"}, {"episode", "80475"}, {"bare_episode", "80476"}, {"two_versions", "30500"},
	} {
		detail, err := s.ItemDetail(bg, c.id)
		g("models.emby_item_detail."+c.variant+".json", detail, err)
	}
	seasons, err := s.Seasons(bg, "80473")
	g("models.emby_seasons.json", seasons, err)
	episodes, err := s.Episodes(bg, "80473", "80474", 0, 500)
	g("models.emby_episodes.json", episodes, err)
	for _, c := range []struct{ variant, id string }{{"with_resume", "30207"}, {"episode", "80475"}} {
		state, err := s.PlaybackState(bg, c.id)
		g("models.emby_playback_state."+c.variant+".json", state, err)
	}

	// Chapters, local trailer, titles of a person, a collection, similar titles.
	film, err := s.Chapters(bg, "30207")
	g("chapters.emby_chapters.film.json", film, err)
	noChapters, err := s.Chapters(bg, "80473")
	g("chapters.emby_chapters.none.json", noChapters, err)
	trailer, err := s.LocalTrailer(bg, "30207")
	g("titles.emby_local_trailer.json", trailer, err)
	for _, c := range []struct {
		variant string
		source  TitlesSource
	}{
		{"person", TitlesSource{Kind: TitlesPerson, ID: "30209"}},
		{"collection", TitlesSource{Kind: TitlesCollection, ID: "120034"}},
		{"similar", TitlesSource{Kind: TitlesSimilar, ID: "30207"}},
	} {
		titles, err := s.Titles(bg, c.source)
		g("titles.emby_titles."+c.variant+".json", titles, err)
	}

	// Quality setting of the session (a local address here).
	quality, err := s.PlaybackQuality()
	g("quality.emby_playback_quality.default.json", quality, err)
	set, err := s.SetPlaybackQuality(QualityCaps{Local: ptr[uint64](40_000_000), Remote: ptr[uint64](4_000_000)})
	g("quality.emby_set_playback_quality.json", set, err)

	// Errors of the same session, last: the token is then revoked.
	_, err = s.ItemDetail(bg, "999")
	goldenErr(t, fix, "errors.service.not_found.json", err)
	_, err = s.Items(bg, "999", 0, 50, DefaultItemsOptions())
	goldenErr(t, fix, "errors.service.unknown_library.json", err)
	_, err = s.SeriesNextEpisode(bg, "../80473")
	goldenErr(t, fix, "errors.service.invalid_id.json", err)
	_, err = s.CreatePlaylist(bg, "   ", "30207")
	goldenErr(t, fix, "errors.service.invalid_playlist_name.json", err)
	_, err = s.SetPlaybackQuality(QualityCaps{Local: ptr[uint64](1)})
	goldenErr(t, fix, "errors.service.quality_not_offered.json", err)
	revoked.Store(true)
	_, err = s.Libraries(bg)
	goldenErr(t, fix, "errors.service.session_expired.json", err)
}

// The service half of golden.rs `error_cases`.
func TestGolden_service_error_cases(t *testing.T) {
	server := startFakeEmby(t, fakeEmbyLike(204))
	fix := goldenFix(t, server.URL)
	fx := newFixture(t)
	_, err := fx.service.SignIn(bg, server.URL, "testeur", "nope")
	goldenErr(t, fix, "errors.service.invalid_credentials.json", err)
	_, err = fx.service.Libraries(bg)
	goldenErr(t, fix, "errors.service.not_signed_in.json", err)
	down := startFakeEmby(t, func(fakeRequest) (int, string) { return 503, "" })
	_, err = fx.service.Probe(bg, down.URL)
	goldenErr(t, fix, "errors.service.server_error.json", err)
	// The text quotes serde: compared on the kind only (index.json).
	foreign := startFakeEmby(t, func(fakeRequest) (int, string) { return 200, "<html>Emby</html>" })
	_, err = fx.service.Probe(bg, foreign.URL)
	var golden struct{ Kind ErrorKind }
	if e := json.Unmarshal(readGolden(t, "errors.service.invalid_response.json"), &golden); e != nil {
		t.Fatal(e)
	}
	wantKind(t, err, golden.Kind)
}
