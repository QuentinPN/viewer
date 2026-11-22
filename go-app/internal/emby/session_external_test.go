package emby_test

// tests.rs tests that need packages importing emby (artworkcache, request).

import (
	"bytes"
	"context"
	"errors"
	"os"
	"path/filepath"
	"reflect"
	"testing"

	"viewer/internal/artworkcache"
	"viewer/internal/emby"
	"viewer/internal/request"
)

var bg = context.Background()

func Test_artwork_cache_spares_the_server_and_keeps_its_checks(t *testing.T) {
	url, fetched := emby.FakeBrowseServer(t)
	root := t.TempDir()
	cache := artworkcache.New(root, 1<<20)
	service := emby.FakeService(t).WithArtworkCache(cache)
	if _, err := service.SignIn(bg, url, "testeur", emby.FakePassword); err != nil {
		t.Fatal(err)
	}
	query := "tag=c0ffee_1&maxWidth=360"
	image := func(item string) emby.ImageRequest {
		req, ok := emby.ParseImageRequest("/"+emby.FakeServerID+"/"+item+"/Primary", &query)
		if !ok {
			t.Fatal("requête d'image refusée")
		}
		return req
	}

	// Downloaded once, then read from the disk.
	firstType, first, err := service.Image(bg, image("103"))
	if err != nil {
		t.Fatal(err)
	}
	if firstType != "image/jpeg" || string(first[3:]) != "cached-artwork" || fetched() != 1 {
		t.Fatalf("%s %q %d", firstType, first, fetched())
	}
	againType, again, err := service.Image(bg, image("103"))
	if err != nil || againType != firstType || !bytes.Equal(again, first) || fetched() != 1 {
		t.Fatalf("%v %d", err, fetched())
	}
	if s := cache.Stats(); s.Entries != 1 || s.Hits != 1 || s.Misses != 1 {
		t.Fatalf("%+v", s)
	}

	// Bytes without an image signature are served as before but never kept.
	for range 2 {
		if _, _, err := service.Image(bg, image("101")); err != nil {
			t.Fatal(err)
		}
	}
	if fetched() != 3 {
		t.Fatal(fetched())
	}

	// Only the server id and the image key on disk: no address, no token.
	file := filepath.Join(root, emby.FakeServerID, "103", "Primary-360-c0ffee_1.img")
	if fi, err := os.Stat(file); err != nil || !fi.Mode().IsRegular() {
		t.Fatal(err)
	}
	entries, err := os.ReadDir(root)
	if err != nil {
		t.Fatal(err)
	}
	var names []string
	for _, e := range entries {
		names = append(names, e.Name())
	}
	if !reflect.DeepEqual(names, []string{emby.FakeServerID}) {
		t.Fatal(names)
	}
	if data, _ := os.ReadFile(file); bytes.Contains(data, []byte(emby.FakeToken)) {
		t.Fatal("jeton sur le disque")
	}

	// The checks still come first: another server, or no session, gets nothing.
	foreign, ok := emby.ParseImageRequest("/otherserver/103/Primary", &query)
	if !ok {
		t.Fatal("requête d'image refusée")
	}
	if _, _, err := service.Image(bg, foreign); !errors.Is(err, emby.ErrForbidden) {
		t.Fatal(err)
	}
	if err := service.SignOut(bg); err != nil {
		t.Fatal(err)
	}
	if _, _, err := service.Image(bg, image("103")); !errors.Is(err, emby.ErrNotSignedIn) {
		t.Fatal(err)
	}
	if fetched() != 3 {
		t.Fatal(fetched())
	}

	// Emptied from the settings: local only, no session needed. Rust's
	// artwork_stats / clear_artwork are the cache's Stats / Clear here.
	if c := cache.Clear(); c.Entries != 1 || c.Bytes != 3+14 {
		t.Fatalf("%+v", c)
	}
	if _, err := os.Stat(file); !os.IsNotExist(err) {
		t.Fatal(err)
	}
	if cache.Stats().Entries != 0 || fetched() != 3 {
		t.Fatal("cache non vidé")
	}
}

// RequestAccount duplicates request.Account (request imports emby): the
// two must stay equal, or forgetting a server would leave the Request token.
func TestRequestAccountIsRequestAccount(t *testing.T) {
	got := emby.RequestAccount(emby.FakeServerID, emby.FakeUserID)
	if want := request.Account(emby.FakeServerID, emby.FakeUserID); got != want {
		t.Fatalf("%q != %q", got, want)
	}
}

// request/tests.rs `emby_forget_drops_request_token`, deferred to wave 4 by
// wave 6: forget_server and sign_out delete the Request token of that account
// only.
func Test_emby_forget_drops_request_token(t *testing.T) {
	const embyUser = emby.FakeUserID
	for _, forget := range []bool{true, false} {
		store := &emby.MemoryCredentialStore{}
		path := filepath.Join(t.TempDir(), "session.json")
		file := emby.SessionFile{Session: &emby.SessionRecord{
			ServerURL: "http://127.0.0.1:9", ServerID: emby.FakeServerID, ServerName: "ServeurTest",
			ServerVersion: "4.10.1.0", UserID: embyUser, UserName: "testeur",
		}}
		if err := file.Save(path); err != nil {
			t.Fatal(err)
		}
		save := func(account, value string) {
			if err := store.Save(account, emby.NewAccessToken(value)); err != nil {
				t.Fatal(err)
			}
		}
		save(emby.CredentialAccount(emby.FakeServerID, embyUser), "emby-token-0123456789abcdef")
		// seed: a token stored for the issuer, as the Request service keeps it.
		save(request.Account(emby.FakeServerID, embyUser), "https://request.exemple.fr ur-token")
		save(request.Account(emby.FakeServerID, "otheruser"), "https://request.exemple.fr other-ur-token")
		service := emby.NewEmbyService(store, path, "PC-TEST", "9.9.9")
		if forget {
			if _, err := service.ForgetServer(emby.FakeServerID, embyUser); err != nil {
				t.Fatal(err)
			}
		} else if err := service.SignOut(bg); err != nil {
			t.Fatal(err)
		}
		if tok, _ := store.Load(request.Account(emby.FakeServerID, embyUser)); tok != nil {
			t.Fatalf("forget=%v : jeton Request gardé", forget)
		}
		other, _ := store.Load(request.Account(emby.FakeServerID, "otheruser"))
		if other == nil || other.Expose() != "https://request.exemple.fr other-ur-token" {
			t.Fatalf("forget=%v : jeton d'un autre compte touché", forget)
		}
	}
}
