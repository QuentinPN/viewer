//go:build windows

package updater

import "golang.org/x/sys/windows"

// shellExecute is the plugin's ShellExecuteW(NULL, "open", file, parameters,
// NULL, SW_SHOW). x/sys returns an error when the result is 32 or less.
func shellExecute(path, args string) error {
	verb, err := windows.UTF16PtrFromString("open")
	if err != nil {
		return err
	}
	file, err := windows.UTF16PtrFromString(path)
	if err != nil {
		return err
	}
	params, err := windows.UTF16PtrFromString(args)
	if err != nil {
		return err
	}
	return windows.ShellExecute(0, verb, file, params, nil, windows.SW_SHOW)
}
