package emby

// Fake Emby server shared by the tests of every wave, port of `FakeEmby`,
// `emby_like` and `browse_like` (src-tauri/src/emby/tests.rs). Answers mirror what
// Emby Server 4.10.1.0 returned during the Gate 2 probe; the fixed ones
// are in testdata/fixtures, extracted from tests.rs by script.
//
// Like the Rust server it records every request (method, raw path with its query as
// sent, X-Emby-Authorization, body) and closes the connection after each answer, so
// the transport never retries a request on a reused connection.

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

const (
	fakeServerID = fixtureServerID
	fakeUserID   = fixtureUserID
	fakeToken    = "0123456789abcdef0123456789abcdef"
	fakePassword = "s3cret-pw"
	// fakeNotFoundText is the 404 body of Emby 4.10.1.0 for an unknown item: French
	// text, not JSON.
	fakeNotFoundText = "L'objet demandé ne peut pas être trouvé. Il a peut-être été récemment supprimé du serveur."
)

// fakeRequest is `Recorded`. Path is the request target as sent (`/emby/…?…`).
type fakeRequest struct {
	Method string
	Path   string
	// Auth is the X-Emby-Authorization header; HasAuth is false without one.
	Auth    string
	HasAuth bool
	Body    string
	// Header is every header received.
	Header http.Header
}

// fakeRoute answers one request. Status 0 drops the connection without an answer. A
// body starting with `JPEG:` is served as an image; `JFIF:` adds the JPEG signature
// the artwork cache checks. Anything else is served as JSON.
type fakeRoute func(r fakeRequest) (int, string)

type fakeEmby struct {
	URL string
	mu  sync.Mutex
	log []fakeRequest
}

// startFakeEmby is `FakeEmby::start`; the server stops with the test.
func startFakeEmby(t *testing.T, route fakeRoute) *fakeEmby {
	t.Helper()
	f := &fakeEmby{}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		auth, hasAuth := r.Header["X-Emby-Authorization"]
		req := fakeRequest{Method: r.Method, Path: r.RequestURI, HasAuth: hasAuth, Body: string(body), Header: r.Header.Clone()}
		if hasAuth {
			req.Auth = auth[0]
		}
		f.mu.Lock()
		status, answer := route(req)
		f.log = append(f.log, req)
		f.mu.Unlock()
		if status == 0 {
			if hj, ok := w.(http.Hijacker); ok {
				if conn, _, err := hj.Hijack(); err == nil {
					_ = conn.Close()
				}
			}
			return
		}
		contentType, payload := "application/json", []byte(answer)
		if b, ok := strings.CutPrefix(answer, "JFIF:"); ok {
			contentType, payload = "image/jpeg", append([]byte{0xFF, 0xD8, 0xFF}, b...)
		} else if b, ok := strings.CutPrefix(answer, "JPEG:"); ok {
			contentType, payload = "image/jpeg", []byte(b)
		}
		w.Header().Set("Content-Type", contentType)
		w.Header().Set("Connection", "close")
		if status == http.StatusNoContent || status == http.StatusNotModified {
			w.WriteHeader(status)
			return
		}
		w.Header().Set("Content-Length", strconv.Itoa(len(payload)))
		w.WriteHeader(status)
		_, _ = w.Write(payload)
	}))
	t.Cleanup(srv.Close)
	f.URL = srv.URL
	return f
}

// requests is a copy of the requests received so far.
func (f *fakeEmby) requests() []fakeRequest {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]fakeRequest(nil), f.log...)
}

// last is the last request received.
func (f *fakeEmby) last(t *testing.T) fakeRequest {
	t.Helper()
	reqs := f.requests()
	if len(reqs) == 0 {
		t.Fatal("aucune requête reçue")
	}
	return reqs[len(reqs)-1]
}

// splitQuery is `path.split_once('?')`: the path and the query (empty without one).
func splitQuery(target string) (string, string) {
	path, query, _ := strings.Cut(target, "?")
	return path, query
}

// queryPairs is `query.split('&')`.
func queryPairs(target string) []string {
	_, query := splitQuery(target)
	return strings.Split(query, "&")
}

func hasPair(pairs []string, want string) bool {
	for _, p := range pairs {
		if p == want {
			return true
		}
	}
	return false
}

// fakeFixture is one fixed answer of tests.rs (testdata/fixtures/<name>.json). The
// playback_*.json fixtures of wave 2B write the token `TOKEN_PLACEHOLDER`: the fake
// server answers with fakeToken, as the Rust one does.
func fakeFixture(name string) string {
	b, err := os.ReadFile(filepath.Join(fixturesDir, name+".json"))
	if err != nil {
		panic(err)
	}
	return strings.ReplaceAll(string(b), "TOKEN_PLACEHOLDER", fakeToken)
}

func (r fakeRequest) hasToken() bool {
	return r.HasAuth && strings.Contains(r.Auth, `Token="`+fakeToken+`"`)
}

// fakeEmbyLike is `emby_like`: correct password, token checks, logout.
func fakeEmbyLike(logoutStatus int) fakeRoute {
	return func(r fakeRequest) (int, string) {
		switch {
		case r.Method == "GET" && r.Path == "/emby/System/Info/Public":
			return 200, fakeFixture("public_info")
		case r.Method == "POST" && r.Path == "/emby/Users/AuthenticateByName":
			if strings.Contains(r.Body, `"Pw":"`+fakePassword+`"`) {
				return 200, fakeFixture("auth_result")
			}
			return 401, `"Nom d'utilisateur ou mot de passe incorrect."`
		case r.Method == "GET" && r.Path == "/emby/Users/"+fakeUserID:
			if r.hasToken() {
				return 200, fakeFixture("user")
			}
			return 401, ""
		case r.Method == "POST" && r.Path == "/emby/Sessions/Logout":
			return logoutStatus, ""
		default:
			return 404, ""
		}
	}
}

// fakeRandomAnswer is `random_answer`: one title when the genre is "Drame", none otherwise.
func fakeRandomAnswer(query string) string {
	if strings.Contains(query, "Genres=Drame") {
		return `{"Items":[{"Name":"2 Coeurs","Id":"101","Type":"Movie","ProductionYear":2020}],"TotalRecordCount":1}`
	}
	return `{"Items":[],"TotalRecordCount":0}`
}

// fakeSearchAnswer is `search_answer`.
func fakeSearchAnswer(query string) string {
	if strings.Contains(query, "IncludeItemTypes=Episode") {
		return `{"Items":[
            {"Name":"Les heures chrono","Id":"44501","Type":"Episode","SeriesName":"24 Heures chrono",
             "SeriesId":"44440","ParentIndexNumber":1,"IndexNumber":12,"RunTimeTicks":25800000000,
             "ImageTags":{"Primary":"ep_12"},"UserData":{"Played":false,"PlaybackPositionTicks":0}},
            {"Name":"Jour 2 : 8h00 - 9h00","Id":"44502","Type":"Episode","SeriesName":"24 Heures chrono",
             "ParentIndexNumber":2,"IndexNumber":1},
            {"Name":"Heures sombres","Id":"","Type":"Episode"},
            {"Name":"Intrus","Id":"8998","Type":"Movie"}
        ],"TotalRecordCount":0}`
	}
	if strings.Contains(query, "IncludeItemTypes=Movie") {
		items := make([]string, 0, 26)
		for i := range 25 {
			items = append(items, fmt.Sprintf(`{"Name":"Film %d","Id":"%d","Type":"Movie","ProductionYear":2001,
                    "ImageTags":{"Primary":"aa_%d"},"UserData":{"Played":false}}`, i, 9000+i, i))
		}
		items = append(items[:3], append([]string{`{"Name":"Intrus","Id":"8999","Type":"Series"}`}, items[3:]...)...)
		return `{"Items":[` + strings.Join(items, ",") + `],"TotalRecordCount":0}`
	}
	return `{"Items":[
            {"Name":"24 Heures chrono","Id":"44440","Type":"Series","ProductionYear":2001,
             "ImageTags":{"Primary":"0e0af3ab_6381"},"UserData":{"UnplayedItemCount":204,"Played":false}}
        ],"TotalRecordCount":0}`
}

// fakePlaybackTwoVersions is `playback_two_versions`: as Emby 4.10.2.0 treats
// `MediaSourceId`, that source only, `NoCompatibleStream` for an id it does not know,
// every source without it.
func fakePlaybackTwoVersions(body string) string {
	first := `{"Id":"mediasource_30500","Container":"mkv","SupportsDirectPlay":true,
        "DirectStreamUrl":"/videos/30500/original.mkv?MediaSourceId=mediasource_30500&PlaySessionId=ps5&api_key=` + fakeToken + `"}`
	second := `{"Id":"30501","Container":"mkv","SupportsDirectPlay":true,
        "DirectStreamUrl":"/videos/30500/original.mkv?MediaSourceId=30501&PlaySessionId=ps5&api_key=` + fakeToken + `"}`
	var req map[string]any
	if err := json.Unmarshal([]byte(body), &req); err != nil {
		panic(err)
	}
	var sources string
	switch id, ok := req["MediaSourceId"].(string); {
	case !ok:
		sources = first + "," + second
	case id == "mediasource_30500":
		sources = first
	case id == "30501":
		sources = second
	default:
		return `{"MediaSources":[],"ErrorCode":"NoCompatibleStream"}`
	}
	return `{"MediaSources":[` + sources + `],"PlaySessionId":"ps5"}`
}

// fakePlaybackTranscodeAudio is `playback_transcode_audio`: a transcode with French
// and English audio and subtitle files.
func fakePlaybackTranscodeAudio(audio int) string {
	a := strconv.Itoa(audio)
	return `{"MediaSources":[{"Protocol":"File","Id":"mediasource_40001","Container":"mkv",
        "SupportsTranscoding":true,"SupportsDirectStream":false,"SupportsDirectPlay":false,
        "RequiredHttpHeaders":{},
        "TranscodingUrl":"/videos/40001/master.m3u8?MediaSourceId=mediasource_40001&PlaySessionId=ps` + a + `&AudioStreamIndex=` + a + `&api_key=` + fakeToken + `",
        "TranscodingSubProtocol":"hls","TranscodingContainer":"ts",
        "DefaultAudioStreamIndex":` + a + `,"DefaultSubtitleStreamIndex":3,
        "MediaStreams":[{"Type":"Video","Index":0},
        {"Type":"Audio","Index":1,"Language":"fre","IsDefault":true},
        {"Type":"Audio","Index":2,"Language":"eng"},
        {"Type":"Subtitle","Index":3,"Codec":"subrip","Language":"fre","IsForced":true,"DeliveryMethod":"External",
        "DeliveryUrl":"/Videos/40001/mediasource_40001/Subtitles/3/0/Stream.subrip?api_key=` + fakeToken + `"},
        {"Type":"Subtitle","Index":4,"Codec":"subrip","Language":"eng","DeliveryMethod":"External",
        "DeliveryUrl":"/Videos/40001/mediasource_40001/Subtitles/4/0/Stream.subrip?api_key=` + fakeToken + `"}]}],
        "PlaySessionId":"ps` + a + `"}`
}

// fakeBrowseLike is `browse_like`: emby_like plus library browsing. revoked makes
// every token call answer 401. The order of the cases is the order of the Rust match.
func fakeBrowseLike(revoked *atomic.Bool) fakeRoute {
	base := fakeEmbyLike(204)
	return func(r fakeRequest) (int, string) {
		tokenOK := !revoked.Load() && r.hasToken()
		path, query := splitQuery(r.Path)
		items := "/emby/Users/" + fakeUserID + "/Items"
		views := "/emby/Users/" + fakeUserID + "/Views"
		sections := "/emby/Users/" + fakeUserID + "/HomeSections"
		sectionItems := "/emby/Users/" + fakeUserID + "/Sections/resume/Items"
		favoriteItems := "/emby/Users/" + fakeUserID + "/FavoriteItems/"
		playedItems := "/emby/Users/" + fakeUserID + "/PlayedItems/"
		has := func(s string) bool { return strings.Contains(query, s) }
		pair := func(s string) bool { return hasPair(strings.Split(query, "&"), s) }
		get, post, del := r.Method == "GET", r.Method == "POST", r.Method == "DELETE"
		emptyPage := `{"Items":[],"TotalRecordCount":0}`

		guarded := path == views || path == sections || path == sectionItems ||
			strings.HasPrefix(path, items) || strings.HasPrefix(path, "/emby/Items/") ||
			strings.HasPrefix(path, "/emby/Shows/") || strings.HasPrefix(path, "/emby/Sessions/") ||
			path == "/emby/Genres" || strings.HasPrefix(path, favoriteItems) ||
			strings.HasPrefix(path, playedItems) || strings.HasPrefix(path, "/emby/Playlists")
		switch {
		case guarded && !tokenOK:
			return 401, ""
		case path == views:
			return 200, fakeFixture("views")
		case path == items && has("IncludeItemTypes=Playlist") && has("Fields=ChildCount"):
			return 200, fakeFixture("home_playlists")
		case path == items && has("IncludeItemTypes=Playlist"):
			return 200, fakeFixture("playlists")
		case path == "/emby/Playlists/60001/Items" && get:
			return 200, fakeFixture("playlist_items")
		case path == "/emby/Playlists/60001/Items" && post:
			added := 0
			if has("Ids=30207") {
				added = 1
			}
			return 200, fmt.Sprintf(`{"Id":"60001","ItemAddedCount":%d}`, added)
		case path == "/emby/Playlists" && post && has("MediaType=Video"):
			return 200, `{"Id":"60010","Name":"Soirée ciné","ItemAddedCount":1}`
		case strings.HasPrefix(path, "/emby/Playlists"):
			return 404, fakeNotFoundText
		case path == "/emby/Genres" && has("ParentId=5"):
			return 200, fakeFixture("genres")
		case path == items && has("SearchTerm="):
			return 200, fakeSearchAnswer(query)
		case path == items && has("ParentId=5") && pair("Fields=SortName"):
			return 200, fakeFixture("sort_names_answer")
		case path == items && has("ParentId=5") && has("SortBy=Random"):
			return 200, fakeRandomAnswer(query)
		case path == items && has("ParentId=5") && pair("Fields=ProductionYear"):
			return 200, fakeFixture("years_answer")
		case path == items && has("ParentId=5"):
			return 200, fakeFixture("movies_page")
		case path == items && has("Filters=IsFavorite"):
			return 200, fakeFixture("favorites")
		case path == items && has("ImageTypes=Backdrop"):
			return 200, fakeFixture("hero_latest")
		case path == favoriteItems+"30207" && post:
			return 200, `{"IsFavorite":true,"Played":false,"PlaybackPositionTicks":0}`
		case path == favoriteItems+"30207" && del:
			return 200, `{"IsFavorite":false,"Played":false,"PlaybackPositionTicks":0}`
		case strings.HasPrefix(path, favoriteItems):
			return 404, fakeNotFoundText
		// 30299 exists for these calls only: reading it back fails.
		case (path == playedItems+"30207" || path == playedItems+"30299") && post && strings.HasPrefix(query, "DatePlayed="):
			return 200, `{"Played":true,"PlayCount":1,"PlaybackPositionTicks":0,"IsFavorite":false}`
		case path == playedItems+"30207" && del:
			return 200, `{"Played":false,"PlayCount":0,"PlaybackPositionTicks":0,"IsFavorite":false}`
		case strings.HasPrefix(path, playedItems):
			return 404, fakeNotFoundText
		case (path == items+"/30207/HideFromResume" || path == items+"/30299/HideFromResume") && post && query == "Hide=true":
			return 200, `{"Played":false,"PlaybackPositionTicks":18310167000,"IsFavorite":false}`
		case path == items && has("PersonIds=30209"):
			return 200, fakeFixture("person_titles")
		case path == items && has("ParentId=120034"):
			return 200, fakeFixture("person_titles")
		case path == "/emby/Items/30207/Similar":
			return 200, fakeFixture("person_titles")
		case path == items+"/30207/LocalTrailers":
			return 200, `[{"Name":"Bande-annonce","Id":"30500","Type":"Trailer"}]`
		case path == items:
			return 200, emptyPage
		case path == "/emby/Items/101/Images/Primary":
			return 200, "JPEG:fake-jpeg-bytes"
		case path == "/emby/Items/102/Images/Primary":
			return 200, "<html>pas une image</html>"
		case path == "/emby/Items/103/Images/Primary":
			return 200, "JFIF:cached-artwork"
		case path == items+"/Resume":
			return 200, fakeFixture("resume_items")
		case path == items+"/Latest" && has("ParentId=5"):
			return 200, fakeFixture("latest_movies")
		case path == "/emby/Shows/NextUp" && has("UserId="+fakeUserID):
			return 200, fakeFixture("next_up")
		case path == sections:
			return 200, fakeFixture("home_sections")
		case path == sectionItems:
			return 200, fakeFixture("resume_section")
		case path == items+"/30207":
			return 200, fakeFixture("movie_detail")
		case path == items+"/30500":
			return 200, fakeFixture("two_versions_detail")
		case path == items+"/80473":
			return 200, fakeFixture("series_detail")
		case path == items+"/80475":
			return 200, fakeFixture("episode_detail")
		case path == items+"/80476":
			return 200, fakeFixture("bare_episode_detail")
		case path == items+"/80474":
			return 200, fakeFixture("season_detail")
		case strings.HasPrefix(path, items+"/"):
			return 404, fakeNotFoundText
		case path == "/emby/Shows/80473/Seasons":
			return 200, fakeFixture("seasons")
		case path == "/emby/Shows/80473/Episodes" && has("IsPlayed=false"):
			return 200, fakeFixture("first_unplayed")
		case path == "/emby/Shows/80500/Episodes" && has("IsPlayed=false"):
			return 200, emptyPage
		case path == "/emby/Shows/80473/Episodes" && has("SeasonId=80474"):
			return 200, fakeFixture("episodes_page")
		// Emby trusts SeasonId: a season of another series returns its episodes.
		case path == "/emby/Shows/80473/Episodes" && has("SeasonId=67639"):
			return 200, fakeFixture("foreign_episodes")
		case path == "/emby/Shows/80473/Episodes" && has("AdjacentTo=80475"):
			return 200, fakeFixture("adjacent_episodes")
		case path == "/emby/Shows/80473/Episodes" && has("AdjacentTo="):
			return 200, emptyPage
		case strings.HasPrefix(path, "/emby/Shows/"):
			return 404, fakeNotFoundText
		// 30207 honours a refusal of direct play; 80475 ignores it.
		case path == "/emby/Items/30207/PlaybackInfo" && post && strings.Contains(r.Body, `"EnableDirectPlay":false`):
			return 200, fakeFixture("playback_transcode")
		case (path == "/emby/Items/30207/PlaybackInfo" || path == "/emby/Items/80475/PlaybackInfo") && post:
			return 200, fakeFixture("playback_direct")
		case path == "/emby/Items/40000/PlaybackInfo" && post:
			return 200, fakeFixture("playback_transcode")
		case path == "/emby/Items/30500/PlaybackInfo" && post:
			return 200, fakePlaybackTwoVersions(r.Body)
		// A transcode takes another audio track only with the source named.
		case path == "/emby/Items/40001/PlaybackInfo" && post:
			other := strings.Contains(r.Body, `"MediaSourceId":"mediasource_40001"`) && strings.Contains(r.Body, `"AudioStreamIndex":2`)
			if other {
				return 200, fakePlaybackTranscodeAudio(2)
			}
			return 200, fakePlaybackTranscodeAudio(1)
		case strings.HasSuffix(path, "/PlaybackInfo"):
			return 404, fakeNotFoundText
		case (path == "/emby/Sessions/Playing" || path == "/emby/Sessions/Playing/Progress" || path == "/emby/Sessions/Playing/Stopped") && post:
			return 204, ""
		default:
			return base(r)
		}
	}
}

// fakeDevice is the device of the Rust fixture (`EmbyService::new(…, "PC \"test\",
// bureau", "0.1.0")`), with a fixed id.
var fakeDevice = DeviceIdentity{Name: `PC "test", bureau`, ID: "test-device-id", Version: "0.1.0"}

// fakeClient is a client without token for server.
func fakeClient(t *testing.T, url string) *Client {
	t.Helper()
	server, err := ParseServerURL(url)
	if err != nil {
		t.Fatal(err)
	}
	c, err := NewClient(server, fakeDevice)
	if err != nil {
		t.Fatal(err)
	}
	return c
}

// signedInClient is the client half of `signed_in_browser`: a browse_like server and
// a client holding the token AuthenticateByName returned. The service half (session
// file, credential store) comes with session.rs (wave 4).
func signedInClient(t *testing.T) (*fakeEmby, *Client, *atomic.Bool) {
	t.Helper()
	revoked := new(atomic.Bool)
	server := startFakeEmby(t, fakeBrowseLike(revoked))
	c := fakeClient(t, server.URL)
	auth, err := c.Authenticate(context.Background(), "testeur", fakePassword)
	if err != nil {
		t.Fatal(err)
	}
	return server, c.WithToken(NewAccessToken(auth.AccessToken)), revoked
}

// closedPortURL is a local address with nothing listening (bind, then close).
func closedPortURL(t *testing.T) string {
	t.Helper()
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	addr := l.Addr().String()
	_ = l.Close()
	return "http://" + addr
}

// hungServer accepts connections and never answers, until the test ends.
func hungServer(t *testing.T) string {
	t.Helper()
	done := make(chan struct{})
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		select {
		case <-done:
		case <-time.After(30 * time.Second):
		}
	}))
	t.Cleanup(func() { close(done); srv.Close() })
	return srv.URL
}
