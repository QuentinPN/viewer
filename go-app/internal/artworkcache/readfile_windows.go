//go:build windows

package artworkcache

import (
	"io"
	"os"

	"golang.org/x/sys/windows"
)

// readFile reads a whole file like os.ReadFile, but opens it with FILE_SHARE_DELETE
// as Rust's std does. Go's os.Open does not, and an image being read would then
// make a concurrent rename-over (Put) or removal (eviction, Clear) fail with a
// sharing violation.
func readFile(path string) ([]byte, error) {
	p, err := windows.UTF16PtrFromString(path)
	if err != nil {
		return nil, err
	}
	h, err := windows.CreateFile(p, windows.GENERIC_READ,
		windows.FILE_SHARE_READ|windows.FILE_SHARE_WRITE|windows.FILE_SHARE_DELETE,
		nil, windows.OPEN_EXISTING, windows.FILE_ATTRIBUTE_NORMAL, 0)
	if err != nil {
		return nil, &os.PathError{Op: "open", Path: path, Err: err}
	}
	// NewFile takes ownership of the handle: Close below releases it.
	f := os.NewFile(uintptr(h), path)
	defer f.Close()
	return io.ReadAll(f)
}
