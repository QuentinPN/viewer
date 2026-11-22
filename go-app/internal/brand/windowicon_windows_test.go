//go:build windows

package brand

import (
	"runtime"
	"testing"

	"github.com/wailsapp/wails/v3/pkg/w32"
	"golang.org/x/sys/windows"
)

// A hidden window of the system class STATIC, made and queried on one locked
// thread: SendMessage to a window of the calling thread runs its procedure at
// once, no message pump needed.
func hiddenWindow(t *testing.T) w32.HWND {
	t.Helper()
	runtime.LockOSThread()
	t.Cleanup(runtime.UnlockOSThread)
	class, _ := windows.UTF16PtrFromString("STATIC")
	name, _ := windows.UTF16PtrFromString("brand test")
	hwnd := w32.CreateWindowEx(0, class, name, 0, 0, 0, 16, 16, 0, 0, 0, nil)
	if hwnd == 0 {
		t.Fatal("fenêtre non créée")
	}
	t.Cleanup(func() { w32.DestroyWindow(hwnd) })
	return hwnd
}

func icons(hwnd w32.HWND) (big, small uintptr) {
	return w32.SendMessage(hwnd, w32.WM_GETICON, w32.ICON_BIG, 0),
		w32.SendMessage(hwnd, w32.WM_GETICON, w32.ICON_SMALL, 0)
}

func Test_apply_sets_big_and_small_icons(t *testing.T) {
	hwnd := hiddenWindow(t)
	var w WindowIcon

	if err := w.Apply(uintptr(hwnd), "default"); err != nil {
		t.Fatal(err)
	}
	big1, small1 := icons(hwnd)
	if big1 == 0 || small1 == 0 {
		t.Fatalf("WM_GETICON : grande %#x, petite %#x", big1, small1)
	}
	if big1 != uintptr(w.big) || small1 != uintptr(w.small) {
		t.Errorf("icônes de la fenêtre %#x/%#x, créées %#x/%#x", big1, small1, w.big, w.small)
	}

	if err := w.Apply(uintptr(hwnd), "christmas"); err != nil {
		t.Fatal(err)
	}
	big2, small2 := icons(hwnd)
	if big2 == 0 || small2 == 0 {
		t.Fatalf("WM_GETICON : grande %#x, petite %#x", big2, small2)
	}
	if big2 == big1 || small2 == small1 {
		t.Errorf("icônes inchangées : %#x/%#x puis %#x/%#x", big1, small1, big2, small2)
	}

	// The icons of the previous logo are destroyed: no handle is leaked per switch.
	if w32.DestroyIcon(w32.HICON(big1)) {
		t.Error("l'ancienne grande icône existait encore")
	}
	if w32.DestroyIcon(w32.HICON(small1)) {
		t.Error("l'ancienne petite icône existait encore")
	}
}

func Test_apply_unknown_id_keeps_the_icons(t *testing.T) {
	hwnd := hiddenWindow(t)
	var w WindowIcon
	if err := w.Apply(uintptr(hwnd), "default"); err != nil {
		t.Fatal(err)
	}
	big, small := icons(hwnd)
	if err := w.Apply(uintptr(hwnd), "nope"); err == nil {
		t.Fatal("id inconnu accepté")
	}
	if b, s := icons(hwnd); b != big || s != small {
		t.Errorf("icônes changées par un id refusé : %#x/%#x puis %#x/%#x", big, small, b, s)
	}
}
