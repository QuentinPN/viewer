package emby

// tests.rs, revoked token to playback state (lines 1931–3315), through EmbyService.
// artwork_cache_spares_the_server_and_keeps_its_checks is in package emby_test; the
// real_server_* tests are skipped stubs.

import (
	"encoding/json"
	"fmt"
	"math"
	"reflect"
	"slices"
	"strings"
	"sync/atomic"
	"testing"

	"viewer/internal/player"
)

// detTokenHeader is `vec![("X-Emby-Token", TOKEN)]`.
var detTokenHeader = []player.Header{{"X-Emby-Token", fakeToken}}

// detJSON is `serde_json::to_value`.
func detJSON(t *testing.T, v any) map[string]any {
	t.Helper()
	var out map[string]any
	if err := json.Unmarshal(marshal(t, v), &out); err != nil {
		t.Fatal(err)
	}
	return out
}

// detBody is the JSON body of a recorded request.
func detBody(t *testing.T, r fakeRequest) map[string]any {
	t.Helper()
	var out map[string]any
	if err := json.Unmarshal([]byte(r.Body), &out); err != nil {
		t.Fatalf("%v: %s", err, r.Body)
	}
	return out
}

// detInfos is every PlaybackInfo request, in order.
func detInfos(server *fakeEmby) []fakeRequest {
	var out []fakeRequest
	for _, r := range server.requests() {
		if strings.Contains(r.Path, "/PlaybackInfo") {
			out = append(out, r)
		}
	}
	return out
}

// detLastInfo is `info_body`: the body of the last PlaybackInfo.
func detLastInfo(t *testing.T, server *fakeEmby) map[string]any {
	t.Helper()
	infos := detInfos(server)
	if len(infos) == 0 {
		t.Fatal("aucun PlaybackInfo")
	}
	return detBody(t, infos[len(infos)-1])
}

// detFirstInfo is `find(|r| r.path.contains("/PlaybackInfo"))`.
func detFirstInfo(t *testing.T, server *fakeEmby) fakeRequest {
	t.Helper()
	infos := detInfos(server)
	if len(infos) == 0 {
		t.Fatal("aucun PlaybackInfo")
	}
	return infos[0]
}

// detPaths is every request target.
func detPaths(server *fakeEmby) []string {
	var out []string
	for _, r := range server.requests() {
		out = append(out, r.Path)
	}
	return out
}

// detNoToken is `assert!(!format!("{plan:?}").contains(TOKEN))`, on both the Go
// printing and the JSON of the value.
func detNoToken(t *testing.T, v any) {
	t.Helper()
	if s := fmt.Sprintf("%+v", v); strings.Contains(s, fakeToken) {
		t.Fatalf("jeton dans %s", s)
	}
	if s := string(marshal(t, v)); strings.Contains(s, fakeToken) {
		t.Fatalf("jeton dans %s", s)
	}
}

func detStr(p *string) string {
	if p == nil {
		return "<nil>"
	}
	return *p
}

func detU32(p *uint32) string {
	if p == nil {
		return "<nil>"
	}
	return fmt.Sprint(*p)
}

func detImage(t *testing.T, ref *ImageRef) ImageRef {
	t.Helper()
	if ref == nil {
		t.Fatal("image absente")
	}
	return *ref
}

func Test_token_revoked_while_browsing_ends_the_session(t *testing.T) {
	_, fx, revoked := signedInBrowser(t)
	revoked.Store(true)

	_, err := fx.service.Libraries(bg)
	wantErr(t, err, ErrSessionExpired)
	if storedToken(t, fx) != nil || fx.service.CurrentUser() != nil {
		t.Fatal("session gardée")
	}
	fx = reopen(fx.store, fx.path)
	if k := reauthKind(t, mustRestore(t, fx)); k != KindSessionExpired {
		t.Fatal(k)
	}
}

func Test_artwork_is_fetched_with_auth_and_checked(t *testing.T) {
	server, fx, _ := signedInBrowser(t)
	q := "tag=7b213eb5_6379&maxWidth=360"
	req, ok := ParseImageRequest("/"+fakeServerID+"/101/Primary", &q)
	if !ok {
		t.Fatal("ParseImageRequest")
	}
	contentType, data, err := fx.service.Image(bg, req)
	if err != nil || contentType != "image/jpeg" || string(data) != "fake-jpeg-bytes" {
		t.Fatalf("%s %q %v", contentType, data, err)
	}
	sent := server.last(t)
	if !strings.HasPrefix(sent.Path, "/emby/Items/101/Images/Primary?") {
		t.Fatal(sent.Path)
	}
	if !strings.Contains(sent.Path, "maxWidth=360") || !strings.Contains(sent.Path, "tag=7b213eb5_6379") {
		t.Fatal(sent.Path)
	}
	if !strings.Contains(sent.Auth, `Token="`+fakeToken+`"`) {
		t.Fatal(sent.Auth)
	}

	q2 := "tag=x&maxWidth=360"
	notImage, ok := ParseImageRequest("/"+fakeServerID+"/102/Primary", &q2)
	if !ok {
		t.Fatal("ParseImageRequest")
	}
	_, _, err = fx.service.Image(bg, notImage)
	wantKind(t, err, KindInvalidResponse)

	// A URL built for another server is refused without any request (ADR 0012).
	before := len(server.requests())
	foreign, ok := ParseImageRequest("/otherserver/101/Primary", &q)
	if !ok {
		t.Fatal("ParseImageRequest")
	}
	_, _, err = fx.service.Image(bg, foreign)
	wantErr(t, err, ErrForbidden)
	if len(server.requests()) != before {
		t.Fatal("requête envoyée pour un autre serveur")
	}

	if err := fx.service.SignOut(bg); err != nil {
		t.Fatal(err)
	}
	_, _, err = fx.service.Image(bg, req)
	wantErr(t, err, ErrNotSignedIn)
}

func Test_movie_detail_maps_metadata_and_tech_info_without_the_server_path(t *testing.T) {
	server, fx, _ := signedInBrowser(t)
	d, err := fx.service.ItemDetail(bg, "30207")
	if err != nil {
		t.Fatal(err)
	}

	if p := server.last(t).Path; p != "/emby/Users/"+fakeUserID+"/Items/30207" {
		t.Fatal(p)
	}
	js := string(marshal(t, d))
	if strings.Contains(js, "/share") {
		t.Fatalf("Server path leaked: %s", js)
	}
	if strings.Contains(js, server.URL) {
		t.Fatalf("server address leaked: %s", js)
	}

	if d.Kind != DetailMovie || !d.Favorite {
		t.Fatalf("%s %v", d.Kind, d.Favorite)
	}
	if detStr(d.OriginalTitle) != "2 Hearts" || detStr(d.Tagline) != "Découvrez le mystère qui les relie tous." {
		t.Fatal(detStr(d.OriginalTitle), detStr(d.Tagline))
	}
	if d.CommunityRating == nil || *d.CommunityRating != 6.2 {
		t.Fatal(d.CommunityRating)
	}
	if detU32(d.CriticRating) != "17" || detU32(d.RuntimeMinutes) != "101" {
		t.Fatal(detU32(d.CriticRating), detU32(d.RuntimeMinutes))
	}
	if !slices.Equal(d.Directors, []string{"Lance Hool"}) {
		t.Fatal(d.Directors)
	}
	if len(d.Cast) != 1 || detStr(d.Cast[0].Role) != "Chris" || detStr(d.Cast[0].ID) != "30209" {
		t.Fatalf("%+v", d.Cast)
	}
	if photo := detImage(t, d.Cast[0].Photo); !strings.Contains(photo.Src, "/30209/Primary?tag=4b8a&maxWidth=120") {
		t.Fatal(photo.Src)
	}
	if !slices.Equal(d.Studios, []string{"Silver Lion Films"}) {
		t.Fatal(d.Studios)
	}
	if d.Resume == nil || d.Resume.PositionSeconds != 1831 || d.Resume.Fraction == nil || math.Abs(*d.Resume.Fraction-0.3) >= 0.001 {
		t.Fatalf("%+v", d.Resume)
	}
	if b := detImage(t, d.Backdrop); !strings.Contains(b.Src, "/30207/Backdrop?tag=2edd4b0e_6379") {
		t.Fatal(b.Src)
	}
	if l := detImage(t, d.Logo); !strings.Contains(l.Src, "/30207/Logo?") {
		t.Fatal(l.Src)
	}
	if d.EndYear != nil || d.Continuing != nil || d.UnplayedCount != nil {
		t.Fatal(d.EndYear, d.Continuing, d.UnplayedCount)
	}

	media := d.Media[0]
	if detStr(media.Container) != "mkv" {
		t.Fatal(detStr(media.Container))
	}
	if v := media.Video; v == nil || detU32(v.Height) != "1038" || detU32(v.BitDepth) != "10" || detStr(v.Range) != "HDR 10" {
		t.Fatalf("%+v", media.Video)
	}
	if len(media.Audio) != 2 || detStr(media.Audio[0].Language) != "fre" || detU32(media.Audio[0].Channels) != "6" {
		t.Fatalf("%+v", media.Audio)
	}
	if len(media.Subtitles) != 2 {
		t.Fatalf("embedded images are not subtitles: %+v", media.Subtitles)
	}
	if !media.Subtitles[0].IsForced {
		t.Fatal("forcés attendus")
	}
}

func Test_person_titles_read_with_person_ids_newest_first(t *testing.T) {
	server, fx, _ := signedInBrowser(t)
	titles, err := fx.service.Titles(bg, TitlesSource{Kind: TitlesPerson, ID: "30209"})
	if err != nil {
		t.Fatal(err)
	}
	var got []string
	for _, m := range titles {
		got = append(got, m.ID)
	}
	if !slices.Equal(got, []string{"30400", "30207", "30401"}) {
		t.Fatal(got)
	}
	if p := detImage(t, titles[0].Poster); !strings.Contains(p.Src, "/30400/Primary?") {
		t.Fatal(p.Src)
	}
	req := server.last(t)
	if !strings.HasPrefix(req.Path, "/emby/Users/"+fakeUserID+"/Items?") || !strings.Contains(req.Path, "PersonIds=30209") {
		t.Fatal(req.Path)
	}
	if strings.Contains(req.Path, fakeToken) {
		t.Fatal("token in the URL")
	}

	if _, err := fx.service.Titles(bg, TitlesSource{Kind: TitlesPerson, ID: "../x"}); err == nil {
		t.Fatal("id refusé attendu")
	}
}

func Test_similar_collection_and_trailer_of_a_movie(t *testing.T) {
	server, fx, _ := signedInBrowser(t)
	d, err := fx.service.ItemDetail(bg, "30207")
	if err != nil {
		t.Fatal(err)
	}
	if len(d.Collections) != 1 || d.Collections[0].ID != "120034" {
		t.Fatalf("%+v", d.Collections)
	}
	if !d.HasLocalTrailer {
		t.Fatal("bande-annonce attendue")
	}

	similar, err := fx.service.Titles(bg, TitlesSource{Kind: TitlesSimilar, ID: "30207"})
	if err != nil {
		t.Fatal(err)
	}
	var got []string
	for _, m := range similar {
		got = append(got, m.ID)
	}
	if !slices.Equal(got, []string{"30400", "30401"}) {
		t.Fatal(got, "the title itself left out")
	}
	req := server.last(t)
	if !strings.HasPrefix(req.Path, "/emby/Items/30207/Similar?") || !strings.Contains(req.Path, "UserId="+fakeUserID) {
		t.Fatal(req.Path)
	}
	if strings.Contains(req.Path, fakeToken) {
		t.Fatal("token in the URL")
	}

	boxset, err := fx.service.Titles(bg, TitlesSource{Kind: TitlesCollection, ID: "120034"})
	if err != nil || len(boxset) != 3 {
		t.Fatalf("%+v %v", boxset, err)
	}

	trailer, err := fx.service.LocalTrailer(bg, "30207")
	if err != nil || detStr(trailer) != "30500" {
		t.Fatal(detStr(trailer), err)
	}
	if p := server.last(t).Path; !strings.Contains(p, "/30207/LocalTrailers?EnableImages=false") {
		t.Fatal(p)
	}
	if _, err := fx.service.LocalTrailer(bg, "../x"); err == nil {
		t.Fatal("id refusé attendu")
	}
}

func Test_series_detail_seasons_and_episodes(t *testing.T) {
	server, fx, _ := signedInBrowser(t)
	d, err := fx.service.ItemDetail(bg, "80473")
	if err != nil {
		t.Fatal(err)
	}
	if d.Kind != DetailSeries {
		t.Fatal(d.Kind)
	}
	if d.Year == nil || *d.Year != 2024 || d.EndYear == nil || *d.EndYear != 2024 || d.Continuing == nil || *d.Continuing {
		t.Fatal(d.Year, d.EndYear, d.Continuing)
	}
	if detU32(d.UnplayedCount) != "24" {
		t.Fatal(detU32(d.UnplayedCount))
	}
	if d.RuntimeMinutes != nil {
		t.Fatal("a series has no single runtime")
	}
	if len(d.Media) != 0 || d.Backdrop != nil || d.Tagline != nil {
		t.Fatalf("%+v %+v %v", d.Media, d.Backdrop, d.Tagline)
	}

	seasons, err := fx.service.Seasons(bg, "80473")
	if err != nil {
		t.Fatal(err)
	}
	var names []string
	for _, s := range seasons {
		names = append(names, detU32(s.Index)+" "+s.Name)
	}
	if !slices.Equal(names, []string{"0 Épisodes spéciaux", "1 Saison 1"}) {
		t.Fatal(names, "a season of another series is dropped")
	}
	if !seasons[1].Played {
		t.Fatal("saison vue attendue")
	}
	req := server.last(t)
	if !strings.HasPrefix(req.Path, "/emby/Shows/80473/Seasons?") {
		t.Fatal(req.Path)
	}
	if !strings.Contains(req.Path, "UserId="+fakeUserID) {
		t.Fatal(req.Path)
	}

	page, err := fx.service.Episodes(bg, "80473", "80474", 0, 500)
	if err != nil {
		t.Fatal(err)
	}
	req = server.last(t)
	if path, _ := splitQuery(req.Path); path != "/emby/Shows/80473/Episodes" {
		t.Fatal(path)
	}
	wantPairs(t, req.Path, "SeasonId=80474", "StartIndex=0", "Limit=200", "Fields=Overview")
	if strings.Contains(req.Path, fakeToken) {
		t.Fatal(req.Path)
	}
	if page.Total != 24 || page.NextIndex != 3 {
		t.Fatal(page.Total, page.NextIndex)
	}
	first := page.Items[0]
	if detU32(first.SeasonIndex) != "1" || detU32(first.Index) != "1" || detU32(first.RuntimeMinutes) != "24" {
		t.Fatalf("%+v", first)
	}
	if first.Resume == nil || first.Resume.PositionSeconds != 720 {
		t.Fatalf("%+v", first.Resume)
	}
	if th := detImage(t, first.Thumb); !strings.Contains(th.Srcset, " 640w") {
		t.Fatal(th.Srcset)
	}
	double := page.Items[1]
	if detU32(double.Index) != "2" || detU32(double.IndexEnd) != "3" {
		t.Fatalf("%+v", double)
	}
	if !double.Played || double.Resume != nil || double.Thumb != nil {
		t.Fatalf("%+v", double)
	}
	if detU32(page.Items[2].SeasonIndex) != "0" {
		t.Fatal("a special aired during the season is kept")
	}
}

func Test_episode_detail_has_series_context_guests_and_neighbours_across_seasons(t *testing.T) {
	server, fx, _ := signedInBrowser(t)
	d, err := fx.service.ItemDetail(bg, "80475")
	if err != nil {
		t.Fatal(err)
	}
	if d.Kind != DetailEpisode || d.Name != "La Nouvelle Recrue" {
		t.Fatal(d.Kind, d.Name)
	}
	if js := string(marshal(t, d)); strings.Contains(js, "/share") {
		t.Fatalf("Server path leaked: %s", js)
	}

	ep := d.Episode
	if ep == nil {
		t.Fatal("contexte d'épisode absent")
	}
	if ep.SeriesID != "80473" || ep.SeriesName != "2.5 Dimensional Seduction" || detStr(ep.SeasonID) != "80474" {
		t.Fatalf("%+v", ep)
	}
	if detU32(ep.SeasonIndex) != "1" || detU32(ep.Index) != "1" || ep.IndexEnd != nil {
		t.Fatalf("%+v", ep)
	}
	prev := ep.Previous
	if prev == nil || prev.ID != "80470" || detU32(prev.SeasonIndex) != "0" || detU32(prev.Index) != "1" {
		t.Fatalf("another series' episode in between is skipped: %+v", prev)
	}
	next := ep.Next
	if next == nil || next.ID != "80476" || next.Name != "Double" || detU32(next.IndexEnd) != "3" {
		t.Fatalf("%+v", next)
	}

	if !slices.Equal(d.Directors, []string{"Kyo Kim"}) || !slices.Equal(d.Writers, []string{"Mariko Kunisawa"}) {
		t.Fatal(d.Directors, d.Writers)
	}
	var guests []string
	for _, c := range d.Cast {
		guests = append(guests, c.Name)
	}
	if !slices.Equal(guests, []string{"Invitée"}) {
		t.Fatal(guests, "the regular cast belongs to the series")
	}
	if len(d.Studios) != 0 || detU32(d.RuntimeMinutes) != "24" {
		t.Fatal(d.Studios, detU32(d.RuntimeMinutes))
	}
	if d.CommunityRating == nil || *d.CommunityRating != 7.4 {
		t.Fatal(d.CommunityRating)
	}
	if d.Resume == nil || d.Resume.PositionSeconds != 720 {
		t.Fatalf("%+v", d.Resume)
	}
	if d.EndYear != nil || d.Continuing != nil || d.UnplayedCount != nil {
		t.Fatal(d.EndYear, d.Continuing, d.UnplayedCount)
	}
	backdrop := detImage(t, d.Backdrop)
	if !strings.Contains(backdrop.Src, "/80475/Primary?tag=9e617fa1_6385") {
		t.Fatal(backdrop.Src, "the episode's own image first")
	}
	if !strings.Contains(backdrop.Srcset, " 1920w") {
		t.Fatal(backdrop.Srcset)
	}
	if p := detImage(t, d.Poster); !strings.Contains(p.Src, "/80473/Primary?tag=4b62464b_6385") {
		t.Fatal(p.Src)
	}
	if l := detImage(t, d.Logo); !strings.Contains(l.Src, "/80473/Logo?tag=l0g0_6385") {
		t.Fatal(l.Src)
	}
	media := d.Media[0]
	if detStr(media.Audio[0].Language) != "jpn" || len(media.Subtitles) != 1 {
		t.Fatalf("%+v", media)
	}

	adjacent := server.last(t)
	if path, _ := splitQuery(adjacent.Path); path != "/emby/Shows/80473/Episodes" {
		t.Fatal(path)
	}
	wantPairs(t, adjacent.Path, "AdjacentTo=80475", "UserId="+fakeUserID, "EnableImages=false", "EnableUserData=false")
	if strings.Contains(adjacent.Path, fakeToken) {
		t.Fatal(adjacent.Path)
	}

	// No own image: the series backdrop; not in the server's answer: no neighbours.
	bare, err := fx.service.ItemDetail(bg, "80476")
	if err != nil {
		t.Fatal(err)
	}
	if b := detImage(t, bare.Backdrop); !strings.Contains(b.Src, "/80473/Backdrop?tag=b4ckd70p_6385") {
		t.Fatal(b.Src)
	}
	bep := bare.Episode
	if bep == nil || detU32(bep.Index) != "2" || detU32(bep.IndexEnd) != "3" {
		t.Fatalf("%+v", bep)
	}
	if bep.Previous != nil || bep.Next != nil {
		t.Fatalf("%+v %+v", bep.Previous, bep.Next)
	}
	if bare.Poster != nil || bare.Logo != nil {
		t.Fatalf("%+v %+v", bare.Poster, bare.Logo)
	}
}

// Chapters (ADR 0034): the item's `Chapters` of Emby 4.10.2.0, in seconds.
func Test_chapters_come_from_the_item_in_seconds(t *testing.T) {
	server, fx, revoked := signedInBrowser(t)
	found, err := fx.service.Chapters(bg, "30207")
	if err != nil {
		t.Fatal(err)
	}
	if p := server.last(t).Path; p != "/emby/Users/"+fakeUserID+"/Items/30207" {
		t.Fatal(p)
	}
	var got []string
	for _, ch := range found {
		got = append(got, fmt.Sprintf("%v %s", ch.Start, ch.Name))
	}
	if !slices.Equal(got, []string{"0 Chapitre 1", "487.57 Chapitre 2", "726.558 Chapitre 3"}) {
		t.Fatal(got)
	}
	again, err := fx.service.Chapters(bg, "30207")
	if err != nil {
		t.Fatal(err)
	}
	// serde writes `0.0` where encoding/json writes `0`: same number (MAP.md).
	if js := string(marshal(t, again)); !strings.HasPrefix(js, `[{"start":0,"name":"Chapitre 1"}`) {
		t.Fatal(js)
	}

	// No chapters: nothing to show, no error.
	if none, err := fx.service.Chapters(bg, "80473"); err != nil || len(none) != 0 {
		t.Fatal(none, err)
	}
	_, err = fx.service.Chapters(bg, "999")
	wantErr(t, err, ErrNotFound)
	revoked.Store(true)
	_, err = fx.service.Chapters(bg, "30207")
	wantErr(t, err, ErrSessionExpired)
}

func Test_detail_refuses_foreign_seasons_unknown_ids_and_other_types(t *testing.T) {
	server, fx, revoked := signedInBrowser(t)
	_, err := fx.service.Episodes(bg, "80473", "67639", 0, 50)
	wantErr(t, err, ErrNotFound)

	_, err = fx.service.ItemDetail(bg, "999")
	wantErr(t, err, ErrNotFound)
	_, err = fx.service.Seasons(bg, "999")
	wantErr(t, err, ErrNotFound)
	_, err = fx.service.ItemDetail(bg, "80474")
	wantKind(t, err, KindInvalidResponse)

	before := len(server.requests())
	for _, bad := range []string{"../Users", "1?x=2", ""} {
		if _, err := fx.service.ItemDetail(bg, bad); err == nil {
			t.Fatalf("%q accepté", bad)
		}
		if _, err := fx.service.Episodes(bg, "80473", bad, 0, 10); err == nil {
			t.Fatalf("%q accepté", bad)
		}
	}
	if len(server.requests()) != before {
		t.Fatal("unsafe ids never reach the server")
	}

	revoked.Store(true)
	_, err = fx.service.Seasons(bg, "80473")
	wantErr(t, err, ErrSessionExpired)
	if storedToken(t, fx) != nil {
		t.Fatal("jeton gardé")
	}
}

func Test_playback_negotiates_direct_play_with_the_token_in_a_header_only(t *testing.T) {
	server, fx, _ := signedInBrowser(t)

	res, err := fx.service.PreparePlayback(bg, "30207", false)
	if err != nil {
		t.Fatal(err)
	}
	plan := res.Plan
	if plan.Method != MethodDirectPlay {
		t.Fatal(plan.Method)
	}
	want := server.URL + "/emby/videos/30207/original.mkv?DeviceId=dev&MediaSourceId=mediasource_30207&PlaySessionId=ps1"
	if plan.URL != want {
		t.Fatalf("%s\n%s", plan.URL, want)
	}
	if strings.Contains(plan.URL, fakeToken) {
		t.Fatal(plan.URL)
	}
	if !slices.Equal(res.Headers, detTokenHeader) {
		t.Fatal(res.Headers)
	}
	if plan.MediaSourceID != "mediasource_30207" || detStr(plan.PlaySessionID) != "ps1" {
		t.Fatal(plan.MediaSourceID, detStr(plan.PlaySessionID))
	}

	// The item is read at the same time (ADR 0017): find PlaybackInfo among both.
	req := detFirstInfo(t, server)
	if req.Method != "POST" || req.Path != "/emby/Items/30207/PlaybackInfo?UserId="+fakeUserID {
		t.Fatal(req.Method, req.Path)
	}
	if !strings.Contains(req.Auth, `Token="`+fakeToken+`"`) {
		t.Fatal(req.Auth)
	}
	body := detBody(t, req)
	if body["UserId"] != fakeUserID || body["IsPlayback"] != true || body["AutoOpenLiveStream"] != false {
		t.Fatal(body)
	}
	if b, ok := body["MaxStreamingBitrate"].(float64); !ok || b <= 0 {
		t.Fatal(body["MaxStreamingBitrate"])
	}
	profile := body["DeviceProfile"].(map[string]any)
	if profile["DirectPlayProfiles"].([]any)[0].(map[string]any)["Type"] != "Video" {
		t.Fatal(profile["DirectPlayProfiles"])
	}
	if strings.Contains(req.Body, fakeToken) {
		t.Fatal(req.Body)
	}
}

func Test_playback_of_one_version_names_it_to_the_server_and_in_reports(t *testing.T) {
	server, fx, _ := signedInBrowser(t)

	// The detail lists each version with the id to play it; an unsafe id is dropped.
	d, err := fx.service.ItemDetail(bg, "30500")
	if err != nil {
		t.Fatal(err)
	}
	var ids []string
	for _, m := range d.Media {
		ids = append(ids, detStr(m.ID))
	}
	if !slices.Equal(ids, []string{"mediasource_30500", "30501", "<nil>"}) {
		t.Fatal(ids)
	}
	if detStr(d.Media[1].Name) != "2160p" {
		t.Fatal(detStr(d.Media[1].Name))
	}

	// No choice: no `MediaSourceId`, the server's first source, as before.
	res, err := fx.service.PrepareVersion(bg, "30500", nil, false, false)
	if err != nil {
		t.Fatal(err)
	}
	if res.Plan.MediaSourceID != "mediasource_30500" {
		t.Fatal(res.Plan.MediaSourceID)
	}
	if _, ok := detLastInfo(t, server)["MediaSourceId"]; ok {
		t.Fatal("MediaSourceId envoyé sans choix")
	}

	res, err = fx.service.PrepareVersion(bg, "30500", ptr("30501"), false, false)
	if err != nil {
		t.Fatal(err)
	}
	plan := res.Plan
	if plan.MediaSourceID != "30501" {
		t.Fatal(plan.MediaSourceID)
	}
	if !strings.Contains(plan.URL, "MediaSourceId=30501") || strings.Contains(plan.URL, fakeToken) {
		t.Fatal(plan.URL)
	}
	if v := detLastInfo(t, server)["MediaSourceId"]; v != "30501" {
		t.Fatal(v)
	}
	if a := NewActivePlayback("30500", &plan); a.MediaSourceID != "30501" {
		t.Fatal(a.MediaSourceID, "start, progress and stop reports name the version played")
	}

	// The transcode fallback keeps the version (ADR 0023).
	_, _ = fx.service.PrepareVersion(bg, "30500", ptr("30501"), false, true)
	forced := detLastInfo(t, server)
	if forced["MediaSourceId"] != "30501" || forced["EnableDirectPlay"] != false {
		t.Fatal(forced)
	}

	// A version the server no longer has is not swapped for another one.
	_, err = fx.service.PrepareVersion(bg, "30500", ptr("30502"), false, false)
	wantErr(t, err, ErrNoCompatibleStream)

	before := len(server.requests())
	for _, bad := range []string{"../x", "a b", `a"x`, ""} {
		if _, err := fx.service.PrepareVersion(bg, "30500", ptr(bad), false, false); err == nil {
			t.Fatalf("%q accepté", bad)
		}
	}
	if len(server.requests()) != before {
		t.Fatal("unsafe ids never reach the server")
	}
}

func Test_playback_transcodes_through_hls_under_the_bitrate_limit(t *testing.T) {
	server := startFakeEmby(t, fakeBrowseLike(new(atomic.Bool)))
	fx := newFixture(t)
	service := fx.service.WithMaxStreamingBitrate(3_000_000)
	if _, err := service.SignIn(bg, server.URL, "testeur", fakePassword); err != nil {
		t.Fatal(err)
	}

	res, err := service.PreparePlayback(bg, "40000", false)
	if err != nil {
		t.Fatal(err)
	}
	plan := res.Plan
	if plan.Method != MethodTranscode {
		t.Fatal(plan.Method)
	}
	want := server.URL + "/emby/videos/40000/master.m3u8?DeviceId=dev&MediaSourceId=mediasource_40000&PlaySessionId=ps2&VideoCodec=h264&TranscodeReasons=ContainerNotSupported"
	if plan.URL != want {
		t.Fatalf("%s\n%s", plan.URL, want)
	}
	if detStr(plan.Container) != "ts" || detStr(plan.TranscodeReasons) != "conteneur non pris en charge" {
		t.Fatal(detStr(plan.Container), detStr(plan.TranscodeReasons))
	}
	if len(plan.Subtitles) != 1 {
		t.Fatalf("%+v", plan.Subtitles)
	}
	if s := plan.Subtitles[0].Source; s != server.URL+"/emby/Videos/40000/mediasource_40000/Subtitles/2/0/Stream.subrip" {
		t.Fatal(s)
	}
	if !plan.Subtitles[0].Selected {
		t.Fatal("sous-titre choisi attendu")
	}
	detNoToken(t, plan)
	if !slices.Equal(res.Headers, detTokenHeader) {
		t.Fatal(res.Headers)
	}

	body := detBody(t, detFirstInfo(t, server))
	if body["MaxStreamingBitrate"] != float64(3_000_000) {
		t.Fatal(body["MaxStreamingBitrate"])
	}
	if body["DeviceProfile"].(map[string]any)["MaxStreamingBitrate"] != float64(3_000_000) {
		t.Fatal(body["DeviceProfile"])
	}
}

func Test_playback_quality_caps_the_bitrate_of_a_local_address(t *testing.T) {
	server, fx, _ := signedInBrowser(t)
	quality, err := fx.service.PlaybackQuality()
	if err != nil {
		t.Fatal(err)
	}
	if quality.Local != nil || quality.Remote != nil || !quality.LocalAddress {
		t.Fatalf("%+v", quality)
	}
	if len(quality.Choices) != 7 {
		t.Fatal(quality.Choices)
	}

	// The fake server is on 127.0.0.1: a remote cap does not apply.
	if _, err := fx.service.SetPlaybackQuality(QualityCaps{Remote: u64(1_500_000)}); err != nil {
		t.Fatal(err)
	}
	res, err := fx.service.PreparePlayback(bg, "30207", false)
	if err != nil {
		t.Fatal(err)
	}
	if l := res.Plan.BitrateLimit; l == nil || !reflect.DeepEqual(*l, StreamingLimit{Bitrate: 400_000_000, LocalAddress: true}) {
		t.Fatalf("%+v", l)
	}
	if a, b := lastBitrate(t, server); a != 400_000_000 || b != 400_000_000 {
		t.Fatal(a, b)
	}

	saved, err := fx.service.SetPlaybackQuality(QualityCaps{Local: u64(4_000_000), Remote: u64(1_500_000)})
	if err != nil {
		t.Fatal(err)
	}
	if saved.Local == nil || *saved.Local != 4_000_000 || saved.Remote == nil || *saved.Remote != 1_500_000 {
		t.Fatalf("%+v", saved)
	}
	res, err = fx.service.PreparePlayback(bg, "30207", false)
	if err != nil {
		t.Fatal(err)
	}
	if l := res.Plan.BitrateLimit; l == nil || l.Cap == nil || *l.Cap != 4_000_000 {
		t.Fatalf("%+v", l)
	}
	if a, b := lastBitrate(t, server); a != 4_000_000 || b != 4_000_000 {
		t.Fatal(a, b)
	}

	// A value off the list is refused and changes nothing.
	_, err = fx.service.SetPlaybackQuality(QualityCaps{Local: u64(5_000_000)})
	wantKind(t, err, "settings")

	// Kept across a restart, for the same server.
	again := reopen(fx.store, fx.path)
	mustSignIn(t, again, server.URL)
	quality, err = again.service.PlaybackQuality()
	if err != nil {
		t.Fatal(err)
	}
	if quality.Local == nil || *quality.Local != 4_000_000 || quality.Remote == nil || *quality.Remote != 1_500_000 {
		t.Fatalf("%+v", quality)
	}
	if _, err := again.service.PreparePlayback(bg, "30207", false); err != nil {
		t.Fatal(err)
	}
	if a, b := lastBitrate(t, server); a != 4_000_000 || b != 4_000_000 {
		t.Fatal(a, b)
	}

	// Signed out: nothing to read or write.
	if err := again.service.SignOut(bg); err != nil {
		t.Fatal(err)
	}
	_, err = again.service.PlaybackQuality()
	wantKind(t, err, "notSignedIn")
}

func Test_playback_quality_yields_to_the_debug_override(t *testing.T) {
	server := startFakeEmby(t, fakeBrowseLike(new(atomic.Bool)))
	service := newFixture(t).service.WithMaxStreamingBitrate(3_000_000)
	if _, err := service.SignIn(bg, server.URL, "testeur", fakePassword); err != nil {
		t.Fatal(err)
	}
	if _, err := service.SetPlaybackQuality(QualityCaps{Local: u64(40_000_000)}); err != nil {
		t.Fatal(err)
	}
	res, err := service.PreparePlayback(bg, "30207", false)
	if err != nil {
		t.Fatal(err)
	}
	if l := res.Plan.BitrateLimit; l == nil || l.Cap == nil || *l.Cap != 3_000_000 {
		t.Fatalf("%+v", l)
	}
	if a, b := lastBitrate(t, server); a != 3_000_000 || b != 3_000_000 {
		t.Fatal(a, b)
	}
}

func Test_track_preferences_pick_the_audio_of_a_transcode_and_its_subtitle_file(t *testing.T) {
	server, fx, _ := signedInBrowser(t)
	infos := func() []map[string]any {
		var out []map[string]any
		for _, r := range detInfos(server) {
			out = append(out, detBody(t, r))
		}
		return out
	}
	selected := func(plan PlaybackPlan) []bool {
		var out []bool
		for _, s := range plan.Subtitles {
			out = append(out, s.Selected)
		}
		return out
	}

	// Without preferences: one request, the server's audio and subtitle.
	res, err := fx.service.PreparePlayback(bg, "40001", false)
	if err != nil {
		t.Fatal(err)
	}
	plan := res.Plan
	if plan.Method != MethodTranscode {
		t.Fatal(plan.Method)
	}
	if len(infos()) != 1 {
		t.Fatal(len(infos()))
	}
	if _, ok := infos()[0]["AudioStreamIndex"]; ok {
		t.Fatal("AudioStreamIndex envoyé sans préférence")
	}
	if !strings.Contains(plan.URL, "AudioStreamIndex=1") {
		t.Fatal(plan.URL)
	}
	if got := selected(plan); !slices.Equal(got, []bool{true, false}) {
		t.Fatal(got)
	}

	english := TrackPreferences{AudioLanguage: ptr("eng"), SubtitleLanguage: ptr("eng"), SubtitleMode: SubtitleAlways}
	res, err = fx.service.Prepare(bg, "40001", false, false, english)
	if err != nil {
		t.Fatal(err)
	}
	plan = res.Plan
	bodies := infos()
	if len(bodies) != 3 {
		t.Fatal(len(bodies), "a second PlaybackInfo names the source and the audio")
	}
	if bodies[2]["MediaSourceId"] != "mediasource_40001" || bodies[2]["AudioStreamIndex"] != float64(2) {
		t.Fatal(bodies[2])
	}
	if !strings.Contains(plan.URL, "PlaySessionId=ps2&AudioStreamIndex=2") {
		t.Fatal(plan.URL)
	}
	if detStr(plan.PlaySessionID) != "ps2" {
		t.Fatal(detStr(plan.PlaySessionID))
	}
	if got := selected(plan); !slices.Equal(got, []bool{false, true}) {
		t.Fatal(got)
	}
	// The stream carries one audio track: the player keeps it.
	if plan.Tracks.Audio.Kind != player.TrackAuto {
		t.Fatalf("%+v", plan.Tracks.Audio)
	}
	detNoToken(t, plan)
	if !slices.Equal(res.Headers, detTokenHeader) {
		t.Fatal(res.Headers)
	}

	// Subtitles off: no file selected and the player shows none.
	res, err = fx.service.Prepare(bg, "40001", false, false, TrackPreferences{SubtitleMode: SubtitleOff})
	if err != nil {
		t.Fatal(err)
	}
	if len(infos()) != 4 {
		t.Fatal(len(infos()))
	}
	for _, s := range res.Plan.Subtitles {
		if s.Selected {
			t.Fatalf("%+v", res.Plan.Subtitles)
		}
	}
	if res.Plan.Tracks.Subtitle.Kind != player.TrackOff {
		t.Fatalf("%+v", res.Plan.Tracks.Subtitle)
	}
}

func Test_track_preferences_select_streams_of_the_file_when_playing_it(t *testing.T) {
	_, fx, _ := signedInBrowser(t)
	// The server names streams it does not list: the player picks, as before.
	res, err := fx.service.PreparePlayback(bg, "30207", false)
	if err != nil {
		t.Fatal(err)
	}
	if res.Plan.Tracks != (player.TrackSelection{}) {
		t.Fatalf("%+v", res.Plan.Tracks)
	}
	res, err = fx.service.Prepare(bg, "30207", false, false, TrackPreferences{SubtitleMode: SubtitleOff})
	if err != nil {
		t.Fatal(err)
	}
	if res.Plan.Tracks.Subtitle.Kind != player.TrackOff {
		t.Fatalf("%+v", res.Plan.Tracks.Subtitle)
	}
}

func Test_playback_fallback_refuses_direct_play_and_names_the_reason(t *testing.T) {
	server, fx, _ := signedInBrowser(t)

	// A normal negotiation keeps both direct methods allowed.
	if _, err := fx.service.PreparePlayback(bg, "30207", false); err != nil {
		t.Fatal(err)
	}
	first := detBody(t, detFirstInfo(t, server))
	if first["EnableDirectPlay"] != true || first["EnableDirectStream"] != true {
		t.Fatal(first)
	}

	res, err := fx.service.PrepareTranscode(bg, "30207", false)
	if err != nil {
		t.Fatal(err)
	}
	if res.Plan.Method != MethodTranscode {
		t.Fatal(res.Plan.Method)
	}
	if detStr(res.Plan.TranscodeReasons) != "échec de la lecture directe" {
		t.Fatal(detStr(res.Plan.TranscodeReasons))
	}
	detNoToken(t, res.Plan)
	if !slices.Equal(res.Headers, detTokenHeader) {
		t.Fatal(res.Headers)
	}
	forced := detLastInfo(t, server)
	if forced["EnableDirectPlay"] != false || forced["EnableDirectStream"] != false {
		t.Fatal(forced)
	}
}

func Test_playback_fallback_does_not_loop_when_the_server_still_offers_the_file(t *testing.T) {
	_, fx, _ := signedInBrowser(t)
	_, err := fx.service.PrepareTranscode(bg, "80475", false)
	wantErr(t, err, ErrNoCompatibleStream)
}

func Test_playback_reports_unknown_items_and_bad_ids(t *testing.T) {
	server, fx, _ := signedInBrowser(t)
	_, err := fx.service.PreparePlayback(bg, "99999", false)
	wantErr(t, err, ErrNotFound)

	before := len(server.requests())
	for _, id := range []string{"../Users", "1?x=2", ""} {
		if _, err := fx.service.PreparePlayback(bg, id, false); err == nil {
			t.Fatalf("%q accepté", id)
		}
	}
	if len(server.requests()) != before {
		t.Fatal(detPaths(server)[before:])
	}
}

func Test_playback_with_a_revoked_token_ends_the_session(t *testing.T) {
	_, fx, revoked := signedInBrowser(t)
	revoked.Store(true)
	_, err := fx.service.PreparePlayback(bg, "30207", false)
	wantErr(t, err, ErrSessionExpired)
	if fx.service.CurrentUser() != nil {
		t.Fatal("session gardée")
	}
}

func Test_resume_starts_at_the_server_position_within_the_runtime(t *testing.T) {
	server, fx, _ := signedInBrowser(t)
	res, err := fx.service.PreparePlayback(bg, "30207", true)
	if err != nil {
		t.Fatal(err)
	}
	// Film detail: PlaybackPositionTicks 18310167000, source runtime 60000000000.
	if res.Plan.StartTicks != 18_310_167_000 {
		t.Fatal(res.Plan.StartTicks)
	}
	if r := res.Plan.RunTimeTicks; r == nil || *r != 60_000_000_000 {
		t.Fatal(r)
	}
	if !slices.Contains(detPaths(server), "/emby/Users/"+fakeUserID+"/Items/30207") {
		t.Fatal(detPaths(server))
	}

	res, err = fx.service.PreparePlayback(bg, "30207", false)
	if err != nil {
		t.Fatal(err)
	}
	if res.Plan.StartTicks != 0 {
		t.Fatal(res.Plan.StartTicks)
	}
}

func Test_playback_carries_the_title_of_a_film_or_an_episode(t *testing.T) {
	server, fx, _ := signedInBrowser(t)

	res, err := fx.service.PreparePlayback(bg, "30207", false)
	if err != nil {
		t.Fatal(err)
	}
	film := res.Plan.Title
	if film == nil || film.Name != "2 Coeurs" || film.SeriesName != nil || film.SeasonIndex != nil || film.Index != nil {
		t.Fatalf("%+v", film)
	}
	// The item is read alongside PlaybackInfo, even without resume.
	if !slices.Contains(detPaths(server), "/emby/Users/"+fakeUserID+"/Items/30207") {
		t.Fatal(detPaths(server))
	}

	res, err = fx.service.PreparePlayback(bg, "80475", false)
	if err != nil {
		t.Fatal(err)
	}
	want := PlaybackTitle{Name: "La Nouvelle Recrue", SeriesName: ptr("2.5 Dimensional Seduction"), SeasonIndex: ptr[uint32](1), Index: ptr[uint32](1)}
	if res.Plan.Title == nil || !reflect.DeepEqual(*res.Plan.Title, want) {
		t.Fatalf("%+v", res.Plan.Title)
	}
}

func Test_playback_starts_without_a_title_when_the_item_cannot_be_read(t *testing.T) {
	server := startFakeEmby(t, fakeBrowseLike(new(atomic.Bool)))
	fx := newFixture(t)
	mustSignIn(t, fx, server.URL)
	// 40000 has PlaybackInfo but no item in the fake server.
	res, err := fx.service.PreparePlayback(bg, "40000", false)
	if err != nil {
		t.Fatal(err)
	}
	if res.Plan.Title != nil {
		t.Fatalf("%+v", res.Plan.Title)
	}
	// With resume the item is required: its error is the playback's.
	_, err = fx.service.PreparePlayback(bg, "40000", true)
	wantErr(t, err, ErrNotFound)
}

func Test_a_lost_stop_report_only_goes_to_the_session_it_belongs_to(t *testing.T) {
	server, fx, _ := signedInBrowser(t)
	report := Report{
		Kind: ReportStopped,
		Body: PlaybackReportBody{
			ItemID:        "30207",
			MediaSourceID: "mediasource_30207",
			PlaySessionID: ptr("ps1"),
			PositionTicks: 7_583_000_000,
			RunTimeTicks:  ptr[int64](60_000_000_000),
			PlayMethod:    ptr("Transcode"),
			Failed:        ptr(true),
		},
	}
	sent := func() []string {
		var out []string
		for _, r := range server.requests() {
			if strings.HasPrefix(r.Path, "/emby/Sessions/Playing") {
				out = append(out, r.Path)
			}
		}
		return out
	}
	owner, err := fx.service.Client()
	if err != nil {
		t.Fatal(err)
	}
	if err := fx.service.ResendStop(bg, owner, report); err != nil {
		t.Fatal(err)
	}
	inOrder := []string{"/emby/Sessions/Playing/Progress", "/emby/Sessions/Playing/Stopped"}
	if !slices.Equal(sent(), inOrder) {
		t.Fatal(sent(), "the progress carries the position")
	}

	url, err := ParseServerURL(server.URL)
	if err != nil {
		t.Fatal(err)
	}
	other, err := NewClient(url, DeviceIdentity{Name: "test", ID: "other-device", Version: "0.1.0"})
	if err != nil {
		t.Fatal(err)
	}
	other = other.WithToken(NewAccessToken("another-token"))
	err = fx.service.ResendStop(bg, other, report)
	wantErr(t, err, ErrCancelled)
	if !slices.Equal(sent(), inOrder) {
		t.Fatal(sent(), "no request for another session")
	}

	if err := fx.service.SignOut(bg); err != nil {
		t.Fatal(err)
	}
	err = fx.service.ResendStop(bg, owner, report)
	wantErr(t, err, ErrNotSignedIn)
	if !slices.Equal(sent(), inOrder) {
		t.Fatal(sent(), "no request once signed out")
	}
}

func Test_playback_reports_post_typed_bodies_with_auth_in_the_header(t *testing.T) {
	server, fx, revoked := signedInBrowser(t)
	report := func(kind ReportKind, eventName *string, failed *bool) Report {
		body := PlaybackReportBody{
			ItemID:        "30207",
			MediaSourceID: "mediasource_30207",
			PlaySessionID: ptr("ps1"),
			PositionTicks: 18_310_167_000,
			RunTimeTicks:  ptr[int64](60_000_000_000),
			EventName:     eventName,
			Failed:        failed,
		}
		if kind != ReportStopped {
			body.IsPaused, body.CanSeek, body.PlayMethod = ptr(false), ptr(true), ptr("DirectPlay")
		}
		return Report{Kind: kind, Body: body}
	}
	for _, r := range []Report{
		report(ReportStart, nil, nil),
		report(ReportProgress, ptr("TimeUpdate"), nil),
		report(ReportStopped, nil, ptr(false)),
	} {
		if err := fx.service.ReportPlayback(bg, r); err != nil {
			t.Fatal(err)
		}
	}
	var sent []fakeRequest
	for _, r := range server.requests() {
		if strings.HasPrefix(r.Path, "/emby/Sessions/") {
			sent = append(sent, r)
		}
	}
	var paths []string
	for _, r := range sent {
		paths = append(paths, r.Path)
	}
	if !slices.Equal(paths, []string{"/emby/Sessions/Playing", "/emby/Sessions/Playing/Progress", "/emby/Sessions/Playing/Stopped"}) {
		t.Fatal(paths)
	}
	for _, r := range sent {
		if r.Method != "POST" || !strings.Contains(r.Auth, `Token="`+fakeToken+`"`) {
			t.Fatalf("%+v", r)
		}
		if strings.Contains(r.Path, fakeToken) || strings.Contains(r.Body, fakeToken) {
			t.Fatalf("%+v", r)
		}
	}
	progress := detBody(t, sent[1])
	if progress["ItemId"] != "30207" || progress["PlaySessionId"] != "ps1" || progress["PositionTicks"] != float64(18_310_167_000) {
		t.Fatal(progress)
	}
	if progress["EventName"] != "TimeUpdate" || progress["PlayMethod"] != "DirectPlay" {
		t.Fatal(progress)
	}
	stopped := detBody(t, sent[2])
	if stopped["Failed"] != false {
		t.Fatal(stopped)
	}
	if _, ok := stopped["IsPaused"]; ok {
		t.Fatal(stopped)
	}

	// A bad id never reaches the server; a revoked token ends the session.
	bad := report(ReportProgress, nil, nil)
	bad.Body.ItemID = "../Users"
	if err := fx.service.ReportPlayback(bg, bad); err == nil {
		t.Fatal("id refusé attendu")
	}
	revoked.Store(true)
	err := fx.service.ReportPlayback(bg, report(ReportStopped, nil, nil))
	wantErr(t, err, ErrSessionExpired)
	if fx.service.CurrentUser() != nil {
		t.Fatal("session gardée")
	}
}

func Test_continue_watching_maps_films_and_episodes_with_wide_artwork(t *testing.T) {
	server, fx, revoked := signedInBrowser(t)
	rows, err := fx.service.ContinueWatching(bg)
	if err != nil {
		t.Fatal(err)
	}

	req := server.last(t)
	if path, _ := splitQuery(req.Path); path != "/emby/Users/"+fakeUserID+"/Items/Resume" {
		t.Fatal(path)
	}
	wantPairs(t, req.Path, "Limit=16", "MediaTypes=Video", "IncludeItemTypes=Movie%2CEpisode", "EnableUserData=true", "EnableImageTypes=Primary%2CBackdrop%2CThumb")

	var ids []string
	for _, r := range rows {
		ids = append(ids, r.ID)
	}
	if !slices.Equal(ids, []string{"30207", "70227", "70228"}) {
		t.Fatal(ids, "audio and unsafe id dropped")
	}
	film := detJSON(t, rows[0])
	if film["kind"] != "movie" || film["seriesName"] != nil || film["runtimeMinutes"] != float64(101) {
		t.Fatal(film)
	}
	if film["resume"].(map[string]any)["positionSeconds"] != float64(1831) {
		t.Fatal(film["resume"])
	}
	if src := film["image"].(map[string]any)["src"].(string); !strings.HasPrefix(src, "http://emby-img.localhost/"+fakeServerID+"/30207/Thumb?tag=bbcac8ec_6379") {
		t.Fatal(film)
	}
	episode := rows[1]
	if episode.Name != "Le Professeur" || detStr(episode.SeriesName) != "La casa de papel" {
		t.Fatalf("%+v", episode)
	}
	if detU32(episode.SeasonIndex) != "5" || detU32(episode.Index) != "2" {
		t.Fatalf("%+v", episode)
	}
	if episode.Resume == nil || episode.Resume.PositionSeconds != 600 {
		t.Fatalf("%+v", episode.Resume)
	}
	if img := detImage(t, episode.Image); !strings.Contains(img.Src, "/70227/Primary?") {
		t.Fatal(img.Src, "own still first")
	}
	if img := detImage(t, rows[2].Image); !strings.Contains(img.Src, "/70185/Thumb?tag=724ef532_6384") {
		t.Fatal(img.Src, "series thumb when the episode has no still")
	}

	text := string(marshal(t, rows))
	if strings.Contains(text, server.URL) || strings.Contains(text, fakeToken) {
		t.Fatal(text)
	}
	revoked.Store(true)
	_, err = fx.service.ContinueWatching(bg)
	wantErr(t, err, ErrSessionExpired)
}

func Test_next_up_lists_episodes_with_the_series_backdrop_as_fallback(t *testing.T) {
	server, fx, revoked := signedInBrowser(t)
	rows, err := fx.service.NextUp(bg)
	if err != nil {
		t.Fatal(err)
	}
	paths := detPaths(server)
	if !slices.ContainsFunc(paths, func(p string) bool { return strings.HasPrefix(p, "/emby/Shows/NextUp?") }) {
		t.Fatal(paths)
	}
	for _, p := range paths {
		if strings.Contains(p, fakeToken) {
			t.Fatal(p)
		}
	}
	i := slices.IndexFunc(paths, func(p string) bool {
		return strings.HasPrefix(p, "/emby/Users/"+fakeUserID+"/Sections/resume/Items?")
	})
	if i < 0 {
		t.Fatal("the resume home section is read")
	}
	if section := paths[i]; !strings.Contains(section, "Limit=16") {
		t.Fatal(section)
	}
	// `/Shows/NextUp` first, then what only the resume section lists: the film in
	// progress and the duplicate are left out.
	var ids []string
	for _, r := range rows {
		ids = append(ids, r.ID)
	}
	if !slices.Equal(ids, []string{"70229", "44443"}) {
		t.Fatal(ids)
	}
	if detStr(rows[1].SeriesName) != "24 Heures chrono" {
		t.Fatal(detStr(rows[1].SeriesName))
	}
	if img := detImage(t, rows[1].Image); !strings.Contains(img.Src, "/44443/Primary?") {
		t.Fatal(img.Src)
	}
	revoked.Store(true)
	_, err = fx.service.NextUp(bg)
	wantErr(t, err, ErrSessionExpired)
}

func Test_search_asks_films_series_and_episodes_apart_and_flags_more_results(t *testing.T) {
	server, fx, revoked := signedInBrowser(t)
	results, err := fx.service.Search(bg, "  Heures\u0007  chrono\n")
	if err != nil {
		t.Fatal(err)
	}
	if results.Query != "Heures chrono" {
		t.Fatal(results.Query)
	}
	var searches []string
	for _, p := range detPaths(server) {
		if strings.Contains(p, "SearchTerm=") {
			searches = append(searches, p)
		}
	}
	if len(searches) != 3 {
		t.Fatal(searches)
	}
	find := func(ty string) string {
		for _, p := range searches {
			if strings.Contains(p, "IncludeItemTypes="+ty+"&") {
				return p
			}
		}
		t.Fatalf("no %s search", ty)
		return ""
	}
	for _, ty := range []string{"Movie", "Series", "Episode"} {
		path := find(ty)
		route, query := splitQuery(path)
		if route != "/emby/Users/"+fakeUserID+"/Items" {
			t.Fatal(route)
		}
		params := strings.Split(query, "&")
		if !slices.Contains(params, "SearchTerm=Heures+chrono") {
			t.Fatal(query)
		}
		limit := "Limit=25"
		if ty == "Episode" {
			limit = "Limit=100"
		}
		if !slices.Contains(params, "Recursive=true") || !slices.Contains(params, limit) {
			t.Fatal(query)
		}
		if strings.Contains(query, fakeToken) || strings.Contains(query, "ParentId") {
			t.Fatal(query)
		}
	}
	if len(results.Movies) != 24 || !results.MoreMovies {
		t.Fatal(len(results.Movies), results.MoreMovies, "a 25th film means more")
	}
	for _, m := range results.Movies {
		if m.ItemType != "Movie" {
			t.Fatal(m.ItemType)
		}
	}
	if len(results.Series) != 1 || results.MoreSeries || detU32(results.Series[0].UnplayedCount) != "204" {
		t.Fatalf("%+v %v", results.Series, results.MoreSeries)
	}

	// Episodes: the 16:9 images of the home cards, series and numbers kept.
	episodeSearch := find("Episode")
	if !strings.Contains(episodeSearch, "EnableImageTypes=Primary%2CBackdrop%2CThumb") || !strings.Contains(episodeSearch, "Limit=100") {
		t.Fatal(episodeSearch)
	}
	if len(results.Episodes) != 1 {
		t.Fatal(len(results.Episodes), "found by series name only, no id or not an episode: dropped")
	}
	if results.MoreEpisodes {
		t.Fatal("pas d'autres épisodes")
	}
	episode := results.Episodes[0]
	if episode.ID != "44501" || detStr(episode.SeriesName) != "24 Heures chrono" {
		t.Fatalf("%+v", episode)
	}
	if detU32(episode.SeasonIndex) != "1" || detU32(episode.Index) != "12" || detU32(episode.RuntimeMinutes) != "43" {
		t.Fatalf("%+v", episode)
	}
	if img := detImage(t, episode.Image); !strings.Contains(img.Src, "/44501/Primary?") {
		t.Fatal(img.Src)
	}

	revoked.Store(true)
	_, err = fx.service.Search(bg, "chrono")
	wantErr(t, err, ErrSessionExpired)
}

func Test_episode_titles_match_at_a_word_start_without_case_or_accents(t *testing.T) {
	for _, c := range []struct {
		title, query string
		want         bool
		why          string
	}{
		{"Heureux Retours", "heureux", true, ""},
		{"Moins d'une heure", "HÉURE", true, ""},
		{"L'arnaque", "arnaq", true, ""},
		{"Rex, chien flic", "chien flic", true, ""},
		{"Él Niño", "el nino", true, ""},
		{"Shizu", "reincarne", false, "series name only"},
		{"Les heures", "eures", false, "not a substring search"},
		{"Nos cœurs", "coeur", false, "no ligature folding, as Emby"},
		{"Titre", "", false, ""},
	} {
		if got := TitleMatches(c.title, c.query); got != c.want {
			t.Errorf("TitleMatches(%q, %q) = %v %s", c.title, c.query, got, c.why)
		}
	}
}

func Test_search_too_short_reaches_no_server(t *testing.T) {
	server, fx, _ := signedInBrowser(t)
	before := len(server.requests())
	for _, query := range []string{"", "   ", "a", " é ", "\u0007b\u0000"} {
		got, err := fx.service.Search(bg, query)
		if err != nil {
			t.Fatal(err)
		}
		if !reflect.DeepEqual(marshal(t, got), marshal(t, SearchResults{})) {
			t.Fatalf("%q: %s", query, marshal(t, got))
		}
	}
	if len(server.requests()) != before {
		t.Fatal(detPaths(server)[before:])
	}
}

func Test_search_query_is_cleaned_and_bounded(t *testing.T) {
	if got := CleanSearch("\tLa  casa\r\nde papel "); got != "La casa de papel" {
		t.Fatalf("%q", got)
	}
	if got := CleanSearch("a\u0000b\u001bc"); got != "abc" {
		t.Fatalf("%q", got)
	}
	long := strings.Repeat("é", 150)
	if got := CleanSearch(long); len([]rune(got)) != 100 {
		t.Fatal(len([]rune(got)))
	}
	if got := CleanSearch(strings.Repeat("a", 99) + " x"); got != strings.Repeat("a", 99) {
		t.Fatalf("%q", got)
	}
}

func Test_next_up_without_home_sections_keeps_shows_next_up(t *testing.T) {
	// A server without home sections answers 404: the row still comes from
	// `/Shows/NextUp`.
	inner := fakeBrowseLike(new(atomic.Bool))
	server := startFakeEmby(t, func(r fakeRequest) (int, string) {
		if strings.Contains(r.Path, "/HomeSections") {
			return 404, fakeNotFoundText
		}
		return inner(r)
	})
	fx := newFixture(t)
	mustSignIn(t, fx, server.URL)
	rows, err := fx.service.NextUp(bg)
	if err != nil {
		t.Fatal(err)
	}
	if len(rows) != 1 || rows[0].ID != "70229" {
		t.Fatalf("%+v", rows)
	}
	for _, p := range detPaths(server) {
		if strings.Contains(p, "/Sections/") {
			t.Fatal(p, "no section read without the list")
		}
	}
}

func Test_next_up_episode_from_the_shows_endpoint(t *testing.T) {
	_, fx, _ := signedInBrowser(t)
	all, err := fx.service.NextUp(bg)
	if err != nil {
		t.Fatal(err)
	}
	var rows []WatchItem
	for _, r := range all {
		if r.ID == "70229" {
			rows = append(rows, r)
		}
	}
	if len(rows) != 1 {
		t.Fatalf("%+v", rows)
	}
	if rows[0].Resume != nil || detU32(rows[0].RuntimeMinutes) != "50" {
		t.Fatalf("%+v", rows[0])
	}
	if img := detImage(t, rows[0].Image); !strings.Contains(img.Src, "/70185/Backdrop?tag=6da1a59e_6384") {
		t.Fatalf("%+v", img)
	}
}

func Test_latest_keeps_the_library_kind_and_refuses_unknown_libraries(t *testing.T) {
	server, fx, _ := signedInBrowser(t)
	items, err := fx.service.Latest(bg, "5")
	if err != nil {
		t.Fatal(err)
	}
	req := server.last(t)
	if path, _ := splitQuery(req.Path); path != "/emby/Users/"+fakeUserID+"/Items/Latest" {
		t.Fatal(path)
	}
	wantPairs(t, req.Path, "ParentId=5", "Limit=16")
	var names []string
	for _, i := range items {
		names = append(names, i.Name)
	}
	if !slices.Equal(names, []string{"Runner"}) {
		t.Fatal(names, "the box set is not a film")
	}
	if items[0].Year == nil || *items[0].Year != 2026 {
		t.Fatal(items[0].Year)
	}

	// Emby answers 500 to an unknown ParentId: such ids never reach it.
	before := len(server.requests())
	_, err = fx.service.Latest(bg, "999")
	wantErr(t, err, ErrNotFound)
	for _, p := range detPaths(server)[before:] {
		if strings.Contains(p, "/Items/Latest") {
			t.Fatal(p, "only the library list is asked again")
		}
	}
}

func Test_state_after_playback_has_watched_status_and_resume_point(t *testing.T) {
	_, fx, _ := signedInBrowser(t)
	film, err := fx.service.PlaybackState(bg, "30207")
	if err != nil {
		t.Fatal(err)
	}
	if film.ItemID != "30207" || film.Played {
		t.Fatalf("%+v", film)
	}
	if film.Resume == nil || film.Resume.PositionSeconds != 1831 {
		t.Fatalf("%+v", film.Resume)
	}
	episode, err := fx.service.PlaybackState(bg, "80475")
	if err != nil {
		t.Fatal(err)
	}
	js := detJSON(t, episode)
	if js["itemId"] != "80475" {
		t.Fatal(js)
	}
	if _, ok := js["played"]; !ok {
		t.Fatal(js)
	}
	if _, ok := js["resume"]; !ok {
		t.Fatal(js)
	}
	_, err = fx.service.PlaybackState(bg, "99999")
	wantErr(t, err, ErrNotFound)
}
