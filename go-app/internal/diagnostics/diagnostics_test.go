package diagnostics

import (
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"testing"
	"time"
)

// Tests of diagnostics.rs, same names with the `Test_` prefix. The 5 tests of
// `redact` are in internal/redact.

func Test_records_are_redacted_and_bounded(t *testing.T) {
	line := FormatRecord("T", LevelWarn, "viewer_lib::emby", "api_key=k")
	if want := "T WARN  viewer_lib::emby: api_key=<redacted>"; line != want {
		t.Errorf("got %q, want %q", line, want)
	}
	long := FormatRecord("T", LevelInfo, "t", strings.Repeat("é", maxRecordBytes))
	if len(long) > maxRecordBytes+6 || !strings.HasSuffix(long, " [cut]") {
		t.Errorf("long record: len %d, suffix %q", len(long), long[len(long)-8:])
	}
}

func Test_dependencies_log_warnings_only(t *testing.T) {
	for _, c := range []struct {
		target string
		own    Level
		want   Level
	}{
		{"viewer_lib::player", LevelDebug, LevelDebug},
		{"mpv", LevelInfo, LevelInfo},
		{"reqwest::connect", LevelDebug, LevelWarn},
		{"tao", LevelError, LevelError},
	} {
		if got := LevelFor(c.target, c.own); got != c.want {
			t.Errorf("LevelFor(%q, %v) = %v, want %v", c.target, c.own, got, c.want)
		}
	}
}

func Test_formats_utc_timestamps(t *testing.T) {
	if got := Timestamp(0); got != "1970-01-01T00:00:00.000Z" {
		t.Errorf("got %q", got)
	}
	// 2026-11-07T09:05:03.250Z
	if got := Timestamp(1_794_042_303_250 * time.Millisecond); got != "2026-11-07T09:05:03.250Z" {
		t.Errorf("got %q", got)
	}
	// Leap day.
	if got := Timestamp(951_782_400 * time.Second); got != "2000-02-29T00:00:00.000Z" {
		t.Errorf("got %q", got)
	}
}

func tempDir(t *testing.T) string {
	t.Helper()
	return filepath.Join(t.TempDir(), "viewer-logs")
}

func mustRead(t *testing.T, path string) string {
	t.Helper()
	b, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	return string(b)
}

func fileLen(t *testing.T, path string) int64 {
	t.Helper()
	info, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	return info.Size()
}

func Test_rotates_by_size_and_keeps_a_bounded_number_of_files(t *testing.T) {
	dir := tempDir(t)
	file, err := OpenRotating(dir, 100, 3)
	if err != nil {
		t.Fatal(err)
	}
	for n := range 20 {
		if err := file.WriteLine(fmt.Sprintf("%02d %s", n, strings.Repeat("x", 36))); err != nil {
			t.Fatal(err)
		}
	}
	if err := file.Flush(); err != nil {
		t.Fatal(err)
	}
	entries, err := os.ReadDir(dir)
	if err != nil {
		t.Fatal(err)
	}
	var names []string
	for _, e := range entries {
		names = append(names, e.Name())
	}
	sort.Strings(names)
	if got := strings.Join(names, ","); got != "viewer.1.log,viewer.2.log,viewer.log" {
		t.Errorf("files: %s", got)
	}
	for i := range 3 {
		if n := fileLen(t, file.Path(i)); n > 100 {
			t.Errorf("file %d is %d bytes", i, n)
		}
	}
	// 40-byte lines, two per file: the newest in the current file.
	current := mustRead(t, file.Path(0))
	if !strings.HasPrefix(current, "18 ") || !strings.Contains(current, "\n19 ") {
		t.Errorf("current file: %q", current)
	}
	if !strings.HasPrefix(mustRead(t, file.Path(2)), "14 ") {
		t.Errorf("oldest archive: %q", mustRead(t, file.Path(2)))
	}
	if err := file.Close(); err != nil {
		t.Fatal(err)
	}
	if err := os.RemoveAll(dir); err != nil {
		t.Fatal(err)
	}
}

func Test_appends_to_the_existing_file_and_counts_its_size(t *testing.T) {
	dir := tempDir(t)
	file, err := OpenRotating(dir, 100, 2)
	if err != nil {
		t.Fatal(err)
	}
	if err := file.WriteLine(strings.Repeat("a", 59)); err != nil {
		t.Fatal(err)
	}
	file.Close()
	file, err = OpenRotating(dir, 100, 2)
	if err != nil {
		t.Fatal(err)
	}
	if err := file.WriteLine(strings.Repeat("b", 59)); err != nil {
		t.Fatal(err)
	}
	file.Flush()
	if got, want := mustRead(t, file.Path(1)), strings.Repeat("a", 59)+"\n"; got != want {
		t.Errorf("archive: %q, want %q", got, want)
	}
	if got, want := mustRead(t, file.Path(0)), strings.Repeat("b", 59)+"\n"; got != want {
		t.Errorf("current: %q, want %q", got, want)
	}
	file.Close()
	if err := os.RemoveAll(dir); err != nil {
		t.Fatal(err)
	}
}

func Test_a_line_longer_than_the_limit_is_still_written(t *testing.T) {
	dir := tempDir(t)
	file, err := OpenRotating(dir, 10, 2)
	if err != nil {
		t.Fatal(err)
	}
	if err := file.WriteLine(strings.Repeat("z", 50)); err != nil {
		t.Fatal(err)
	}
	file.Flush()
	if n := fileLen(t, file.Path(0)); n != 51 {
		t.Errorf("size %d, want 51", n)
	}
	file.Close()
	if err := os.RemoveAll(dir); err != nil {
		t.Fatal(err)
	}
}
