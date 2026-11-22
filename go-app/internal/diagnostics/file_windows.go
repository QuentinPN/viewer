//go:build windows

package diagnostics

import (
	"os"

	"golang.org/x/sys/windows"
)

// openLogFile opens the log file the way Rust's std does: create if missing, append
// (or truncate), with FILE_SHARE_READ | WRITE | DELETE. Go's os.OpenFile leaves out
// FILE_SHARE_DELETE, which would stop the owner deleting or moving the log from the
// file explorer while the app runs.
func openLogFile(path string, truncate bool) (*os.File, error) {
	name, err := windows.UTF16PtrFromString(path)
	if err != nil {
		return nil, err
	}
	access, disposition := uint32(windows.FILE_APPEND_DATA), uint32(windows.OPEN_ALWAYS)
	if truncate {
		access, disposition = windows.GENERIC_WRITE, windows.CREATE_ALWAYS
	}
	share := uint32(windows.FILE_SHARE_READ | windows.FILE_SHARE_WRITE | windows.FILE_SHARE_DELETE)
	h, err := windows.CreateFile(name, access, share, nil, disposition, windows.FILE_ATTRIBUTE_NORMAL, 0)
	if err != nil {
		return nil, &os.PathError{Op: "open", Path: path, Err: err}
	}
	// os.NewFile takes ownership of the handle: Close closes it. The handle is a
	// synchronous (non-overlapped) file handle, which is what os.File expects.
	return os.NewFile(uintptr(h), path), nil
}
