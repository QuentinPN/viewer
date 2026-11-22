package emby

// Chapters of an item for the player (ADR 0034), port of src-tauri/src/emby/chapters.rs:
// Emby's `Chapters`, kept to the regular `Chapter` markers, in seconds of the file. The
// intro and credits markers stay with the segments (ADR 0030).

import (
	"sort"
	"strconv"
	"strings"
	"unicode"
)

const ticksPerSecond = 10_000_000.0

// minChapterGapSeconds: chapters closer than this to the previous one are dropped:
// they would share a mark on the seek bar and one key press would skip nothing.
const minChapterGapSeconds = 1.0

// maxChapterNameChars: names are shown on one line; anything longer is cut (characters).
const maxChapterNameChars = 120

type Chapter struct {
	// Start is in seconds from the start of the file.
	Start float64 `json:"start"`
	Name  string  `json:"name"`
}

type foundChapter struct {
	start float64
	name  *string
}

// Chapters is the list the player offers, in order. Fewer than two give none: a
// single chapter is the whole file and offers no navigation. Never nil.
func Chapters(dtos []ChapterDto, runTimeTicks *int64) List[Chapter] {
	var end *float64
	if runTimeTicks != nil && *runTimeTicks > 0 {
		e := float64(*runTimeTicks) / ticksPerSecond
		end = &e
	}
	var found []foundChapter
	for _, c := range dtos {
		if c.MarkerType != nil && *c.MarkerType != "Chapter" {
			continue
		}
		start := float64(c.StartPositionTicks) / ticksPerSecond
		if start >= 0 && (end == nil || start < *end) {
			found = append(found, foundChapter{start, c.Name})
		}
	}
	// Stable, as Rust's sort_by; starts come from integers, so no NaN.
	sort.SliceStable(found, func(i, j int) bool { return found[i].start < found[j].start })
	kept := make([]foundChapter, 0, len(found))
	for _, c := range found {
		if len(kept) == 0 || c.start-kept[len(kept)-1].start >= minChapterGapSeconds {
			kept = append(kept, c)
		}
	}
	if len(kept) < 2 {
		return List[Chapter]{}
	}
	out := make(List[Chapter], len(kept))
	for i, c := range kept {
		name, ok := cleanChapterName(c.name)
		if !ok {
			name = "Chapitre " + strconv.Itoa(i+1)
		}
		out[i] = Chapter{Start: c.start, Name: name}
	}
	return out
}

// cleanChapterName keeps one line of printable text, or false when nothing is left:
// words joined by one space, control characters dropped, at most 120 characters.
func cleanChapterName(name *string) (string, bool) {
	if name == nil {
		return "", false
	}
	line := strings.Join(strings.Fields(*name), " ")
	var b strings.Builder
	n := 0
	for _, r := range line {
		if unicode.IsControl(r) {
			continue
		}
		if n == maxChapterNameChars {
			break
		}
		b.WriteRune(r)
		n++
	}
	return b.String(), b.Len() > 0
}
