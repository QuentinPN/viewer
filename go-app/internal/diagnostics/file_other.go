//go:build !windows

package diagnostics

import "os"

func openLogFile(path string, truncate bool) (*os.File, error) {
	flags := os.O_CREATE | os.O_WRONLY
	if truncate {
		flags |= os.O_TRUNC
	} else {
		flags |= os.O_APPEND
	}
	return os.OpenFile(path, flags, 0o644)
}
