//go:build !windows

package brand

// WindowIcon is a no-op outside Windows: the app is a Windows app, this only
// lets the package build elsewhere.
type WindowIcon struct{}

// Apply checks the id and does nothing else.
func (*WindowIcon) Apply(hwnd uintptr, id string) error {
	if !Valid(id) {
		return ErrUnknownID
	}
	if hwnd == 0 {
		return ErrNoWindow
	}
	return nil
}
