//go:build !windows

package updater

import "errors"

// shellExecute: the NSIS installer only runs on Windows.
func shellExecute(string, string) error {
	return errors.New("installer launch is only supported on Windows")
}
