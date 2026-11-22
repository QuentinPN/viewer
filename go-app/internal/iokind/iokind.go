// Package iokind renders an I/O error as the text of Rust's io::ErrorKind Display
// ("entity not found", "permission denied"…), which the Rust app logs and returns
// instead of the OS message.
package iokind

import (
	"errors"
	"io/fs"
	"os/exec"
	"syscall"
)

// Text is the io::ErrorKind text matching err, or "other error".
func Text(err error) string {
	switch {
	case errors.Is(err, fs.ErrNotExist), errors.Is(err, exec.ErrNotFound):
		return "entity not found"
	case errors.Is(err, fs.ErrPermission):
		return "permission denied"
	case errors.Is(err, fs.ErrExist):
		return "entity already exists"
	case errors.Is(err, syscall.ENOTDIR):
		return "not a directory"
	default:
		return "other error"
	}
}
