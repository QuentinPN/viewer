//go:build windows

package brand

import (
	"errors"
	"sync"

	"github.com/wailsapp/wails/v3/pkg/w32"
)

// WindowIcon sets the icon of one window and owns the icons it created.
type WindowIcon struct {
	mu         sync.Mutex
	big, small w32.HICON
}

// Apply sets the big (Alt+Tab, taskbar) and small (title bar) icons of the window
// hwnd to the logo id, then destroys the icons this WindowIcon set before. The
// icon the window had at the start (the exe's resource) is not ours: it is
// replaced, never destroyed.
func (w *WindowIcon) Apply(hwnd uintptr, id string) error {
	png, err := PNG(id)
	if err != nil {
		return err
	}
	if hwnd == 0 {
		return ErrNoWindow
	}
	big, err := w32.CreateLargeHIconFromImage(png)
	if big == 0 {
		return errors.Join(errors.New("icône de fenêtre non créée"), err)
	}
	small, err := w32.CreateSmallHIconFromImage(png)
	if small == 0 {
		w32.DestroyIcon(big)
		return errors.Join(errors.New("icône de fenêtre non créée"), err)
	}

	w.mu.Lock()
	defer w.mu.Unlock()
	h := w32.HWND(hwnd)
	w32.SendMessage(h, w32.WM_SETICON, w32.ICON_BIG, uintptr(big))
	w32.SendMessage(h, w32.WM_SETICON, w32.ICON_SMALL, uintptr(small))
	// The new icons are set: the old ones are no longer in use.
	if w.big != 0 {
		w32.DestroyIcon(w.big)
	}
	if w.small != 0 {
		w32.DestroyIcon(w.small)
	}
	w.big, w.small = big, small
	return nil
}
