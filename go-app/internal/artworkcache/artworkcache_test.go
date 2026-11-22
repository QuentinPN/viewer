package artworkcache

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"

	"viewer/internal/emby"
)

var jpeg = []byte{0xFF, 0xD8, 0xFF, 0xE0, 1, 2, 3, 4}

func newCache(t *testing.T, quota uint64) *Cache {
	t.Helper()
	return New(filepath.Join(t.TempDir(), "artwork"), quota)
}

func parse(t *testing.T, path, query string) emby.ImageRequest {
	t.Helper()
	req, ok := emby.ParseImageRequest(path, &query)
	if !ok {
		t.Fatalf("request %s?%s refused", path, query)
	}
	return req
}

func req(t *testing.T, item string, width int) emby.ImageRequest {
	t.Helper()
	return parse(t, "/srv1/"+item+"/Primary", fmt.Sprintf("tag=ab12_6384&maxWidth=%d", width))
}

func age(t *testing.T, path string, secs int) {
	t.Helper()
	when := time.Now().Add(-time.Duration(secs) * time.Second)
	if err := os.Chtimes(path, when, when); err != nil {
		t.Fatal(err)
	}
}

func mustPut(t *testing.T, c *Cache, r emby.ImageRequest, data []byte) bool {
	t.Helper()
	ok, err := c.Put(r, data)
	if err != nil {
		t.Fatal(err)
	}
	return ok
}

func exists(path string) bool {
	_, err := os.Stat(path)
	return err == nil
}

func Test_keeps_and_serves_images_by_request(t *testing.T) {
	c := newCache(t, DefaultQuota)
	if _, _, ok := c.Get(req(t, "1", 360)); ok {
		t.Fatal("empty cache served an image")
	}
	if !mustPut(t, c, req(t, "1", 360), jpeg) {
		t.Fatal("jpeg not kept")
	}
	ct, data, ok := c.Get(req(t, "1", 360))
	if !ok || ct != "image/jpeg" || !bytes.Equal(data, jpeg) {
		t.Fatalf("Get = %q %v %v", ct, data, ok)
	}
	// Another width is another image.
	if _, _, ok := c.Get(req(t, "1", 480)); ok {
		t.Fatal("another width served")
	}
	want := filepath.Join(c.root, "srv1", "1", "Primary-360-ab12_6384.img")
	if got := c.path(req(t, "1", 360)); got != want {
		t.Fatalf("path = %s, want %s", got, want)
	}
	s := c.Stats()
	if s.Entries != 1 || s.Bytes != uint64(len(jpeg)) || s.Hits != 1 || s.Misses != 2 {
		t.Fatalf("stats = %+v", s)
	}
}

func Test_refuses_what_is_not_an_image(t *testing.T) {
	c := newCache(t, DefaultQuota)
	if mustPut(t, c, req(t, "1", 360), []byte("<html>pas une image</html>")) {
		t.Fatal("html kept")
	}
	if c.Stats().Entries != 0 {
		t.Fatal("entry created")
	}
	// A file changed on disk is dropped, not served.
	mustPut(t, c, req(t, "2", 360), jpeg)
	if err := os.WriteFile(c.path(req(t, "2", 360)), []byte("garbage"), 0o644); err != nil {
		t.Fatal(err)
	}
	if _, _, ok := c.Get(req(t, "2", 360)); ok {
		t.Fatal("garbage served")
	}
	if exists(c.path(req(t, "2", 360))) {
		t.Fatal("garbage kept on disk")
	}
}

func Test_recognizes_the_formats_emby_serves(t *testing.T) {
	for _, tc := range []struct {
		in   []byte
		want string
	}{
		{jpeg, "image/jpeg"},
		{[]byte("\x89PNG\r\n\x1a\n...."), "image/png"},
		{[]byte("GIF89a"), "image/gif"},
		{[]byte("RIFF\x00\x00\x00\x00WEBPVP8 "), "image/webp"},
		{[]byte("RIFF\x00\x00\x00\x00WAVE"), ""},
		{[]byte(""), ""},
	} {
		got, ok := sniff(tc.in)
		if got != tc.want || ok != (tc.want != "") {
			t.Errorf("sniff(%q) = %q, %v; want %q", tc.in, got, ok, tc.want)
		}
	}
}

func Test_quota_drops_the_least_recently_used(t *testing.T) {
	image := func(n byte) []byte {
		b := append([]byte(nil), jpeg...)
		for len(b) < 100 {
			b = append(b, n)
		}
		return b
	}
	// Room for 4 images of 100 bytes; eviction goes down to 80 % (3 images).
	c := newCache(t, 400)
	for _, it := range []struct {
		item string
		secs int
	}{{"1", 4000}, {"2", 9000}, {"3", 6000}, {"4", 5000}} {
		mustPut(t, c, req(t, it.item, 360), image(1))
		age(t, c.path(req(t, it.item, 360)), it.secs)
	}
	// Reading "2" makes it recent: "3" becomes the oldest.
	if _, _, ok := c.Get(req(t, "2", 360)); !ok {
		t.Fatal("2 missing")
	}
	if !mustPut(t, c, req(t, "5", 360), image(2)) {
		t.Fatal("5 not kept")
	}
	if s := c.Stats(); s.Entries != 3 || s.Bytes != 300 {
		t.Fatalf("stats = %+v", s)
	}
	for item, kept := range map[string]bool{"1": true, "2": true, "3": false, "4": false, "5": true} {
		if got := exists(c.path(req(t, item, 360))); got != kept {
			t.Errorf("item %s kept = %v, want %v", item, got, kept)
		}
	}
	if exists(filepath.Join(c.root, "srv1", "3")) {
		t.Error("empty folder of 3 kept")
	}
	// Too large for the quota: not kept at all.
	if mustPut(t, c, req(t, "6", 360), append(image(3), 0)) {
		t.Error("oversized image kept")
	}
}

func Test_clear_removes_every_image_and_keeps_other_files(t *testing.T) {
	c := newCache(t, DefaultQuota)
	dir := c.root
	mustPut(t, c, req(t, "1", 360), jpeg)
	mustPut(t, c, req(t, "2", 360), jpeg)
	other := parse(t, "/srv2/9/Backdrop", "tag=cd_1&maxWidth=720")
	mustPut(t, c, other, jpeg)
	// Not ours: never removed.
	foreign := filepath.Join(dir, "srv1", "1", "notes.txt")
	if err := os.WriteFile(foreign, []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}

	if got, want := c.Clear(), (Cleared{3, 3 * uint64(len(jpeg))}); got != want {
		t.Fatalf("Clear = %+v, want %+v", got, want)
	}
	if _, _, ok := c.Get(req(t, "1", 360)); ok {
		t.Error("cleared image served")
	}
	if !exists(foreign) {
		t.Error("foreign file removed")
	}
	if exists(filepath.Join(dir, "srv1", "2")) {
		t.Error("empty folders kept")
	}
	if exists(filepath.Join(dir, "srv2")) {
		t.Error("empty server folder kept")
	}
	if s := c.Stats(); s.Entries != 0 || s.Bytes != 0 {
		t.Fatalf("stats after clear = %+v", s)
	}

	// Still usable, and its size is counted again from the disk.
	if !mustPut(t, c, req(t, "1", 360), jpeg) {
		t.Fatal("not usable after clear")
	}
	if c.Stats().Entries != 1 {
		t.Fatal("entry missing after clear")
	}
	if got, want := c.Clear(), (Cleared{1, uint64(len(jpeg))}); got != want {
		t.Fatalf("second Clear = %+v, want %+v", got, want)
	}
	if got := c.Clear(); got != (Cleared{}) {
		t.Fatalf("nothing left, Clear = %+v", got)
	}
}

func Test_stale_temporary_files_are_removed(t *testing.T) {
	c := newCache(t, DefaultQuota)
	mustPut(t, c, req(t, "1", 360), jpeg)
	dir := filepath.Join(c.root, "srv1", "1")
	old, fresh := filepath.Join(dir, ".old.tmp"), filepath.Join(dir, ".fresh.tmp")
	for _, p := range []string{old, fresh} {
		if err := os.WriteFile(p, jpeg, 0o644); err != nil {
			t.Fatal(err)
		}
	}
	age(t, old, 7200)
	if c.Stats().Entries != 1 {
		t.Fatal("temporary files counted as images")
	}
	if exists(old) || !exists(fresh) {
		t.Fatalf("old exists = %v, fresh exists = %v", exists(old), exists(fresh))
	}
}

// Not in Rust: the file layout is the contract that keeps an old cache folder valid.
func Test_the_layout_is_the_rust_one(t *testing.T) {
	c := newCache(t, DefaultQuota)
	mustPut(t, c, req(t, "42", 640), jpeg)
	want := filepath.Join(c.root, "srv1", "42", "Primary-640-ab12_6384.img")
	got, err := os.ReadFile(want)
	if err != nil || !bytes.Equal(got, jpeg) {
		t.Fatalf("file %s: %v %v", want, got, err)
	}
	entries, err := os.ReadDir(filepath.Dir(want))
	if err != nil || len(entries) != 1 {
		t.Fatalf("leftover temp files: %v %v", entries, err)
	}
	// An image written by the Rust side (same name) is served as is.
	mustPut(t, c, parse(t, "/srv1/7/Backdrop", "tag=t-1&maxWidth=1920"), jpeg)
	if !exists(filepath.Join(c.root, "srv1", "7", "Backdrop-1920-t-1.img")) {
		t.Fatal("Backdrop name differs")
	}
}

// A read refreshes the date of an old image, but not of a recent one.
func Test_a_read_touches_only_stale_files(t *testing.T) {
	c := newCache(t, DefaultQuota)
	mustPut(t, c, req(t, "1", 360), jpeg)
	mustPut(t, c, req(t, "2", 360), jpeg)
	p1, p2 := c.path(req(t, "1", 360)), c.path(req(t, "2", 360))
	age(t, p1, 7200)
	age(t, p2, 600)
	before, _ := os.Stat(p2)
	c.Get(req(t, "1", 360))
	c.Get(req(t, "2", 360))
	i1, _ := os.Stat(p1)
	i2, _ := os.Stat(p2)
	if time.Since(i1.ModTime()) > time.Minute {
		t.Error("stale file not touched")
	}
	if !i2.ModTime().Equal(before.ModTime()) {
		t.Error("recent file touched")
	}
}

func Test_stats_and_cleared_serialize_in_camel_case(t *testing.T) {
	s, _ := json.Marshal(Stats{Entries: 1, Bytes: 2, QuotaBytes: 3, Hits: 4, Misses: 5})
	if string(s) != `{"entries":1,"bytes":2,"quotaBytes":3,"hits":4,"misses":5}` {
		t.Errorf("stats json = %s", s)
	}
	c, _ := json.Marshal(Cleared{Entries: 1, Bytes: 2})
	if string(c) != `{"entries":1,"bytes":2}` {
		t.Errorf("cleared json = %s", c)
	}
}

func Test_an_empty_or_missing_root_is_an_empty_cache(t *testing.T) {
	c := newCache(t, DefaultQuota)
	if s := c.Stats(); s.Entries != 0 || s.Bytes != 0 || s.QuotaBytes != DefaultQuota {
		t.Fatalf("stats = %+v", s)
	}
	if got := c.Clear(); got != (Cleared{}) {
		t.Fatalf("Clear = %+v", got)
	}
}

func Test_extension_follows_rust_path_extension(t *testing.T) {
	for name, want := range map[string]string{
		"a.img": "img", ".x.tmp": "tmp", ".tmp": "", "noext": "", "a.b.img": "img", "a.": "",
	} {
		if got := extensionOf(name); got != want {
			t.Errorf("extensionOf(%q) = %q, want %q", name, got, want)
		}
	}
}

// Concurrency: many writers, readers, stats and a clearer at once. Run without
// -race on Windows (it needs cgo); the checks below still catch lost accounting.
func Test_concurrent_puts_and_gets_respect_the_quota(t *testing.T) {
	const size, quota = 100, 1000
	c := newCache(t, quota)
	image := func() []byte {
		b := append([]byte(nil), jpeg...)
		for len(b) < size {
			b = append(b, 7)
		}
		return b
	}
	var wg sync.WaitGroup
	const workers, perWorker = 8, 25
	for w := range workers {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for i := range perWorker {
				r := req(t, fmt.Sprintf("w%di%d", w, i), 360)
				// As in Rust, an eviction may remove the folder of a Put that
				// has just created it: that Put fails, nothing else.
				if _, err := c.Put(r, image()); err != nil && !errors.Is(err, fs.ErrNotExist) {
					t.Errorf("put: %v", err)
					return
				}
				c.Get(r)
				c.Get(req(t, "absent", 360))
				c.Stats()
			}
		}()
	}
	wg.Wait()
	s := c.Stats()
	if s.Bytes > quota {
		t.Errorf("bytes %d over the quota %d", s.Bytes, quota)
	}
	if s.Entries == 0 || s.Bytes != s.Entries*size {
		t.Errorf("stats = %+v", s)
	}
	if s.Hits+s.Misses != workers*perWorker*2 {
		t.Errorf("hits+misses = %d, want %d", s.Hits+s.Misses, workers*perWorker*2)
	}
	// No temporary file is left behind.
	_ = filepath.WalkDir(c.root, func(p string, d os.DirEntry, err error) error {
		if err == nil && !d.IsDir() && extensionOf(d.Name()) == "tmp" {
			t.Errorf("temporary file left: %s", p)
		}
		return nil
	})
}

// A concurrent Clear may take a folder away between MkdirAll and the write (as in
// Rust), so Put may fail there; nothing may panic or leave the cache unusable.
func Test_concurrent_clear_leaves_a_usable_cache(t *testing.T) {
	c := newCache(t, DefaultQuota)
	var wg sync.WaitGroup
	stop := make(chan struct{})
	wg.Add(1)
	go func() {
		defer wg.Done()
		for {
			select {
			case <-stop:
				return
			default:
				c.Clear()
			}
		}
	}()
	var puts sync.WaitGroup
	for w := range 4 {
		puts.Add(1)
		go func() {
			defer puts.Done()
			for i := range 30 {
				r := req(t, fmt.Sprintf("w%di%d", w, i), 360)
				_, _ = c.Put(r, jpeg)
				c.Get(r)
			}
		}()
	}
	puts.Wait()
	close(stop)
	wg.Wait()

	c.Clear()
	if s := c.Stats(); s.Entries != 0 || s.Bytes != 0 {
		t.Fatalf("stats after final clear = %+v", s)
	}
	if !mustPut(t, c, req(t, "after", 360), jpeg) {
		t.Fatal("cache unusable after concurrent clears")
	}
	if c.Stats().Entries != 1 {
		t.Fatal("entry missing")
	}
}
