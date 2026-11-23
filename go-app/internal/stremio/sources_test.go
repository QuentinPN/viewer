package stremio

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"slices"
	"strings"
	"testing"

	"viewer/internal/emby"
	segs "viewer/internal/segments"
)

func ptr[T any](v T) *T { return &v }

func movieRef() emby.ExternalRef {
	return emby.ExternalRef{ItemType: "Movie", IDs: segs.MediaIDs{Imdb: ptr("tt0111161")}, Title: &emby.PlaybackTitle{Name: "Les Évadés"}}
}

func episodeRef() emby.ExternalRef {
	return emby.ExternalRef{
		ItemType: "Episode", IDs: segs.MediaIDs{Imdb: ptr("tt0944947")},
		Episode: &segs.EpisodeNumber{Season: 2, Episode: 5},
		Title:   &emby.PlaybackTitle{Name: "Pilote", SeriesName: ptr("La Série"), SeasonIndex: ptr(uint32(2)), Index: ptr(uint32(5))},
	}
}

// resolverOf answers refs by Emby item id and writes down each id asked.
type resolverOf struct {
	refs  map[string]emby.ExternalRef
	err   error
	asked []string
}

func (r *resolverOf) resolve(_ context.Context, itemID string) (emby.ExternalRef, error) {
	r.asked = append(r.asked, itemID)
	if r.err != nil {
		return emby.ExternalRef{}, r.err
	}
	ref, ok := r.refs[itemID]
	if !ok {
		return emby.ExternalRef{}, emby.ErrNotFound
	}
	return ref, nil
}

func newResolver() *resolverOf {
	return &resolverOf{refs: map[string]emby.ExternalRef{"30207": movieRef(), "80475": episodeRef()}}
}

func signedIn(t *testing.T) (*Service, *fakeServer) {
	t.Helper()
	svc, f, _, _ := newTestService(t)
	if err := svc.SignIn(context.Background(), "owner@example.com", "hunter2"); err != nil {
		t.Fatal(err)
	}
	return svc, f
}

func TestTargetOfAnEmbyItem(t *testing.T) {
	cases := []struct {
		name string
		ref  emby.ExternalRef
		typ  ContentType
		id   string
		err  error
	}{
		{"film", movieRef(), Movie, "tt0111161", nil},
		{"épisode", episodeRef(), Series, "tt0944947:2:5", nil},
		{"film sans IMDb", emby.ExternalRef{ItemType: "Movie", IDs: segs.MediaIDs{Tmdb: ptr(uint64(278))}}, "", "", ErrNoImdb},
		{"épisode sans IMDb", emby.ExternalRef{ItemType: "Episode", Episode: &segs.EpisodeNumber{Season: 1, Episode: 1}}, "", "", ErrNoImdb},
		{"épisode sans numéros", emby.ExternalRef{ItemType: "Episode"}, "", "", ErrNoImdb},
		{"épisode spécial", emby.ExternalRef{ItemType: "Episode", IDs: segs.MediaIDs{Imdb: ptr("tt0944947")}, Episode: &segs.EpisodeNumber{Season: 0, Episode: 3}}, Series, "tt0944947:0:3", nil},
		{"épisode numéro 0", emby.ExternalRef{ItemType: "Episode", IDs: segs.MediaIDs{Imdb: ptr("tt0944947")}, Episode: &segs.EpisodeNumber{Season: 0, Episode: 0}}, "", "", ErrInvalidID},
		{"série", emby.ExternalRef{ItemType: "Series", IDs: segs.MediaIDs{Imdb: ptr("tt0944947")}}, "", "", ErrInvalidID},
	}
	for _, c := range cases {
		typ, id, err := Target(c.ref)
		if typ != c.typ || id != c.id || (c.err == nil) != (err == nil) || (c.err != nil && !errors.Is(err, c.err)) {
			t.Errorf("%s : %q %q %v", c.name, typ, id, err)
		}
	}
}

func TestSourcesListsTheAddonsServingTheItem(t *testing.T) {
	svc, f := signedIn(t)
	res := newResolver()
	h := Handlers(svc, res.resolve)

	out, errText := call(t, h, "stremio_sources", `{"itemId":"30207"}`)
	if errText != "" {
		t.Fatal(errText)
	}
	assertNoSecret(t, "sources", out, f.URL)
	var movie struct {
		Query  string `json:"query"`
		Addons []struct {
			ID   string `json:"id"`
			Name string `json:"name"`
		} `json:"addons"`
	}
	if err := json.Unmarshal([]byte(out), &movie); err != nil {
		t.Fatal(err)
	}
	var names, ids []string
	for _, a := range movie.Addons {
		names = append(names, a.Name)
		ids = append(ids, a.ID)
		if !strings.HasPrefix(a.ID, "addon_") || len(a.ID) < len("addon_")+16 {
			t.Errorf("id %q", a.ID)
		}
	}
	// Collection order; TMDB Collections serves movies only, Cinemeta no stream.
	want := []string{"TMDB Collections", "Local Files (without catalog support)", "Lumio · Profil", "AIOSources",
		"StreamFusionReborn", "Wastream | AD", "Comet | AD", "TorrentsDB AD"}
	if !slices.Equal(names, want) {
		t.Fatal(names)
	}
	if !strings.HasPrefix(movie.Query, "query_") || len(slices.Compact(slices.Sorted(slices.Values(ids)))) != len(ids) {
		t.Fatal(out)
	}
	// An Emby item is keyed by its own id.
	if !strings.HasPrefix(out, `{"query":"`+movie.Query+`","key":"30207","addons":[`) {
		t.Fatal(out)
	}

	out, errText = call(t, h, "stremio_sources", `{"itemId":"80475"}`)
	if errText != "" || strings.Contains(out, "TMDB Collections") || !strings.Contains(out, `"name":"Lumio · Profil"`) {
		t.Fatal(out, errText)
	}
	if !slices.Equal(res.asked, []string{"30207", "80475"}) {
		t.Fatal(res.asked)
	}
}

func TestSourcesErrors(t *testing.T) {
	svc, f, _, _ := newTestService(t)
	res := newResolver()
	h := Handlers(svc, res.resolve)
	if _, err := call(t, h, "stremio_sources", `{"itemId":"30207"}`); err != `{"kind":"signedOut","message":"Connecte ton compte Stremio dans les Réglages."}` {
		t.Fatal(err)
	}
	if len(res.asked) != 0 {
		t.Fatal("Emby lu sans compte Stremio")
	}
	if err := svc.SignIn(context.Background(), "owner@example.com", "hunter2"); err != nil {
		t.Fatal(err)
	}
	res.refs["1"] = emby.ExternalRef{ItemType: "Movie", Title: &emby.PlaybackTitle{Name: "x"}}
	if _, err := call(t, h, "stremio_sources", `{"itemId":"1"}`); err != `{"kind":"noImdb","message":"Ce titre n'a pas d'identifiant IMDb : les addons ne peuvent pas le chercher."}` {
		t.Fatal(err)
	}
	// Emby's own failure, as Emby gives it.
	if _, err := call(t, h, "stremio_sources", `{"itemId":"404"}`); !strings.Contains(err, `"kind":"notFound"`) {
		t.Fatal(err)
	}
	f.collection = string(fixture(t, "addon_collection_session_expired"))
	if err := svc.SignIn(context.Background(), "owner@example.com", "hunter2"); err != nil {
		t.Fatal(err)
	}
	if _, err := call(t, h, "stremio_sources", `{"itemId":"30207"}`); err != `{"kind":"reauthRequired","message":"Reconnecte Stremio dans les Réglages."}` {
		t.Fatal(err)
	}
}

// sourcesOf opens a query on item and returns its id and its addon ids by name.
func sourcesOf(t *testing.T, svc *Service, res *resolverOf, item string) (string, map[string]string) {
	t.Helper()
	s, err := svc.Sources(context.Background(), item, res.resolve)
	if err != nil {
		t.Fatal(err)
	}
	ids := map[string]string{}
	for _, a := range s.Addons {
		ids[a.Name] = a.ID
	}
	return s.Query, ids
}

func TestAddonStreamsDescribeWithoutURL(t *testing.T) {
	svc, f := signedIn(t)
	res := newResolver()
	h := Handlers(svc, res.resolve)
	q, ids := sourcesOf(t, svc, res, "30207")

	out, errText := call(t, h, "stremio_addon_streams", fmt.Sprintf(`{"query":%q,"addonId":%q}`, q, ids["Lumio · Profil"]))
	if errText != "" {
		t.Fatal(errText)
	}
	assertNoSecret(t, "flux", out, f.URL)
	for _, k := range []string{`"url"`, `"headers"`, "proxyHeaders", "behaviorHints", "filename"} {
		if strings.Contains(out, k) {
			t.Errorf("descripteur avec %s : %s", k, out)
		}
	}
	var got struct {
		Streams []struct {
			ID          string  `json:"id"`
			Name        string  `json:"name"`
			Description string  `json:"description"`
			Size        *int64  `json:"size"`
			Extra       *string `json:"extra"`
		} `json:"streams"`
		Unsupported int `json:"unsupported"`
	}
	if err := json.Unmarshal([]byte(out), &got); err != nil {
		t.Fatal(err)
	}
	if len(got.Streams) == 0 {
		t.Fatal(out)
	}
	first := got.Streams[0]
	if !strings.HasPrefix(first.ID, "stream_") || first.Name != "[AD⚡️] Lumio" ||
		!strings.HasPrefix(first.Description, "1080p • BluRay REMUX • REPACK • 📶 30 Mb/s\n") ||
		first.Size == nil || *first.Size != 31682067376 {
		t.Fatalf("%+v", first)
	}
	var keys []map[string]json.RawMessage
	var raw struct {
		Streams *[]map[string]json.RawMessage `json:"streams"`
	}
	raw.Streams = &keys
	if err := json.Unmarshal([]byte(out), &raw); err != nil {
		t.Fatal(err)
	}
	for _, k := range keys {
		if len(k) != 4 || k["id"] == nil || k["name"] == nil || k["description"] == nil || k["size"] == nil {
			t.Fatalf("clés %v", k)
		}
	}

	// TorrentsDB gives `title`; a size it does not give is null.
	f.streams[Movie]["addon-9"] = addonReply{Status: 200, Response: json.RawMessage(`{"streams":[
		{"name":"T\n1080p","title":"ligne 1\nligne 2","url":"https://debrid.example/REDACTED/a.mkv"},
		{"name":"torrent","infoHash":"0123456789abcdef0123456789abcdef01234567"}]}`)}
	out, errText = call(t, h, "stremio_addon_streams", fmt.Sprintf(`{"query":%q,"addonId":%q}`, q, ids["TorrentsDB AD"]))
	if errText != "" || !strings.Contains(out, `"name":"T\n1080p","description":"ligne 1\nligne 2","size":null}],"unsupported":1}`) {
		t.Fatal(out, errText)
	}
}

func TestAddonStreamsErrors(t *testing.T) {
	svc, f := signedIn(t)
	res := newResolver()
	h := Handlers(svc, res.resolve)
	q, ids := sourcesOf(t, svc, res, "30207")
	args := func(query, addon string) string { return fmt.Sprintf(`{"query":%q,"addonId":%q}`, query, addon) }

	// Local Files closes the connection.
	if _, err := call(t, h, "stremio_addon_streams", args(q, ids["Local Files (without catalog support)"])); err != `{"kind":"unreachable","message":"Local Files (without catalog support) : indisponible."}` {
		t.Fatal(err)
	}
	f.streams[Movie]["addon-1"] = addonReply{Status: 500, Response: json.RawMessage(`{}`)}
	if _, err := call(t, h, "stremio_addon_streams", args(q, ids["TMDB Collections"])); err != `{"kind":"server","message":"TMDB Collections : indisponible."}` {
		t.Fatal(err)
	}
	const expired = `{"kind":"expired","message":"La liste a expiré, rouvre-la."}`
	for _, a := range []string{args("query_inconnue", ids["Comet | AD"]), args(q, "addon_inconnu"), args(q, "")} {
		if _, err := call(t, h, "stremio_addon_streams", a); err != expired {
			t.Errorf("%s : %s", a, err)
		}
	}
	if _, err := svc.Selected("stream_inconnu"); !errors.Is(err, ErrExpired) {
		t.Fatal(err)
	}
}

// The table keeps the last 4 queries: a 5th drops the oldest, its streams with it.
func TestQueryTableKeepsTheLastFour(t *testing.T) {
	svc, _ := signedIn(t)
	res := newResolver()
	ctx := context.Background()
	q1, ids1 := sourcesOf(t, svc, res, "30207")
	s1, err := svc.AddonStreams(ctx, q1, ids1["Comet | AD"])
	if err != nil {
		t.Fatal(err)
	}
	var queries []string
	for range 3 {
		q, _ := sourcesOf(t, svc, res, "80475")
		queries = append(queries, q)
	}
	if _, err := svc.Selected(s1.Streams[0].ID); err != nil {
		t.Fatal("4 requêtes : la première est gardée", err)
	}
	q5, ids5 := sourcesOf(t, svc, res, "80475")
	if _, err := svc.AddonStreams(ctx, q1, ids1["Comet | AD"]); !errors.Is(err, ErrExpired) {
		t.Fatal(err)
	}
	if _, err := svc.Selected(s1.Streams[0].ID); !errors.Is(err, ErrExpired) {
		t.Fatal(err)
	}
	// Addon ids belong to their query.
	if _, err := svc.AddonStreams(ctx, queries[0], ids5["Comet | AD"]); !errors.Is(err, ErrExpired) {
		t.Fatal(err)
	}
	if _, err := svc.AddonStreams(ctx, q5, ids5["Comet | AD"]); err != nil {
		t.Fatal(err)
	}
}

// A new fetch of the same addon replaces its streams; sign-out and sign-in forget
// every query.
func TestQueriesAreForgotten(t *testing.T) {
	svc, _ := signedIn(t)
	res := newResolver()
	ctx := context.Background()
	q, ids := sourcesOf(t, svc, res, "30207")
	first, err := svc.AddonStreams(ctx, q, ids["Comet | AD"])
	if err != nil {
		t.Fatal(err)
	}
	again, err := svc.AddonStreams(ctx, q, ids["Comet | AD"])
	if err != nil || len(again.Streams) != len(first.Streams) || again.Streams[0].ID == first.Streams[0].ID {
		t.Fatal(err)
	}
	if _, err := svc.Selected(first.Streams[0].ID); !errors.Is(err, ErrExpired) {
		t.Fatal("ancien id gardé", err)
	}
	if _, err := svc.Selected(again.Streams[0].ID); err != nil {
		t.Fatal(err)
	}

	if err := svc.SignOut(); err != nil {
		t.Fatal(err)
	}
	if _, err := svc.Selected(again.Streams[0].ID); !errors.Is(err, ErrExpired) {
		t.Fatal(err)
	}
	if err := svc.SignIn(ctx, "owner@example.com", "hunter2"); err != nil {
		t.Fatal(err)
	}
	if _, err := svc.AddonStreams(ctx, q, ids["Comet | AD"]); !errors.Is(err, ErrExpired) {
		t.Fatal(err)
	}

	q, ids = sourcesOf(t, svc, res, "30207")
	s, err := svc.AddonStreams(ctx, q, ids["Comet | AD"])
	if err != nil {
		t.Fatal(err)
	}
	if err := svc.SignIn(ctx, "owner@example.com", "hunter2"); err != nil {
		t.Fatal(err)
	}
	if _, err := svc.Selected(s.Streams[0].ID); !errors.Is(err, ErrExpired) {
		t.Fatal("une nouvelle connexion garde les flux", err)
	}
}

// The selection gives the player the stream's own URL and headers, the Emby item
// and its title; printed or serialized, it shows none of them.
func TestSelectedCarriesTheStream(t *testing.T) {
	svc, f := signedIn(t)
	res := newResolver()
	f.streams[Series]["addon-8"] = addonReply{Status: 200, Response: json.RawMessage(`{"streams":[
		{"name":"a","url":"https://debrid.example/REDACTED/one.mkv"},
		{"name":"b","url":"https://debrid.example/REDACTED/two.mkv","behaviorHints":{"proxyHeaders":{"request":{"User-Agent":"UA-2","Referer":"https://debrid.example/REDACTED/"}}}}]}`)}
	q, ids := sourcesOf(t, svc, res, "80475")
	s, err := svc.AddonStreams(context.Background(), q, ids["Comet | AD"])
	if err != nil || len(s.Streams) != 2 {
		t.Fatal(err)
	}
	sel, err := svc.Selected(s.Streams[1].ID)
	if err != nil {
		t.Fatal(err)
	}
	h := sel.Stream.Headers()
	if sel.ItemID != "80475" || sel.Stream.URL() != "https://debrid.example/REDACTED/two.mkv" ||
		len(h) != 2 || h["User-Agent"] != "UA-2" || sel.Title == nil || sel.Title.Name != "Pilote" {
		t.Fatalf("%+v", sel)
	}
	for _, verb := range []string{"%v", "%+v", "%#v", "%s"} {
		assertNoSecret(t, verb, fmt.Sprintf(verb, sel), f.URL)
	}
	if b, err := json.Marshal(sel); err == nil {
		t.Fatalf("sérialisé %s", b)
	}
}

func TestSourcesHandlersRefuseBadArgs(t *testing.T) {
	svc, f, _, _ := newTestService(t)
	res := newResolver()
	h := Handlers(svc, res.resolve)
	play := PlayHandler(svc, func(context.Context, Selected) (any, error) {
		t.Fatal("lecture sans flux")
		return nil, nil
	})
	h["stremio_play"] = play
	cases := []struct{ cmd, args, want string }{
		{"stremio_sources", `{}`, "invalid args for command `stremio_sources`"},
		{"stremio_sources", `{"itemId":null,"external":null}`, "invalid args for command `stremio_sources`"},
		{"stremio_sources", `{"itemId":""}`, "command stremio_sources missing required key itemId"},
		{"stremio_sources", `{"itemId":7}`, "invalid args for command `stremio_sources`"},
		{"stremio_sources", `{"itemId":"1","x":1}`, "invalid args for command `stremio_sources`"},
		{"stremio_sources", `{"itemId":"1","external":{"imdb":"tt0111161","name":"hunter2"}}`, "invalid args for command `stremio_sources`"},
		{"stremio_sources", `{"external":"tt0111161"}`, "invalid args for command `stremio_sources`"},
		{"stremio_sources", `{"external":{"imdb":"tt0111161","name":"hunter2","year":1994}}`, "invalid args for command `stremio_sources`"},
		{"stremio_sources", `{"external":{"imdb":"tt0111161","name":"hunter2","season":1.5,"episode":2}}`, "invalid args for command `stremio_sources`"},
		{"stremio_sources", `{"external":{"name":"hunter2"}}`, "command stremio_sources missing required key external.imdb"},
		{"stremio_sources", `{"external":{"imdb":"tt0111161"}}`, "command stremio_sources missing required key external.name"},
		{"stremio_addon_streams", `{"query":"q"}`, "command stremio_addon_streams missing required key addonId"},
		{"stremio_addon_streams", `{"addonId":"a"}`, "command stremio_addon_streams missing required key query"},
		{"stremio_addon_streams", `["q","a"]`, "invalid args for command `stremio_addon_streams`"},
		{"stremio_play", `{}`, "command stremio_play missing required key streamId"},
		{"stremio_play", `{"streamId":"https://debrid.example/x.mkv","source":"x"}`, "invalid args for command `stremio_play`"},
	}
	for _, c := range cases {
		_, err := call(t, h, c.cmd, c.args)
		var msg string
		if json.Unmarshal([]byte(err), &msg) != nil || !strings.HasPrefix(msg, c.want) {
			t.Errorf("%s %s : %s", c.cmd, c.args, err)
		}
		assertNoSecret(t, c.cmd, err, f.URL)
	}
	if len(res.asked) != 0 || len(f.log()) != 0 {
		t.Fatal("appel avant validation")
	}
}

// stremio_play looks the stream up, then hands it to the player; an unknown id
// never reaches it.
func TestPlayHandler(t *testing.T) {
	svc, _ := signedIn(t)
	res := newResolver()
	var played []Selected
	h := map[string]Handler{"stremio_play": PlayHandler(svc, func(_ context.Context, s Selected) (any, error) {
		played = append(played, s)
		return "ok", nil
	})}
	if _, err := call(t, h, "stremio_play", `{"streamId":"stream_inconnu"}`); err != `{"kind":"expired","message":"La liste a expiré, rouvre-la."}` {
		t.Fatal(err)
	}
	q, ids := sourcesOf(t, svc, res, "30207")
	s, err := svc.AddonStreams(context.Background(), q, ids["Lumio · Profil"])
	if err != nil {
		t.Fatal(err)
	}
	if out, err := call(t, h, "stremio_play", fmt.Sprintf(`{"streamId":%q}`, s.Streams[0].ID)); err != "" || out != `"ok"` {
		t.Fatal(out, err)
	}
	if len(played) != 1 || played[0].ItemID != "30207" || played[0].Stream.Name != "[AD⚡️] Lumio" {
		t.Fatal(played)
	}
}

func TestTargetOfATitleOutsideEmby(t *testing.T) {
	cases := []struct {
		name  string
		ext   External
		typ   ContentType
		id    string
		title string
	}{
		{"film", External{Imdb: "tt0111161", Name: "Les Évadés"}, Movie, "tt0111161",
			`{"name":"Les Évadés","seriesName":null,"seasonIndex":null,"index":null,"indexEnd":null}`},
		{"épisode", External{Imdb: "tt0944947", Name: "La Série", Season: ptr(int64(1)), Episode: ptr(int64(2))}, Series, "tt0944947:1:2",
			`{"name":"Épisode 2","seriesName":"La Série","seasonIndex":1,"index":2,"indexEnd":null}`},
		{"épisode spécial", External{Imdb: "tt0944947", Name: "La Série", Season: ptr(int64(0)), Episode: ptr(int64(3))}, Series, "tt0944947:0:3",
			`{"name":"Épisode 3","seriesName":"La Série","seasonIndex":0,"index":3,"indexEnd":null}`},
		{"nom entouré d'espaces", External{Imdb: "tt0111161", Name: "  Les Évadés \n"}, Movie, "tt0111161",
			`{"name":"Les Évadés","seriesName":null,"seasonIndex":null,"index":null,"indexEnd":null}`},
	}
	for _, c := range cases {
		typ, id, title, err := ExternalTarget(c.ext)
		if err != nil || typ != c.typ || id != c.id {
			t.Errorf("%s : %q %q %v", c.name, typ, id, err)
			continue
		}
		if b, _ := json.Marshal(title); string(b) != c.title {
			t.Errorf("%s : titre %s", c.name, b)
		}
	}
	invalid := []struct {
		name string
		ext  External
	}{
		{"saison seule", External{Imdb: "tt0944947", Name: "x", Season: ptr(int64(1))}},
		{"épisode seul", External{Imdb: "tt0944947", Name: "x", Episode: ptr(int64(1))}},
		{"épisode 0", External{Imdb: "tt0944947", Name: "x", Season: ptr(int64(1)), Episode: ptr(int64(0))}},
		{"saison négative", External{Imdb: "tt0944947", Name: "x", Season: ptr(int64(-1)), Episode: ptr(int64(1))}},
		{"saison trop grande", External{Imdb: "tt0944947", Name: "x", Season: ptr(int64(1) << 32), Episode: ptr(int64(1))}},
		{"épisode trop grand", External{Imdb: "tt0944947", Name: "x", Season: ptr(int64(1)), Episode: ptr(int64(1) << 32)}},
		{"pas un id IMDb", External{Imdb: "278", Name: "x"}},
		{"id IMDb vide", External{Imdb: "", Name: "x"}},
		{"nom vide", External{Imdb: "tt0111161", Name: " "}},
	}
	for _, c := range invalid {
		if _, _, _, err := ExternalTarget(c.ext); !errors.Is(err, ErrInvalidID) {
			t.Errorf("%s : %v", c.name, err)
		}
	}
}

// A title outside Emby is asked by its IMDb id: Emby is never read, the query is
// keyed `stremio:<target>`, and its streams play under that key and the title given.
func TestSourcesOfATitleOutsideEmby(t *testing.T) {
	svc, f := signedIn(t)
	res := newResolver()
	h := Handlers(svc, res.resolve)
	var played []Selected
	h["stremio_play"] = PlayHandler(svc, func(_ context.Context, s Selected) (any, error) {
		played = append(played, s)
		return "ok", nil
	})
	var src Sources

	out, errText := call(t, h, "stremio_sources", `{"external":{"imdb":"tt0111161","name":"Les Évadés"}}`)
	if errText != "" || json.Unmarshal([]byte(out), &src) != nil {
		t.Fatal(out, errText)
	}
	assertNoSecret(t, "sources", out, f.URL)
	var names []string
	ids := map[string]string{}
	for _, a := range src.Addons {
		names = append(names, a.Name)
		ids[a.Name] = a.ID
	}
	if src.Key != "stremio:tt0111161" || !strings.HasPrefix(src.Query, "query_") || !slices.Contains(names, "TMDB Collections") {
		t.Fatal(out)
	}
	s, err := svc.AddonStreams(context.Background(), src.Query, ids["Lumio · Profil"])
	if err != nil {
		t.Fatal(err)
	}
	if out, err := call(t, h, "stremio_play", fmt.Sprintf(`{"streamId":%q}`, s.Streams[0].ID)); err != "" || out != `"ok"` {
		t.Fatal(out, err)
	}
	if len(played) != 1 || played[0].ItemID != "stremio:tt0111161" || played[0].Title == nil || played[0].Title.Name != "Les Évadés" ||
		played[0].Title.SeriesName != nil || played[0].Stream.Name != "[AD⚡️] Lumio" {
		t.Fatalf("%+v %+v", played, played[0].Title)
	}

	out, errText = call(t, h, "stremio_sources", `{"external":{"imdb":"tt0944947","name":"La Série","season":1,"episode":2}}`)
	src = Sources{}
	if errText != "" || json.Unmarshal([]byte(out), &src) != nil || src.Key != "stremio:tt0944947:1:2" ||
		strings.Contains(out, "TMDB Collections") || !strings.Contains(out, `"name":"Lumio · Profil"`) {
		t.Fatal(out, errText)
	}
	ids = map[string]string{}
	for _, a := range src.Addons {
		ids[a.Name] = a.ID
	}
	s, err = svc.AddonStreams(context.Background(), src.Query, ids["Comet | AD"])
	if err != nil || len(s.Streams) == 0 {
		t.Fatal(err)
	}
	sel, err := svc.Selected(s.Streams[0].ID)
	if err != nil || sel.ItemID != "stremio:tt0944947:1:2" {
		t.Fatal(sel.ItemID, err)
	}
	if b, _ := json.Marshal(sel.Title); string(b) != `{"name":"Épisode 2","seriesName":"La Série","seasonIndex":1,"index":2,"indexEnd":null}` {
		t.Fatal(string(b))
	}
	if len(res.asked) != 0 {
		t.Fatal("Emby lu pour un titre hors Emby", res.asked)
	}
}

func TestSourcesOfATitleOutsideEmbyErrors(t *testing.T) {
	svc, f, _, _ := newTestService(t)
	res := newResolver()
	h := Handlers(svc, res.resolve)
	if _, err := call(t, h, "stremio_sources", `{"external":{"imdb":"tt0111161","name":"x"}}`); err != `{"kind":"signedOut","message":"Connecte ton compte Stremio dans les Réglages."}` {
		t.Fatal(err)
	}
	if err := svc.SignIn(context.Background(), "owner@example.com", "hunter2"); err != nil {
		t.Fatal(err)
	}
	const invalid = `{"kind":"invalidId","message":"Identifiant de contenu invalide."}`
	for _, a := range []string{
		`{"external":{"imdb":"tt0944947","name":"hunter2","season":1}}`,
		`{"external":{"imdb":"tt0944947","name":"hunter2","episode":1}}`,
		`{"external":{"imdb":"tt0944947","name":"hunter2","season":-1,"episode":1}}`,
		`{"external":{"imdb":"tt0944947","name":"hunter2","season":1,"episode":0}}`,
		`{"external":{"imdb":"tt0944947","name":"hunter2","season":1,"episode":null}}`,
		`{"external":{"imdb":"https://debrid.example/x","name":"hunter2"}}`,
		`{"external":{"imdb":"tt0111161","name":""}}`,
	} {
		if _, err := call(t, h, "stremio_sources", a); err != invalid {
			t.Errorf("%s : %s", a, err)
		}
	}
	for _, r := range f.log() {
		if strings.Contains(r.URI, "/stream/") {
			t.Fatal("addon interrogé pour un titre invalide", r.URI)
		}
	}
	if len(res.asked) != 0 {
		t.Fatal("Emby lu", res.asked)
	}
}
