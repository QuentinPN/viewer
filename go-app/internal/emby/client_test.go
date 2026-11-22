package emby

// Tests of the Emby client against the fake server (fakeserver_test.go). The Rust
// tests of tests.rs go through EmbyService (session.rs, wave 4): here is their client
// half under the same name, the HTTP contract (path, query, headers, body, error
// kind) and the decoded answer. The service-level assertions left for wave 4 are
// listed in MAP.md, test by test.

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

var bg = context.Background()

func wantKind(t *testing.T, err error, kind ErrorKind) {
	t.Helper()
	got, ok := KindOf(err)
	if !ok || got != kind {
		t.Fatalf("erreur %v (%T), attendu le kind %s", err, err, kind)
	}
}

func noTokenInURLs(t *testing.T, server *fakeEmby) {
	t.Helper()
	for _, r := range server.requests() {
		if strings.Contains(r.Path, fakeToken) || strings.Contains(r.Path, fakePassword) {
			t.Fatalf("secret dans l'URL : %s", r.Path)
		}
	}
}

func wantPairs(t *testing.T, target string, expected ...string) {
	t.Helper()
	q := queryPairs(target)
	for _, e := range expected {
		if !hasPair(q, e) {
			t.Errorf("%s absent de %s", e, target)
		}
	}
}

// --------------------------------------------------- sign-in (client half)

func Test_sign_in_stores_token_securely_and_sends_auth_in_header(t *testing.T) {
	server := startFakeEmby(t, fakeEmbyLike(204))
	c := fakeClient(t, server.URL)
	info, err := c.PublicInfo(bg)
	if err != nil {
		t.Fatal(err)
	}
	if info.ServerName != "ServeurTest" || info.ID != fakeServerID {
		t.Fatalf("%+v", info)
	}
	auth, err := c.Authenticate(bg, "testeur", fakePassword)
	if err != nil {
		t.Fatal(err)
	}
	if auth.User.Name != "testeur" || auth.User.Policy == nil || auth.User.Policy.IsAdministrator || auth.AccessToken != fakeToken {
		t.Fatalf("%v", auth)
	}
	reqs := server.requests()
	sent := reqs[1]
	if sent.Path != "/emby/Users/AuthenticateByName" || !strings.Contains(sent.Body, `"Username":"testeur"`) {
		t.Fatalf("%s %s", sent.Path, sent.Body)
	}
	if !sent.HasAuth || !strings.HasPrefix(sent.Auth, `Emby Client="Viewer"`) ||
		!strings.Contains(sent.Auth, `Device="PC test bureau"`) || strings.Contains(sent.Auth, "Token=") ||
		!strings.Contains(sent.Auth, `DeviceId="test-device-id"`) {
		t.Fatal(sent.Auth)
	}
	noTokenInURLs(t, server)

	// The token then travels in the header of every call.
	signed := c.WithToken(NewAccessToken(auth.AccessToken))
	if _, err := signed.User(bg, fakeUserID); err != nil {
		t.Fatal(err)
	}
	if a := server.last(t).Auth; !strings.Contains(a, `Token="`+fakeToken+`"`) {
		t.Fatal(a)
	}
	// The original client is unchanged: WithToken copies.
	if c.accessToken() != nil || signed.accessToken() == nil {
		t.Fatal("WithToken a modifié le client d'origine")
	}
}

func Test_wrong_password_is_reported_and_nothing_is_stored(t *testing.T) {
	server := startFakeEmby(t, fakeEmbyLike(204))
	_, err := fakeClient(t, server.URL).Authenticate(bg, "testeur", "nope")
	wantKind(t, err, KindInvalidCredentials)
}

func Test_unreachable_server_is_reported(t *testing.T) {
	_, err := fakeClient(t, closedPortURL(t)).PublicInfo(bg)
	wantKind(t, err, KindUnreachable)
	if strings.Contains(err.Error(), "127.0.0.1") {
		t.Fatalf("l'URL ne doit pas apparaître dans le message : %v", err)
	}
}

func Test_malformed_or_foreign_server_responses_are_rejected(t *testing.T) {
	server := startFakeEmby(t, func(fakeRequest) (int, string) { return 200, "<html>Emby</html>" })
	_, err := fakeClient(t, server.URL).PublicInfo(bg)
	wantKind(t, err, KindInvalidResponse)

	// AuthenticateByName answered for a different server id: the client decodes it,
	// the service refuses it (wave 4).
	server = startFakeEmby(t, func(r fakeRequest) (int, string) {
		if r.Path == "/emby/System/Info/Public" {
			return 200, fakeFixture("public_info")
		}
		return 200, strings.ReplaceAll(fakeFixture("auth_result"), fakeServerID, "another")
	})
	auth, err := fakeClient(t, server.URL).Authenticate(bg, "testeur", fakePassword)
	if err != nil || auth.ServerID != "another" {
		t.Fatalf("%v %v", auth, err)
	}

	server = startFakeEmby(t, func(fakeRequest) (int, string) { return 503, "" })
	_, err = fakeClient(t, server.URL).PublicInfo(bg)
	wantKind(t, err, KindServer)
	if !errors.Is(err, NewServer(503)) || err.(EmbyError).Status != 503 {
		t.Fatal(err)
	}
}

// ------------------------------------------------------------- libraries

func Test_libraries_keep_films_and_series_with_local_image_urls(t *testing.T) {
	server, c, _ := signedInClient(t)
	views, err := c.Views(bg, fakeUserID)
	if err != nil {
		t.Fatal(err)
	}
	if len(views.Items) != 4 || views.TotalRecordCount != 4 {
		t.Fatalf("%+v", views)
	}
	anime := views.Items[0]
	if anime.ID != "10" || *anime.CollectionType != "tvshows" || anime.ImageTags["Primary"] != "56f6b8b2_6384" {
		t.Fatalf("%+v", anime)
	}
	if films := views.Items[1]; films.ID != "5" || *films.CollectionType != "movies" || len(films.ImageTags) != 0 {
		t.Fatalf("%+v", films)
	}
	if views.Items[3].ImageTags != nil {
		t.Fatal("pas d'ImageTags : map nil")
	}
	if a := server.last(t).Auth; !strings.Contains(a, `Token="`+fakeToken+`"`) {
		t.Fatal(a)
	}
	noTokenInURLs(t, server)
}

func Test_items_send_the_chosen_sort_and_filters(t *testing.T) {
	server, c, _ := signedInClient(t)
	queryOf := func(o ItemsOptions) []string {
		t.Helper()
		_, err := c.Items(bg, fakeUserID, ItemsQuery{ParentID: "5", ItemTypes: "Movie", StartIndex: 0, Limit: 60, Options: o, Shape: ShapePage})
		if err != nil {
			t.Fatal(err)
		}
		return queryPairs(server.last(t).Path)
	}
	for _, p := range queryOf(DefaultItemsOptions()) {
		if strings.HasPrefix(p, "Filters=") || strings.HasPrefix(p, "Genres=") {
			t.Fatal(p)
		}
	}
	q := queryOf(ItemsOptions{
		Sort: SortDateAdded, Descending: true, Watch: WatchUnplayed, Favorites: true,
		Genre: ptr("Science-Fiction & Fantastique"), Decade: ptr[int32](1990),
	})
	for _, e := range []string{
		"Years=1990%2C1991%2C1992%2C1993%2C1994%2C1995%2C1996%2C1997%2C1998%2C1999",
		"SortBy=DateCreated%2CSortName",
		"SortOrder=Descending",
		"Filters=IsUnplayed%2CIsFavorite",
		"Genres=Science-Fiction+%26+Fantastique",
		"ParentId=5",
		"StartIndex=0",
	} {
		if !hasPair(q, e) {
			t.Errorf("%s absent de %v", e, q)
		}
	}
	for sort, sortBy := range map[SortKey]string{
		SortName:   "SortBy=SortName",
		SortYear:   "SortBy=ProductionYear%2CSortName",
		SortRating: "SortBy=CommunityRating%2CSortName",
	} {
		if q := queryOf(ItemsOptions{Sort: sort}); !hasPair(q, sortBy) {
			t.Errorf("%s absent de %v", sortBy, q)
		}
	}
	for watch, filters := range map[WatchFilter]string{
		WatchPlayed:     "Filters=IsPlayed",
		WatchInProgress: "Filters=IsResumable",
	} {
		if q := queryOf(ItemsOptions{Watch: watch}); !hasPair(q, filters) {
			t.Errorf("%s absent de %v", filters, q)
		}
	}
}

// The Rust tests items_refuse_a_genre/decade_the_server_cannot_filter_on check the
// service ("nothing sent", wave 4). These only check the rules it applies.
func TestUnusableGenresAreRefusedByTheRule(t *testing.T) {
	for _, genre := range []string{"", "  ", "Drame|Romance", "a\u0007b", strings.Repeat("x", 101)} {
		if IsUsableGenre(genre) {
			t.Errorf("%q accepté", genre)
		}
	}
}

func TestUnusableDecadesSendNoYears(t *testing.T) {
	for _, decade := range []int32{1995, 1870, 2200, -10} {
		if IsUsableDecade(decade) {
			t.Errorf("%d accepté", decade)
		}
		if _, ok := (ItemsOptions{Decade: &decade}).Years(); ok {
			t.Errorf("%d : Years envoyé", decade)
		}
	}
}

func Test_sort_names_follow_the_grid_query_without_paging_or_images(t *testing.T) {
	server, c, _ := signedInClient(t)
	options := ItemsOptions{Sort: SortName, Descending: true, Watch: WatchUnplayed, Genre: ptr("Drame"), Decade: ptr[int32](2010)}
	page, err := c.Items(bg, fakeUserID, ItemsQuery{ParentID: "5", ItemTypes: "Movie", Options: options, Shape: ShapeSortNames})
	if err != nil {
		t.Fatal(err)
	}
	// Nothing dropped by the client, the unsafe id included: positions stay the server's.
	if got := ids(page.Items); fmt.Sprint(got) != "[201 ../202 203]" {
		t.Fatal(got)
	}
	if *page.Items[0].SortName != "Grand Bleu" || page.Items[1].SortName != nil {
		t.Fatalf("%+v", page.Items)
	}
	req := server.last(t)
	path, query := splitQuery(req.Path)
	if path != "/emby/Users/"+fakeUserID+"/Items" {
		t.Fatal(path)
	}
	wantPairs(t, req.Path, "ParentId=5", "Recursive=true", "IncludeItemTypes=Movie", "SortBy=SortName",
		"SortOrder=Descending", "Fields=SortName", "EnableImages=false", "EnableUserData=false",
		"Filters=IsUnplayed", "Genres=Drame")
	if !strings.Contains(query, "Years=2010%2C") {
		t.Fatal(query)
	}
	for _, p := range queryPairs(req.Path) {
		if strings.HasPrefix(p, "StartIndex=") || strings.HasPrefix(p, "Limit=") {
			t.Fatal(p)
		}
	}
	noTokenInURLs(t, server)
}

func Test_random_item_is_drawn_by_the_server_with_the_grid_filters(t *testing.T) {
	server, c, _ := signedInClient(t)
	options := ItemsOptions{Watch: WatchPlayed, Favorites: true, Genre: ptr("Drame")}
	page, err := c.Items(bg, fakeUserID, ItemsQuery{ParentID: "5", ItemTypes: "Movie", Options: options, Shape: ShapeRandom})
	if err != nil {
		t.Fatal(err)
	}
	if len(page.Items) != 1 || page.Items[0].ID != "101" || *page.Items[0].ProductionYear != 2020 {
		t.Fatalf("%+v", page)
	}
	req := server.last(t)
	wantPairs(t, req.Path, "ParentId=5", "SortBy=Random", "Limit=1", "Filters=IsPlayed%2CIsFavorite", "Genres=Drame")
	for _, p := range queryPairs(req.Path) {
		if strings.HasPrefix(p, "SortOrder=") || strings.HasPrefix(p, "StartIndex=") {
			t.Fatal(p)
		}
	}
	noTokenInURLs(t, server)
	// Nothing matches: no title, no error.
	page, err = c.Items(bg, fakeUserID, ItemsQuery{ParentID: "5", ItemTypes: "Movie", Shape: ShapeRandom})
	if err != nil || len(page.Items) != 0 {
		t.Fatalf("%+v %v", page, err)
	}
}

func Test_production_years_are_read_once_without_filters(t *testing.T) {
	server, c, _ := signedInClient(t)
	page, err := c.ProductionYears(bg, fakeUserID, "5", "Movie")
	if err != nil || len(page.Items) != 5 {
		t.Fatalf("%+v %v", page, err)
	}
	req := server.last(t)
	wantPairs(t, req.Path, "ParentId=5", "Recursive=true", "IncludeItemTypes=Movie", "Fields=ProductionYear", "EnableImages=false")
	for _, p := range queryPairs(req.Path) {
		if strings.HasPrefix(p, "Filters=") || strings.HasPrefix(p, "Limit=") {
			t.Fatal(p)
		}
	}
}

// ------------------------------------------------------------ titles, chapters

func Test_person_titles_read_with_person_ids_newest_first(t *testing.T) {
	server, c, _ := signedInClient(t)
	source := TitlesSource{Kind: TitlesPerson, ID: "30209"}
	params, err := TitlesParams(source)
	if err != nil {
		t.Fatal(err)
	}
	page, err := c.Titles(bg, fakeUserID, params)
	if err != nil {
		t.Fatal(err)
	}
	if got := ids(OrderTitles(source, page.Items)); fmt.Sprint(got) != "[30400 30207 30401]" {
		t.Fatal(got)
	}
	req := server.last(t)
	if !strings.HasPrefix(req.Path, "/emby/Users/"+fakeUserID+"/Items?") || !strings.Contains(req.Path, "PersonIds=30209") {
		t.Fatal(req.Path)
	}
	noTokenInURLs(t, server)
	if _, err := TitlesParams(TitlesSource{Kind: TitlesPerson, ID: "../x"}); err == nil {
		t.Fatal("id refusé attendu")
	}
}

func Test_similar_collection_and_trailer_of_a_movie(t *testing.T) {
	server, c, _ := signedInClient(t)
	item, err := c.Item(bg, fakeUserID, "30207")
	if err != nil {
		t.Fatal(err)
	}
	if refs := CollectionRefs(item.Collections); len(refs) != 1 || refs[0].ID != "120034" {
		t.Fatalf("%+v", refs)
	}
	if item.LocalTrailerCount == nil || *item.LocalTrailerCount == 0 {
		t.Fatal("bande-annonce attendue")
	}

	source := TitlesSource{Kind: TitlesSimilar, ID: "30207"}
	params, ok := SimilarParams("30207", fakeUserID)
	if !ok {
		t.Fatal("SimilarParams")
	}
	page, err := c.Similar(bg, "30207", params)
	if err != nil {
		t.Fatal(err)
	}
	if got := ids(OrderTitles(source, page.Items)); fmt.Sprint(got) != "[30400 30401]" {
		t.Fatal(got, "the title itself left out")
	}
	req := server.last(t)
	if !strings.HasPrefix(req.Path, "/emby/Items/30207/Similar?") || !strings.Contains(req.Path, "UserId="+fakeUserID) {
		t.Fatal(req.Path)
	}

	boxset := TitlesSource{Kind: TitlesCollection, ID: "120034"}
	params, err = TitlesParams(boxset)
	if err != nil {
		t.Fatal(err)
	}
	page, err = c.Titles(bg, fakeUserID, params)
	if err != nil || len(OrderTitles(boxset, page.Items)) != 3 {
		t.Fatalf("%+v %v", page, err)
	}

	trailers, err := c.LocalTrailers(bg, fakeUserID, "30207")
	if err != nil || len(trailers) != 1 || trailers[0].ID != "30500" {
		t.Fatalf("%+v %v", trailers, err)
	}
	if p := server.last(t).Path; !strings.Contains(p, "/30207/LocalTrailers?EnableImages=false") {
		t.Fatal(p)
	}
	_, err = c.LocalTrailers(bg, fakeUserID, "../x")
	wantKind(t, err, KindInvalidResponse)
	noTokenInURLs(t, server)
}

func Test_chapters_come_from_the_item_in_seconds(t *testing.T) {
	server, c, revoked := signedInClient(t)
	item, err := c.Item(bg, fakeUserID, "30207")
	if err != nil {
		t.Fatal(err)
	}
	if p := server.last(t).Path; p != "/emby/Users/"+fakeUserID+"/Items/30207" {
		t.Fatal(p)
	}
	found := Chapters(item.Chapters, item.RunTimeTicks)
	var got []string
	for _, ch := range found {
		got = append(got, fmt.Sprintf("%v %s", ch.Start, ch.Name))
	}
	if fmt.Sprint(got) != "[0 Chapitre 1 487.57 Chapitre 2 726.558 Chapitre 3]" {
		t.Fatal(got)
	}
	// serde writes `0.0` where encoding/json writes `0`: same number (MAP.md).
	js := string(marshal(t, found))
	if !strings.HasPrefix(js, `[{"start":0,"name":"Chapitre 1"}`) {
		t.Fatal(js)
	}
	// No chapters: nothing to show, no error.
	series, err := c.Item(bg, fakeUserID, "80473")
	if err != nil || len(Chapters(series.Chapters, series.RunTimeTicks)) != 0 {
		t.Fatalf("%v", err)
	}
	_, err = c.Item(bg, fakeUserID, "999")
	wantKind(t, err, KindNotFound)
	revoked.Store(true)
	_, err = c.Item(bg, fakeUserID, "30207")
	wantKind(t, err, KindSessionExpired)
}

// ---------------------------------------------------------- playback quality

// lastBitrate is `last_bitrate`: MaxStreamingBitrate of the last PlaybackInfo body and
// of its DeviceProfile.
func lastBitrate(t *testing.T, server *fakeEmby) (uint64, uint64) {
	t.Helper()
	reqs := server.requests()
	for i := len(reqs) - 1; i >= 0; i-- {
		if !strings.Contains(reqs[i].Path, "/PlaybackInfo") {
			continue
		}
		var body struct {
			MaxStreamingBitrate uint64
			DeviceProfile       struct{ MaxStreamingBitrate uint64 }
		}
		if err := json.Unmarshal([]byte(reqs[i].Body), &body); err != nil {
			t.Fatal(err)
		}
		return body.MaxStreamingBitrate, body.DeviceProfile.MaxStreamingBitrate
	}
	t.Fatal("aucun PlaybackInfo")
	return 0, 0
}

// playWithLimit is the client half of prepare_playback: the profile for one bitrate.
func playWithLimit(t *testing.T, c *Client, bitrate uint64) {
	t.Helper()
	profile := DeviceProfileFor(bitrate)
	if _, err := c.PlaybackInfo(bg, fakeUserID, "30207", &profile, false, nil, nil); err != nil {
		t.Fatal(err)
	}
}

func Test_playback_quality_caps_the_bitrate_of_a_local_address(t *testing.T) {
	server, c, _ := signedInClient(t)
	if !c.Server().IsLocal() {
		t.Fatal("le faux serveur est sur 127.0.0.1")
	}
	// The fake server is on 127.0.0.1: a remote cap does not apply.
	caps := QualityCaps{Remote: u64(1_500_000)}
	if c := caps.ForAddress(true); c != nil {
		t.Fatal(*c)
	}
	playWithLimit(t, c, MaxStreamingBitrate)
	if a, b := lastBitrate(t, server); a != 400_000_000 || b != 400_000_000 {
		t.Fatal(a, b)
	}
	caps = QualityCaps{Local: u64(4_000_000), Remote: u64(1_500_000)}
	playWithLimit(t, c, *caps.ForAddress(true))
	if a, b := lastBitrate(t, server); a != 4_000_000 || b != 4_000_000 {
		t.Fatal(a, b)
	}
}

// The PlaybackInfo bodies of the goldens, now sent by the Go client and read back by
// the fake server (alphabetical keys: compared without order). The profile is wave
// 2B's DeviceProfileFor; its own golden is playback.device_profile.json.
func TestGolden_playback_playbackinfo_request(t *testing.T) {
	cases := []struct {
		golden, item string
		source       *string
		audio        *int32
	}{
		{"playback.playbackinfo_request.direct_play.json", "30207", nil, nil},
		{"playback.playbackinfo_request.transcode_english.json", "40001", ptr("mediasource_40001"), ptr[int32](2)},
	}
	for _, tc := range cases {
		t.Run(tc.golden, func(t *testing.T) {
			server, c, _ := signedInClient(t)
			profile := DeviceProfileFor(MaxStreamingBitrate)
			res, err := c.PlaybackInfo(bg, fakeUserID, tc.item, &profile, false, tc.source, tc.audio)
			if err != nil || len(res.MediaSources) != 1 {
				t.Fatalf("%+v %v", res, err)
			}
			req := server.last(t)
			if req.Method != "POST" || req.Path != "/emby/Items/"+tc.item+"/PlaybackInfo?UserId="+fakeUserID {
				t.Fatal(req.Method, req.Path)
			}
			assertSameJSON(t, tc.golden, readGolden(t, tc.golden), []byte(req.Body))
		})
	}
}

// ------------------------------------------------------------ client details

func Test_date_played_is_utc_yyyymmddhhmmss(t *testing.T) {
	if got := EmbyDate(0); got != "19700101000000" {
		t.Fatal(got)
	}
	// 2024-02-29 23:59:58 UTC: a leap day.
	if got := EmbyDate(1_709_251_198); got != "20240229235958" {
		t.Fatal(got)
	}
	if got := DatePlayedNow(); len(got) != 14 {
		t.Fatal(got)
	}
}

func Test_artwork_is_fetched_with_auth_and_checked(t *testing.T) {
	server, c, _ := signedInClient(t)
	q := "tag=7b213eb5_6379&maxWidth=360"
	req, ok := ParseImageRequest("/"+fakeServerID+"/101/Primary", &q)
	if !ok {
		t.Fatal("ParseImageRequest")
	}
	contentType, data, err := c.Image(bg, req)
	if err != nil || contentType != "image/jpeg" || string(data) != "fake-jpeg-bytes" {
		t.Fatalf("%s %q %v", contentType, data, err)
	}
	sent := server.last(t)
	if !strings.HasPrefix(sent.Path, "/emby/Items/101/Images/Primary?") ||
		!strings.Contains(sent.Path, "maxWidth=360") || !strings.Contains(sent.Path, "tag=7b213eb5_6379") {
		t.Fatal(sent.Path)
	}
	if !strings.Contains(sent.Auth, `Token="`+fakeToken+`"`) {
		t.Fatal(sent.Auth)
	}
	q2 := "tag=x&maxWidth=360"
	notImage, _ := ParseImageRequest("/"+fakeServerID+"/102/Primary", &q2)
	_, _, err = c.Image(bg, notImage)
	wantKind(t, err, KindInvalidResponse)
}

func TestImageRefusesAnOversizedAnswer(t *testing.T) {
	server := startFakeEmby(t, func(fakeRequest) (int, string) {
		return 200, "JPEG:" + strings.Repeat("x", MaxImageBytes+1)
	})
	q := "tag=t&maxWidth=360"
	req, _ := ParseImageRequest("/"+fakeServerID+"/101/Primary", &q)
	_, _, err := fakeClient(t, server.URL).WithToken(NewAccessToken(fakeToken)).Image(bg, req)
	wantKind(t, err, KindInvalidResponse)
	if !strings.Contains(err.Error(), "image trop lourde") {
		t.Fatal(err)
	}
}

func TestAuthorizationHeaderStripsQuotesAndCommas(t *testing.T) {
	c := fakeClient(t, "https://emby.test")
	want := `Emby Client="Viewer", Device="PC test bureau", DeviceId="test-device-id", Version="0.1.0"`
	if got := c.AuthorizationHeader(); got != want {
		t.Fatal(got)
	}
	c = c.WithToken(NewAccessToken(`a"b,c`))
	if got := c.AuthorizationHeader(); got != want+`, Token="abc"` {
		t.Fatal(got)
	}
}

func TestStatusMapping(t *testing.T) {
	for status, kind := range map[int]ErrorKind{
		401: KindSessionExpired, 403: KindForbidden, 302: KindInvalidResponse,
		404: KindNotFound, 500: KindServer, 418: KindServer,
	} {
		server := startFakeEmby(t, func(fakeRequest) (int, string) { return status, "" })
		c := fakeClient(t, server.URL).WithToken(NewAccessToken(fakeToken))
		_, err := c.Views(bg, fakeUserID)
		wantKind(t, err, kind)
		if status == 302 && !strings.Contains(err.Error(), "redirection HTTP 302 non suivie") {
			t.Fatal(err)
		}
	}
	// 401 on sign-in is a wrong password, not an expired session.
	server := startFakeEmby(t, func(fakeRequest) (int, string) { return 401, "" })
	_, err := fakeClient(t, server.URL).PublicInfo(bg)
	wantKind(t, err, KindInvalidCredentials)
}

func TestRedirectIsNotFollowed(t *testing.T) {
	other := startFakeEmby(t, func(fakeRequest) (int, string) { return 200, `{"Items":[],"TotalRecordCount":0}` })
	// A real redirect, with Location: the other server must never see the auth header.
	redirect := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, other.URL+r.URL.Path, http.StatusFound)
	}))
	t.Cleanup(redirect.Close)
	c := fakeClient(t, redirect.URL).WithToken(NewAccessToken(fakeToken))
	_, err := c.Views(bg, fakeUserID)
	wantKind(t, err, KindInvalidResponse)
	if len(other.requests()) != 0 {
		t.Fatal("redirection suivie")
	}
}

func TestTokenCallsNeedAToken(t *testing.T) {
	server := startFakeEmby(t, fakeBrowseLike(new(atomic.Bool)))
	c := fakeClient(t, server.URL)
	_, err := c.Views(bg, fakeUserID)
	wantKind(t, err, KindNotSignedIn)
	wantKind(t, c.Logout(bg), KindNotSignedIn)
	_, err = c.StreamAuthHeaders()
	wantKind(t, err, KindNotSignedIn)
	_, err = c.LibrarySocketURL()
	wantKind(t, err, KindNotSignedIn)
	if len(server.requests()) != 0 {
		t.Fatal("requête envoyée sans jeton")
	}
	// A bad id is refused before any request.
	_, err = c.WithToken(NewAccessToken(fakeToken)).Item(bg, fakeUserID, "../x")
	wantKind(t, err, KindInvalidResponse)
	if len(server.requests()) != 0 {
		t.Fatal("requête envoyée avec un id invalide")
	}
}

func TestLibrarySocketURLHoldsTheTokenInTheQuery(t *testing.T) {
	for in, want := range map[string]string{
		"http://192.168.1.14:8096": "ws://192.168.1.14:8096/embywebsocket?api_key=" + fakeToken + "&deviceId=test-device-id",
		"https://emby.example.org": "wss://emby.example.org/embywebsocket?api_key=" + fakeToken + "&deviceId=test-device-id",
	} {
		got, err := fakeClient(t, in).WithToken(NewAccessToken(fakeToken)).LibrarySocketURL()
		if err != nil || got != want {
			t.Fatalf("%s: %s %v", in, got, err)
		}
	}
}

func TestStreamAuthHeadersCarryTheToken(t *testing.T) {
	h, err := fakeClient(t, "https://emby.test").WithToken(NewAccessToken(fakeToken)).StreamAuthHeaders()
	if err != nil || len(h) != 1 || h[0] != [2]string{"X-Emby-Token", fakeToken} {
		t.Fatal(h, err)
	}
}

func TestSameToken(t *testing.T) {
	c := fakeClient(t, "https://emby.test")
	a := c.WithToken(NewAccessToken("a"))
	if !a.SameToken(c.WithToken(NewAccessToken("a"))) || a.SameToken(c.WithToken(NewAccessToken("b"))) || c.SameToken(c) || a.SameToken(c) {
		t.Fatal("SameToken")
	}
}

func TestAccessTokenNeverPrints(t *testing.T) {
	tok := NewAccessToken(fakeToken)
	var logged strings.Builder
	slog.New(slog.NewTextHandler(&logged, nil)).Info("x", "token", tok)
	for _, s := range []string{fmt.Sprint(tok), fmt.Sprintf("%v %+v %#v %s", tok, tok, tok, tok), logged.String()} {
		if strings.Contains(s, fakeToken) {
			t.Fatal(s)
		}
	}
	if _, err := json.Marshal(tok); err == nil {
		t.Fatal("AccessToken sérialisé")
	}
}

// User-Agent, Accept, no Accept-Encoding (reqwest without gzip), Content-Type only
// with a body.
func TestRequestsSendTheRustHeaders(t *testing.T) {
	server := startFakeEmby(t, fakeEmbyLike(204))
	c := fakeClient(t, server.URL)
	if _, err := c.PublicInfo(bg); err != nil {
		t.Fatal(err)
	}
	if _, err := c.Authenticate(bg, "testeur", fakePassword); err != nil {
		t.Fatal(err)
	}
	reqs := server.requests()
	for i, r := range reqs {
		h := r.Header
		if h.Get("User-Agent") != "Viewer/0.1.0" || h.Get("Accept") != "application/json" {
			t.Fatal(h)
		}
		if _, ok := h["Accept-Encoding"]; ok {
			t.Fatal("Accept-Encoding envoyé", h)
		}
		ct, hasCT := h["Content-Type"]
		if (i == 1) != hasCT || (hasCT && ct[0] != "application/json") {
			t.Fatalf("requête %d : Content-Type %v", i, ct)
		}
	}
}

// Every endpoint against browse_like: the full request target (query order of
// client.rs), and an answer the strict decoder accepts (serde accepts these bodies).
func TestEveryEndpointSendsTheRustQuery(t *testing.T) {
	server, c, _ := signedInClient(t)
	u := fakeUserID
	cases := []struct {
		name   string
		method string
		target string
		call   func() error
	}{
		{"Views", "GET", "/emby/Users/" + u + "/Views", func() error { _, err := c.Views(bg, u); return err }},
		{"Genres", "GET", "/emby/Genres?UserId=" + u + "&ParentId=5&IncludeItemTypes=Movie&Recursive=true&SortBy=SortName&SortOrder=Ascending&EnableImages=false&EnableUserData=false",
			func() error { _, err := c.Genres(bg, u, "5", "Movie"); return err }},
		{"ResumeItems", "GET", "/emby/Users/" + u + "/Items/Resume?Limit=12&MediaTypes=Video&IncludeItemTypes=Movie%2CEpisode&Fields=PrimaryImageAspectRatio&EnableUserData=true&ImageTypeLimit=1&EnableImageTypes=Primary%2CBackdrop%2CThumb",
			func() error { _, err := c.ResumeItems(bg, u, 12); return err }},
		{"HeroResume", "GET", "/emby/Users/" + u + "/Items/Resume?Limit=5&MediaTypes=Video&IncludeItemTypes=Movie%2CEpisode&Fields=Overview%2CProductionYear%2CPrimaryImageAspectRatio&EnableUserData=true&ImageTypeLimit=1&EnableImageTypes=Primary%2CBackdrop%2CLogo",
			func() error { _, err := c.HeroResume(bg, u, 5); return err }},
		{"HeroLatest", "GET", "/emby/Users/" + u + "/Items?Recursive=true&IncludeItemTypes=Movie%2CSeries&IsPlayed=false&ImageTypes=Backdrop&SortBy=DateCreated&SortOrder=Descending&Limit=5&Fields=Overview%2CProductionYear%2CPrimaryImageAspectRatio&EnableUserData=true&ImageTypeLimit=1&EnableImageTypes=Primary%2CBackdrop%2CLogo",
			func() error { _, err := c.HeroLatest(bg, u, 5); return err }},
		{"FirstUnplayedEpisode", "GET", "/emby/Shows/80473/Episodes?UserId=" + u + "&IsPlayed=false&Limit=1&EnableUserData=true&EnableImageTypes=Primary&ImageTypeLimit=1",
			func() error { _, err := c.FirstUnplayedEpisode(bg, u, "80473"); return err }},
		{"NextUp", "GET", "/emby/Shows/NextUp?UserId=" + u + "&Limit=12&Fields=PrimaryImageAspectRatio&EnableUserData=true&ImageTypeLimit=1&EnableImageTypes=Primary%2CBackdrop%2CThumb",
			func() error { _, err := c.NextUp(bg, u, 12); return err }},
		{"SearchMovie", "GET", "/emby/Users/" + u + "/Items?SearchTerm=24+h%C3%A9&Recursive=true&IncludeItemTypes=Movie&Limit=24&Fields=PrimaryImageAspectRatio%2CProductionYear&EnableImageTypes=Primary&ImageTypeLimit=1&EnableUserData=true",
			func() error { _, err := c.Search(bg, u, "24 hé", "Movie", 24); return err }},
		{"SearchEpisode", "GET", "/emby/Users/" + u + "/Items?SearchTerm=24&Recursive=true&IncludeItemTypes=Episode&Limit=24&Fields=PrimaryImageAspectRatio&EnableImageTypes=Primary%2CBackdrop%2CThumb&ImageTypeLimit=1&EnableUserData=true",
			func() error { _, err := c.Search(bg, u, "24", "Episode", 24); return err }},
		{"HomeSections", "GET", "/emby/Users/" + u + "/HomeSections", func() error { _, err := c.HomeSections(bg, u); return err }},
		{"SectionItems", "GET", "/emby/Users/" + u + "/Sections/resume/Items?Limit=12&Fields=PrimaryImageAspectRatio&EnableUserData=true&ImageTypeLimit=1&EnableImageTypes=Primary%2CBackdrop%2CThumb",
			func() error { _, err := c.SectionItems(bg, u, "resume", 12); return err }},
		{"Latest", "GET", "/emby/Users/" + u + "/Items/Latest?ParentId=5&Limit=16&Fields=PrimaryImageAspectRatio%2CProductionYear&EnableUserData=true&ImageTypeLimit=1&EnableImageTypes=Primary",
			func() error { _, err := c.Latest(bg, u, "5", 16); return err }},
		{"ItemSeries", "GET", "/emby/Users/" + u + "/Items/80473", func() error { _, err := c.Item(bg, u, "80473"); return err }},
		{"ItemEpisode", "GET", "/emby/Users/" + u + "/Items/80475", func() error { _, err := c.Item(bg, u, "80475"); return err }},
		{"ItemBareEpisode", "GET", "/emby/Users/" + u + "/Items/80476", func() error { _, err := c.Item(bg, u, "80476"); return err }},
		{"ItemSeason", "GET", "/emby/Users/" + u + "/Items/80474", func() error { _, err := c.Item(bg, u, "80474"); return err }},
		{"ItemTwoVersions", "GET", "/emby/Users/" + u + "/Items/30500", func() error { _, err := c.Item(bg, u, "30500"); return err }},
		{"Seasons", "GET", "/emby/Shows/80473/Seasons?UserId=" + u + "&EnableUserData=true&EnableImageTypes=Primary&ImageTypeLimit=1",
			func() error { _, err := c.Seasons(bg, u, "80473"); return err }},
		{"Episodes", "GET", "/emby/Shows/80473/Episodes?UserId=" + u + "&SeasonId=80474&StartIndex=0&Limit=50&Fields=Overview&EnableUserData=true&EnableImageTypes=Primary&ImageTypeLimit=1",
			func() error {
				_, err := c.Episodes(bg, u, EpisodesQuery{SeriesID: "80473", SeasonID: "80474", Limit: 50})
				return err
			}},
		{"ForeignEpisodes", "GET", "/emby/Shows/80473/Episodes?UserId=" + u + "&SeasonId=67639&StartIndex=0&Limit=50&Fields=Overview&EnableUserData=true&EnableImageTypes=Primary&ImageTypeLimit=1",
			func() error {
				_, err := c.Episodes(bg, u, EpisodesQuery{SeriesID: "80473", SeasonID: "67639", Limit: 50})
				return err
			}},
		{"AdjacentEpisodes", "GET", "/emby/Shows/80473/Episodes?UserId=" + u + "&AdjacentTo=80475&EnableImages=false&EnableUserData=false",
			func() error { _, err := c.AdjacentEpisodes(bg, u, "80473", "80475"); return err }},
		{"Favorites", "GET", "/emby/Users/" + u + "/Items?Filters=IsFavorite&Recursive=true&IncludeItemTypes=Movie%2CSeries&SortBy=SortName&SortOrder=Ascending&Limit=100&Fields=PrimaryImageAspectRatio%2CProductionYear&EnableImageTypes=Primary&ImageTypeLimit=1&EnableUserData=true",
			func() error { _, err := c.Favorites(bg, u, 100); return err }},
		{"Playlists", "GET", "/emby/Users/" + u + "/Items?IncludeItemTypes=Playlist&Recursive=true&SortBy=SortName&SortOrder=Ascending&Limit=100&EnableImageTypes=Primary&ImageTypeLimit=1",
			func() error { _, err := c.Playlists(bg, u, 100); return err }},
		{"HomePlaylists", "GET", "/emby/Users/" + u + "/Items?IncludeItemTypes=Playlist&Recursive=true&SortBy=SortName&SortOrder=Ascending&Limit=20&Fields=ChildCount%2CPrimaryImageAspectRatio&EnableUserData=false&ImageTypeLimit=1&EnableImageTypes=Primary",
			func() error { _, err := c.HomePlaylists(bg, u, 20); return err }},
		{"PlaylistItems", "GET", "/emby/Playlists/60001/Items?UserId=" + u + "&Limit=200&Fields=PrimaryImageAspectRatio&EnableUserData=true&ImageTypeLimit=1&EnableImageTypes=Primary%2CBackdrop%2CThumb",
			func() error { _, err := c.PlaylistItems(bg, u, "60001", 200); return err }},
		{"PlaybackInfoTranscode", "POST", "/emby/Items/40000/PlaybackInfo?UserId=" + u,
			func() error {
				p := DeviceProfileFor(MaxStreamingBitrate)
				_, err := c.PlaybackInfo(bg, u, "40000", &p, true, nil, nil)
				return err
			}},
		{"PlaybackInfoTwoVersions", "POST", "/emby/Items/30500/PlaybackInfo?UserId=" + u,
			func() error {
				p := DeviceProfileFor(MaxStreamingBitrate)
				_, err := c.PlaybackInfo(bg, u, "30500", &p, false, ptr("30501"), nil)
				return err
			}},
		{"ReportProgress", "POST", "/emby/Sessions/Playing/Progress",
			func() error {
				return c.ReportPlayback(bg, "/Sessions/Playing/Progress", PlaybackReportBody{ItemID: "30207", MediaSourceID: "m"})
			}},
		{"Logout", "POST", "/emby/Sessions/Logout", func() error { return c.Logout(bg) }},
	}
	for _, tc := range cases {
		if err := tc.call(); err != nil {
			t.Errorf("%s: %v", tc.name, err)
			continue
		}
		if r := server.last(t); r.Method != tc.method || r.Path != tc.target {
			t.Errorf("%s: %s %s\nattendu %s %s", tc.name, r.Method, r.Path, tc.method, tc.target)
		}
	}
	// No route in browse_like for /Sessions (404): the query is what is checked.
	_, err := c.Sessions(bg, u)
	wantKind(t, err, KindNotFound)
	if p := server.last(t).Path; p != "/emby/Sessions?ControllableByUserId="+u {
		t.Fatal(p)
	}
	noTokenInURLs(t, server)
}

func TestJSONBodiesAreSerdeLike(t *testing.T) {
	server, c, _ := signedInClient(t)
	// browse_like has no route for these two (404): the body is what matters here.
	_ = c.PostCapabilities(bg)
	if b := server.last(t).Body; b != `{"PlayableMediaTypes":["Video"],"SupportedCommands":["SendString"],"SupportsMediaControl":true}` {
		t.Fatal(b)
	}
	_ = c.SendString(bg, "s1", "<a & b>")
	if b := server.last(t).Body; b != `{"Arguments":{"String":"<a & b>"},"Name":"SendString"}` {
		t.Fatal(b)
	}
	if err := c.ReportPlayback(bg, "/Sessions/Playing", PlaybackReportBody{ItemID: "30207", MediaSourceID: "m"}); err != nil {
		t.Fatal(err)
	}
	if b := server.last(t).Body; b != `{"ItemId":"30207","MediaSourceId":"m","PositionTicks":0}` {
		t.Fatal(b)
	}
}

func TestQueryIsFormEncodedInOrder(t *testing.T) {
	got := encodeQuery([]Param{{"Name", "Soirée ciné*~"}, {"Ids", "1"}, {"a b", "x/y?&="}})
	if got != "Name=Soir%C3%A9e+cin%C3%A9*%7E&Ids=1&a+b=x%2Fy%3F%26%3D" {
		t.Fatal(got)
	}
}

func TestPlaylistAndPlayedCallsHitTheRustEndpoints(t *testing.T) {
	server, c, _ := signedInClient(t)
	added, err := c.AddToPlaylist(bg, fakeUserID, "60001", "30207")
	if err != nil || added.ItemAddedCount != 1 {
		t.Fatal(added, err)
	}
	if p := server.last(t).Path; p != "/emby/Playlists/60001/Items?Ids=30207&UserId="+fakeUserID {
		t.Fatal(p)
	}
	created, err := c.CreatePlaylist(bg, fakeUserID, "Soirée ciné", "30207")
	if err != nil || created.ID != "60010" || created.Name != "Soirée ciné" {
		t.Fatal(created, err)
	}
	if p := server.last(t).Path; p != "/emby/Playlists?Name=Soir%C3%A9e+cin%C3%A9&Ids=30207&MediaType=Video&UserId="+fakeUserID {
		t.Fatal(p)
	}
	date := EmbyDate(1_709_251_198)
	data, err := c.SetPlayed(bg, fakeUserID, "30207", &date)
	if err != nil || !data.Played {
		t.Fatal(data, err)
	}
	if r := server.last(t); r.Method != "POST" || r.Path != "/emby/Users/"+fakeUserID+"/PlayedItems/30207?DatePlayed=20240229235958" {
		t.Fatal(r.Method, r.Path)
	}
	data, err = c.SetPlayed(bg, fakeUserID, "30207", nil)
	if err != nil || data.Played || server.last(t).Method != "DELETE" {
		t.Fatal(data, err)
	}
	data, err = c.HideFromResume(bg, fakeUserID, "30207", true)
	if err != nil || data.PlaybackPositionTicks != 18310167000 {
		t.Fatal(data, err)
	}
	data, err = c.SetFavorite(bg, fakeUserID, "30207", true)
	if err != nil || !data.IsFavorite || server.last(t).Method != "POST" {
		t.Fatal(data, err)
	}
	if err := c.StopActiveEncodings(bg, "ps1"); err == nil {
		t.Fatal("404 attendu")
	}
	if p := server.last(t).Path; p != "/emby/Videos/ActiveEncodings?DeviceId=test-device-id&PlaySessionId=ps1" || server.last(t).Method != "DELETE" {
		t.Fatal(p)
	}
}

// ---------------------------------------------------------- transport errors

func TestHungServerTimesOut(t *testing.T) {
	server, err := ParseServerURL(hungServer(t))
	if err != nil {
		t.Fatal(err)
	}
	c, _ := NewClientWithTimeouts(server, fakeDevice, time.Second, 200*time.Millisecond)
	_, err = c.PublicInfo(bg)
	wantKind(t, err, KindTimeout)
}

func TestCancelledContextIsCancelled(t *testing.T) {
	c := fakeClient(t, hungServer(t))
	ctx, cancel := context.WithCancel(bg)
	time.AfterFunc(100*time.Millisecond, cancel)
	_, err := c.PublicInfo(ctx)
	wantKind(t, err, KindCancelled)
	// A deadline of the caller is a timeout.
	ctx, cancel = context.WithTimeout(bg, 100*time.Millisecond)
	defer cancel()
	_, err = c.PublicInfo(ctx)
	wantKind(t, err, KindTimeout)
}

func TestDroppedConnectionIsUnreachable(t *testing.T) {
	server := startFakeEmby(t, func(fakeRequest) (int, string) { return 0, "" })
	_, err := fakeClient(t, server.URL).PublicInfo(bg)
	wantKind(t, err, KindUnreachable)
	if len(server.requests()) != 1 {
		t.Fatalf("%d requêtes : la requête a été renvoyée", len(server.requests()))
	}
}

// ---------------------------------------------------------- strict decoding

func TestAnswersAreDecodedWithSerdeRules(t *testing.T) {
	type answer struct {
		path string
		body string
		ok   bool
	}
	for _, a := range []answer{
		{"public", fakeFixture("public_info"), true},
		// Missing required field, wrong case, null for a String: refused like serde.
		{"public", `{"ServerName":"S","Version":"4"}`, false},
		{"public", `{"servername":"S","Version":"4","Id":"x"}`, false},
		{"public", `{"ServerName":null,"Version":"4","Id":"x"}`, false},
		{"public", `{"ServerName":"S","Version":"4","Id":"x","Id":"y"}`, false},
		{"public", `{"ServerName":"S","Version":"4","Id":"x"} trailing`, false},
		{"public", `{"ServerName":"S","Version":"4","Id":"x","Unknown":[1,{}]}`, true},
		// Default fields may be absent; Option fields may be null.
		{"views", `{"Items":[{"Id":"1","CollectionType":null}]}`, true},
		{"views", `{}`, true},
		{"views", `{"Items":null}`, false},
		{"views", `{"Items":[{"Name":"sans id"}]}`, false},
		{"views", `{"Items":[{"Id":"1","ImageTags":null}]}`, false},
		{"views", `{"Items":[{"Id":"1","ProductionYear":1.5}]}`, false},
		{"views", `{"TotalRecordCount":-1}`, false},
		{"views", `{"Items":[{"Id":"1","Collections":null,"UserData":{"Played":true}}]}`, true},
	} {
		server := startFakeEmby(t, func(fakeRequest) (int, string) { return 200, a.body })
		c := fakeClient(t, server.URL).WithToken(NewAccessToken(fakeToken))
		var err error
		if a.path == "public" {
			_, err = c.PublicInfo(bg)
		} else {
			_, err = c.Views(bg, fakeUserID)
		}
		if a.ok && err != nil {
			t.Errorf("%s refusé : %v", a.body, err)
		}
		if !a.ok {
			if err == nil {
				t.Errorf("%s accepté", a.body)
			} else {
				wantKind(t, err, KindInvalidResponse)
			}
		}
	}
}

func TestEmptyTokenIsRefused(t *testing.T) {
	server := startFakeEmby(t, func(fakeRequest) (int, string) {
		return 200, strings.ReplaceAll(fakeFixture("auth_result"), fakeToken, "")
	})
	_, err := fakeClient(t, server.URL).Authenticate(bg, "testeur", fakePassword)
	wantKind(t, err, KindInvalidResponse)
	if !strings.Contains(err.Error(), "jeton absent") {
		t.Fatal(err)
	}
}
