package emby

import (
	"errors"
	"strings"
	"sync/atomic"
	"testing"
)

// externalRoute is the browse_like server with items carrying ProviderIds: a film
// (key `Imdb`), a series (key `IMDB`, as Emby 4.10.2.0 writes it), its episode, an
// episode without numbers, a film without IMDb, a series without IMDb, an episode
// of a missing series and a film keyed `imdb`.
func externalRoute(revoked *atomic.Bool) fakeRoute {
	base := fakeBrowseLike(revoked)
	items := "/emby/Users/" + fakeUserID + "/Items/"
	answers := map[string]string{
		"91001": `{"Name":" Le Film ","Id":"91001","Type":"Movie","ProviderIds":{"Imdb":"tt0111161","Tmdb":"278"}}`,
		"91002": `{"Name":"La Série","Id":"91002","Type":"Series","ProviderIds":{"IMDB":"tt0944947"}}`,
		"91003": `{"Name":"Pilote","Id":"91003","Type":"Episode","SeriesId":"91002","SeriesName":"La Série",
			"ParentIndexNumber":2,"IndexNumber":5,"ProviderIds":{"Imdb":"tt9999999"}}`,
		"91004": `{"Name":"Sans numéro","Id":"91004","Type":"Episode","SeriesId":"91002"}`,
		"91005": `{"Name":"Sans IMDb","Id":"91005","Type":"Movie","ProviderIds":{"Tmdb":"1"}}`,
		"91006": `{"Name":"Série sans IMDb","Id":"91006","Type":"Series","ProviderIds":{"imdb":"nm0000001"}}`,
		"91007": `{"Name":"Épisode","Id":"91007","Type":"Episode","SeriesId":"91006","ParentIndexNumber":1,"IndexNumber":1}`,
		"91008": `{"Name":"Épisode orphelin","Id":"91008","Type":"Episode","SeriesId":"91999","ParentIndexNumber":1,"IndexNumber":1}`,
		"91009": `{"Name":"Minuscules","Id":"91009","Type":"Movie","ProviderIds":{"imdb":"tt0068646"}}`,
	}
	return func(r fakeRequest) (int, string) {
		path, _ := splitQuery(r.Path)
		if id, ok := strings.CutPrefix(path, items); ok && r.hasToken() && !revoked.Load() {
			if body, found := answers[id]; found {
				return 200, body
			}
		}
		return base(r)
	}
}

func Test_go_external_ref_of_a_movie_and_an_episode(t *testing.T) {
	revoked := new(atomic.Bool)
	server := startFakeEmby(t, externalRoute(revoked))
	fx := newFixture(t)
	mustSignIn(t, fx, server.URL)

	movie, err := fx.service.ExternalRef(bg, "91001")
	if err != nil {
		t.Fatal(err)
	}
	if movie.ItemType != "Movie" || movie.IDs.Imdb == nil || *movie.IDs.Imdb != "tt0111161" || movie.Episode != nil {
		t.Fatalf("%+v", movie)
	}
	// The same title as emby_play's.
	if movie.Title == nil || movie.Title.Name != "Le Film" || movie.Title.SeriesName != nil {
		t.Fatalf("%+v", movie.Title)
	}

	// An episode is asked by its series' IMDb (key `IMDB`), never its own.
	ep, err := fx.service.ExternalRef(bg, "91003")
	if err != nil {
		t.Fatal(err)
	}
	if ep.ItemType != "Episode" || ep.IDs.Imdb == nil || *ep.IDs.Imdb != "tt0944947" ||
		ep.Episode == nil || ep.Episode.Season != 2 || ep.Episode.Episode != 5 {
		t.Fatalf("%+v", ep)
	}
	if ep.Title == nil || ep.Title.Name != "Pilote" || ep.Title.SeriesName == nil || *ep.Title.SeriesName != "La Série" ||
		ep.Title.SeasonIndex == nil || *ep.Title.SeasonIndex != 2 || ep.Title.Index == nil || *ep.Title.Index != 5 {
		t.Fatalf("%+v", ep.Title)
	}

	// Any key case.
	lower, err := fx.service.ExternalRef(bg, "91009")
	if err != nil || lower.IDs.Imdb == nil || *lower.IDs.Imdb != "tt0068646" {
		t.Fatalf("%+v %v", lower, err)
	}

	// No IMDb: empty ids, no error.
	for _, id := range []string{"91004", "91005", "91007", "91008"} {
		ref, err := fx.service.ExternalRef(bg, id)
		if err != nil {
			t.Fatalf("%s : %v", id, err)
		}
		if ref.IDs.Imdb != nil {
			t.Fatalf("%s : %+v", id, ref)
		}
	}

	// A refused token ends the session, as any read.
	revoked.Store(true)
	if _, err := fx.service.ExternalRef(bg, "91001"); !errors.Is(err, ErrSessionExpired) {
		t.Fatal(err)
	}
}

func Test_go_external_ref_needs_a_session(t *testing.T) {
	fx := newFixture(t)
	if _, err := fx.service.ExternalRef(bg, "91001"); !errors.Is(err, ErrNotSignedIn) {
		t.Fatal(err)
	}
}
