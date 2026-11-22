package diagnostics

import (
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
)

// RotatingFile is a log file archived by size: when a line would take the current
// file beyond maxBytes, `name.log` becomes `name.1.log`, which becomes `name.2.log`,
// and so on; the oldest beyond kept files is deleted. Not safe for concurrent use:
// the log handler serializes calls.
type RotatingFile struct {
	dir      string
	maxBytes int64
	kept     int
	file     *os.File
	size     int64
}

// OpenRotating opens (appending to) the current file in dir, creating dir.
func OpenRotating(dir string, maxBytes int64, kept int) (*RotatingFile, error) {
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return nil, err
	}
	f := &RotatingFile{dir: dir, maxBytes: maxBytes, kept: max(kept, 1)}
	if err := f.reopen(false); err != nil {
		return nil, err
	}
	return f, nil
}

// Path is the file at index: 0 is the current one, n the n-th archive.
func (f *RotatingFile) Path(index int) string {
	if index == 0 {
		return filepath.Join(f.dir, LogFile)
	}
	return filepath.Join(f.dir, fmt.Sprintf("viewer.%d.log", index))
}

func (f *RotatingFile) reopen(truncate bool) error {
	file, err := openLogFile(f.Path(0), truncate)
	if err != nil {
		return err
	}
	info, err := file.Stat()
	if err != nil {
		file.Close()
		return err
	}
	f.size = info.Size()
	f.file = file
	return nil
}

func (f *RotatingFile) rotate() error {
	// Windows cannot rename an open file.
	if f.file != nil {
		f.file.Close()
		f.file = nil
	}
	if err := os.Remove(f.Path(f.kept - 1)); err != nil && !errors.Is(err, fs.ErrNotExist) {
		return err
	}
	for n := f.kept - 2; n >= 0; n-- {
		if err := os.Rename(f.Path(n), f.Path(n+1)); err != nil && !errors.Is(err, fs.ErrNotExist) {
			return err
		}
	}
	return f.reopen(true)
}

// WriteLine appends line and a newline, archiving the current file first when needed.
func (f *RotatingFile) WriteLine(line string) error {
	n := int64(len(line)) + 1
	if f.size > 0 && f.size+n > f.maxBytes && f.rotate() != nil && f.file == nil {
		// Keep logging into the current file rather than losing records.
		if err := f.reopen(false); err != nil {
			return err
		}
	}
	if f.file == nil {
		return errors.New("log file closed")
	}
	// One write, so a record is never split by a crash between line and newline.
	if _, err := f.file.WriteString(line + "\n"); err != nil {
		return err
	}
	f.size += n
	return nil
}

// Flush is a no-op for the OS: every write goes straight to the file, like Rust's
// unbuffered `File`. It exists for symmetry with the Rust API.
func (f *RotatingFile) Flush() error { return nil }

// Close closes the current file (Rust: drop).
func (f *RotatingFile) Close() error {
	if f.file == nil {
		return nil
	}
	err := f.file.Close()
	f.file = nil
	return err
}
