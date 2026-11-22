package request

// Tests of src-tauri/src/request/trailer.rs, same names, plus the command shape.

import (
	"encoding/json"
	"errors"
	"strings"
	"testing"
)

func Test_a_video_id_opens_its_youtube_page(t *testing.T) {
	for key, want := range map[string]string{
		"9YGuN8sQpL0": "https://www.youtube.com/watch?v=9YGuN8sQpL0",
		"uygf0l_WzjA": "https://www.youtube.com/watch?v=uygf0l_WzjA",
	} {
		if got, ok := TrailerPage(key); !ok || got != want {
			t.Errorf("%q : %q", key, got)
		}
	}
}

func Test_anything_else_is_refused(t *testing.T) {
	for _, key := range []string{
		"",
		"abc&list=x",
		"abc def",
		"abc\n",
		"../x",
		"https://evil.example/x",
		"é",
		strings.Repeat("a", trailerKeyMax+1),
	} {
		if got, ok := TrailerPage(key); ok {
			t.Errorf("%q : %q", key, got)
		}
	}
	never := func(string) error { t.Fatal("navigateur ouvert"); return nil }
	if err := OpenTrailer("a b", never); err != ErrTrailerInvalid {
		t.Fatal(err)
	}
}

func Test_go_open_trailer_command(t *testing.T) {
	var opened []string
	h := OpenTrailerHandler(func(page string) error { opened = append(opened, page); return nil })
	res, err := h(bg, json.RawMessage(`{"key":"9YGuN8sQpL0"}`))
	if err != nil || res != nil || len(opened) != 1 || opened[0] != "https://www.youtube.com/watch?v=9YGuN8sQpL0" {
		t.Fatal(res, err, opened)
	}
	// TrailerError is a Rust `serialize_str`: no {kind, message}, a bare string.
	_, err = h(bg, json.RawMessage(`{"key":"a b"}`))
	var m json.Marshaler
	if err == nil || errors.As(err, &m) || err.Error() != "bande-annonce invalide" {
		t.Fatal(err)
	}
	failing := OpenTrailerHandler(func(string) error { return errors.New("x") })
	if _, err := failing(bg, json.RawMessage(`{"key":"abc"}`)); err == nil || err.Error() != "navigateur non ouvert : other error" {
		t.Fatal(err)
	}
	// A missing key is refused before anything runs, as Tauri does.
	if _, err := h(bg, json.RawMessage(`{}`)); err == nil || len(opened) != 1 {
		t.Fatal(err, opened)
	}
}
