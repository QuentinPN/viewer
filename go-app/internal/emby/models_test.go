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

// Typed values no Go code produces yet, shape only: the golden is decoded into the Go
// type (unknown keys refused) and must serialize back identically. The models.*,
// titles.emby_* and playback.emby_play.* goldens are produced through EmbyService
// since wave 4 (golden_session_test.go, playback_test.go).
func TestGolden_models_shapes(t *testing.T) {
	cases := map[string]func() any{
		"playback.device_profile.json":             func() any { return new(DeviceProfile) },
		"events.playback_stopped.watched.json":     func() any { return new(PlaybackStopped) },
		"events.playback_stopped.with_resume.json": func() any { return new(PlaybackStopped) },
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

// SessionStatus is internally tagged (`state`) with camelCase fields; its four
// goldens are produced by Restore (TestGolden_session_cases). A status without its
// fields never serializes.
func TestSessionStatusNeedsItsFields(t *testing.T) {
	if _, err := json.Marshal(SessionStatus{State: StateAuthenticated}); err == nil {
		t.Fatal("authenticated without a user must not serialize")
	}
	if _, err := json.Marshal(SessionStatus{State: StateServerUnreachable}); err == nil {
		t.Fatal("serverUnreachable without a server must not serialize")
	}
}

const (
	fixtureServerID = "d969e3d9465b49ec8f73a5f1eab2e86f"
	fixtureUserID   = "4a1f0e2b9c8d4e7fa6b5c4d3e2f1a0b9"
)
