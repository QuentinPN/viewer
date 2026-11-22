package emby

import (
	"bytes"
	"encoding/json"
	"reflect"
	"strings"
	"testing"
)

// emby/tests.rs options_from_the_ui_default_and_parse
func Test_options_from_the_ui_default_and_parse(t *testing.T) {
	var parsed ItemsOptions
	if err := json.Unmarshal([]byte(`{"sort":"rating","descending":true,"watch":"inProgress"}`), &parsed); err != nil {
		t.Fatal(err)
	}
	if parsed.Sort != SortRating {
		t.Fatalf("sort %q", parsed.Sort)
	}
	if !parsed.Descending || parsed.Favorites || parsed.Genre != nil {
		t.Fatalf("%+v", parsed)
	}
	if parsed.Decade != nil {
		t.Fatal("decade")
	}
	var withDecade ItemsOptions
	if err := json.Unmarshal([]byte(`{"decade":1980}`), &withDecade); err != nil {
		t.Fatal(err)
	}
	if withDecade.Decade == nil || *withDecade.Decade != 1980 {
		t.Fatalf("decade %v", withDecade.Decade)
	}
	if parsed.Watch != WatchInProgress {
		t.Fatalf("watch %q", parsed.Watch)
	}
	var empty ItemsOptions
	if err := json.Unmarshal([]byte(`{}`), &empty); err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(empty, DefaultItemsOptions()) {
		t.Fatalf("%+v", empty)
	}
	var bad ItemsOptions
	if err := json.Unmarshal([]byte(`{"sort":"random"}`), &bad); err == nil {
		t.Fatal("unknown sort accepted")
	}
}

// serde rules kept by the hand-written decoder: exact key case, null refused for
// non-Option fields, null accepted for Option ones, unknown keys ignored.
func TestItemsOptionsDecodeLikeSerde(t *testing.T) {
	for _, bad := range []string{
		`{"sort":null}`, `{"descending":null}`, `{"watch":"later"}`, `{"decade":1980.5}`,
		`{"decade":"1980"}`, `{"genre":3}`, `[]`, `null`,
	} {
		var o ItemsOptions
		if err := json.Unmarshal([]byte(bad), &o); err == nil {
			t.Errorf("%s accepted: %+v", bad, o)
		}
	}
	var o ItemsOptions
	if err := json.Unmarshal([]byte(`{"Sort":"year","genre":null,"decade":null,"other":1}`), &o); err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(o, DefaultItemsOptions()) {
		t.Fatalf("%+v", o)
	}
}

func TestItemsOptionsQuery(t *testing.T) {
	o := DefaultItemsOptions()
	if o.SortOrder() != "Ascending" || o.Filters() != "" {
		t.Fatal(o.SortOrder(), o.Filters())
	}
	if _, ok := o.Years(); ok {
		t.Fatal("years without decade")
	}
	d := int32(1990)
	o = ItemsOptions{Sort: SortYear, Descending: true, Watch: WatchPlayed, Favorites: true, Decade: &d}
	if o.SortOrder() != "Descending" || o.Filters() != "IsPlayed,IsFavorite" || o.Sort.SortBy() != "ProductionYear,SortName" {
		t.Fatal(o.SortOrder(), o.Filters())
	}
	if y, ok := o.Years(); !ok || y != "1990,1991,1992,1993,1994,1995,1996,1997,1998,1999" {
		t.Fatal(y)
	}
	bad := int32(1995)
	o.Decade = &bad
	if _, ok := o.Years(); ok {
		t.Fatal("unusable decade")
	}
	if !IsUsableDecade(1880) || !IsUsableDecade(2190) || IsUsableDecade(1870) || IsUsableDecade(2200) || IsUsableDecade(-10) {
		t.Fatal("decades")
	}
	if !IsUsableGenre("Drame") || IsUsableGenre(" ") || IsUsableGenre("a|b") || IsUsableGenre("a\u0007") ||
		!IsUsableGenre(strings.Repeat("é", 100)) || IsUsableGenre(strings.Repeat("é", 101)) {
		t.Fatal("genres")
	}
	if k, ok := LibraryKindFromCollectionType(ptr("tvshows")); !ok || k != LibraryTvShows || k.ItemTypes() != "Series" {
		t.Fatal(k)
	}
	if _, ok := LibraryKindFromCollectionType(ptr("music")); ok {
		t.Fatal("music")
	}
	if _, ok := LibraryKindFromCollectionType(nil); ok {
		t.Fatal("nil")
	}
	if MethodDirectStream.AsEmby() != "DirectStream" {
		t.Fatal("as_emby")
	}
}

func ptr[T any](v T) *T { return &v }

// Every IPC struct writes every key, and an empty Vec as [] (never null), even from
// its zero value: what wave 2 builds with nil slices must still match serde.
func TestIPCZeroValuesKeepEveryKeyAndEmptyLists(t *testing.T) {
	values := []any{
		ServerSummary{}, SignedInUser{}, SavedServer{}, ShelfServer{}, SortNameEntry{}, ImageRef{},
		Library{}, MediaItem{}, WatchItem{}, SearchResults{}, ItemPage{}, HeroItem{}, HeroEpisode{},
		EpisodeContext{}, EpisodeLink{}, ResumePoint{}, CastMember{}, VideoInfo{}, StreamInfo{},
		MediaInfo{}, ItemDetail{}, Season{}, Episode{}, EpisodePage{}, PlaybackStart{}, PlaybackTitle{},
		PlaybackStopped{}, Playlist{}, PlaylistCard{}, PlaylistCreated{}, CollectionRef{}, Chapter{},
		PlaybackQuality{}, QualityCaps{},
	}
	for _, v := range values {
		typ := reflect.TypeOf(v)
		var m map[string]json.RawMessage
		if err := json.Unmarshal(marshal(t, v), &m); err != nil {
			t.Fatalf("%s: %v", typ, err)
		}
		if len(m) != typ.NumField() {
			t.Errorf("%s: %d keys for %d fields", typ, len(m), typ.NumField())
		}
		for i := range typ.NumField() {
			f := typ.Field(i)
			name := strings.Split(f.Tag.Get("json"), ",")[0]
			raw, ok := m[name]
			if !ok {
				t.Errorf("%s.%s: key %q missing", typ, f.Name, name)
				continue
			}
			if f.Type.Kind() == reflect.Slice && !bytes.Equal(raw, []byte("[]")) {
				t.Errorf("%s.%s: empty list written %s", typ, f.Name, raw)
			}
		}
	}
	if got := string(marshal(t, List[int](nil))); got != "[]" {
		t.Fatalf("nil List = %s", got)
	}
}

// Typed results of the commands, shape only: the golden is decoded into the Go type
// (unknown keys refused) and must serialize back identically. The values themselves
// are built by session.rs (wave 2+), with the fake server of emby/tests.rs.
func TestGolden_models_shapes(t *testing.T) {
	cases := map[string]func() any{
		"models.emby_add_to_playlist.json":                  func() any { return new(uint32) },
		"models.emby_continue_watching.json":                func() any { return new(List[WatchItem]) },
		"models.emby_create_playlist.json":                  func() any { return new(PlaylistCreated) },
		"models.emby_episodes.json":                         func() any { return new(EpisodePage) },
		"models.emby_favorites.json":                        func() any { return new(List[MediaItem]) },
		"models.emby_genres.json":                           func() any { return new(List[string]) },
		"models.emby_hero.json":                             func() any { return new(List[HeroItem]) },
		"models.emby_hide_from_resume.json":                 func() any { return new(PlaybackStopped) },
		"models.emby_home_playlists.json":                   func() any { return new(List[PlaylistCard]) },
		"models.emby_item_detail.bare_episode.json":         func() any { return new(ItemDetail) },
		"models.emby_item_detail.episode.json":              func() any { return new(ItemDetail) },
		"models.emby_item_detail.movie.json":                func() any { return new(ItemDetail) },
		"models.emby_item_detail.series.json":               func() any { return new(ItemDetail) },
		"models.emby_item_detail.two_versions.json":         func() any { return new(ItemDetail) },
		"models.emby_items.movies_page.json":                func() any { return new(ItemPage) },
		"models.emby_last_server.json":                      func() any { return new(*string) },
		"models.emby_latest.json":                           func() any { return new(List[MediaItem]) },
		"models.emby_libraries.json":                        func() any { return new(List[Library]) },
		"models.emby_next_up.json":                          func() any { return new(List[WatchItem]) },
		"models.emby_playback_state.episode.json":           func() any { return new(PlaybackStopped) },
		"models.emby_playback_state.with_resume.json":       func() any { return new(PlaybackStopped) },
		"models.emby_playlist_items.json":                   func() any { return new(List[WatchItem]) },
		"models.emby_playlists.json":                        func() any { return new(List[Playlist]) },
		"models.emby_probe.json":                            func() any { return new(ServerSummary) },
		"models.emby_production_years.json":                 func() any { return new(List[int32]) },
		"models.emby_random_item.found.json":                func() any { return new(*MediaItem) },
		"models.emby_random_item.none.json":                 func() any { return new(*MediaItem) },
		"models.emby_search.json":                           func() any { return new(SearchResults) },
		"models.emby_seasons.json":                          func() any { return new(List[Season]) },
		"models.emby_series_next_episode.none.json":         func() any { return new(*WatchItem) },
		"models.emby_series_next_episode.some.json":         func() any { return new(*WatchItem) },
		"models.emby_server_info.json":                      func() any { return new(ServerSummary) },
		"models.emby_set_favorite.cleared.json":             func() any { return new(bool) },
		"models.emby_set_favorite.set.json":                 func() any { return new(bool) },
		"models.emby_set_played.played.json":                func() any { return new(PlaybackStopped) },
		"models.emby_set_played.unplayed.json":              func() any { return new(PlaybackStopped) },
		"models.emby_shelf.json":                            func() any { return new(List[ShelfServer]) },
		"models.emby_sign_in.json":                          func() any { return new(SignedInUser) },
		"models.emby_sort_names.json":                       func() any { return new(List[SortNameEntry]) },
		"titles.emby_titles.collection.json":                func() any { return new(List[MediaItem]) },
		"titles.emby_titles.person.json":                    func() any { return new(List[MediaItem]) },
		"titles.emby_titles.similar.json":                   func() any { return new(List[MediaItem]) },
		"titles.emby_local_trailer.json":                    func() any { return new(*string) },
		"playback.emby_play.direct_play.json":               func() any { return new(PlaybackStart) },
		"playback.emby_play.direct_play_resume.json":        func() any { return new(PlaybackStart) },
		"playback.emby_play.direct_play_subtitles_off.json": func() any { return new(PlaybackStart) },
		"playback.emby_play.episode_direct_play.json":       func() any { return new(PlaybackStart) },
		"playback.emby_play.fallback_transcode.json":        func() any { return new(PlaybackStart) },
		"playback.emby_play.transcode_english.json":         func() any { return new(PlaybackStart) },
		"playback.emby_play.transcode_no_title.json":        func() any { return new(PlaybackStart) },
		"playback.emby_play.two_versions_second.json":       func() any { return new(PlaybackStart) },
		"playback.device_profile.json":                      func() any { return new(DeviceProfile) },
		"events.playback_stopped.watched.json":              func() any { return new(PlaybackStopped) },
		"events.playback_stopped.with_resume.json":          func() any { return new(PlaybackStopped) },
	}
	for name, mk := range cases {
		t.Run(name, func(t *testing.T) { roundTripGolden(t, name, mk()) })
	}
}

// The PlaybackInfo bodies of the goldens are sent by the Go client since wave 2
// (TestGolden_playback_playbackinfo_request, client_test.go).
func TestPlaybackInfoRequestSkipsUnsetFields(t *testing.T) {
	// skip_serializing_if = "Option::is_none": absent, not null.
	js := string(marshal(t, PlaybackInfoRequest{DeviceProfile: &DeviceProfile{}}))
	if strings.Contains(js, "MediaSourceId") || strings.Contains(js, "AudioStreamIndex") {
		t.Fatal(js)
	}
}

func TestPlaybackReportBodySkipsUnsetFields(t *testing.T) {
	js := string(marshal(t, PlaybackReportBody{ItemID: "1", MediaSourceID: "m", PositionTicks: 5}))
	if js != `{"ItemId":"1","MediaSourceId":"m","PositionTicks":5}` {
		t.Fatal(js)
	}
	paused := false
	js = string(marshal(t, PlaybackReportBody{ItemID: "1", MediaSourceID: "m", IsPaused: &paused, PlayMethod: ptr("DirectPlay")}))
	if js != `{"ItemId":"1","MediaSourceId":"m","PositionTicks":0,"IsPaused":false,"PlayMethod":"DirectPlay"}` {
		t.Fatal(js)
	}
}

// SessionStatus is internally tagged (`state`) with camelCase fields.
func TestGolden_models_emby_restore(t *testing.T) {
	server := summaryOfFixture()
	user := SignedInUser{Server: server, UserID: fixtureUserID, UserName: "testeur", IsAdministrator: false}
	saved := SavedServer{
		URL: "http://emby.test", ID: fixtureServerID, Name: "ServeurTest", Version: "4.10.1.0",
		Encrypted: false, UserID: fixtureUserID, UserName: "testeur",
	}
	assertTypedGolden(t, "models.emby_restore.signed_out.json", StatusSignedOut())
	assertTypedGolden(t, "models.emby_restore.authenticated.json", StatusAuthenticated(user))
	assertTypedGolden(t, "models.emby_restore.authentication_required.json", StatusAuthenticationRequired(saved, ErrSessionExpired))
	assertTypedGolden(t, "models.emby_restore.server_unreachable.json", StatusServerUnreachable(saved, ErrTimeout))
	if _, err := json.Marshal(SessionStatus{State: StateAuthenticated}); err == nil {
		t.Fatal("authenticated without a user must not serialize")
	}
}

const (
	fixtureServerID = "d969e3d9465b49ec8f73a5f1eab2e86f"
	fixtureUserID   = "4a1f0e2b9c8d4e7fa6b5c4d3e2f1a0b9"
)

// summaryOfFixture is the ServerSummary of the emby_like() fake server.
func summaryOfFixture() ServerSummary {
	return ServerSummary{URL: "http://emby.test", Name: "ServeurTest", Version: "4.10.1.0", ID: fixtureServerID, Encrypted: false}
}
