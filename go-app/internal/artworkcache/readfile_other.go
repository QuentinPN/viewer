//go:build !windows

package artworkcache

import "os"

func readFile(path string) ([]byte, error) { return os.ReadFile(path) }
