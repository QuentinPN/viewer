package updater

import (
	"fmt"
	"testing"
)

// Signed version (the place of the plugin's verify_signed_version), target
// fallback, strict entries, app arguments and the re-verification at install.

func Test_go_version_swap_is_rejected(t *testing.T) {
	f := newFakeServer(t)
	// The installer of 1.3.0, validly signed for 1.3.0, advertised as 1.4.0.
	f.setManifest(func(base string) (int, string) {
		return 200, fmt.Sprintf(`{"version":"1.4.0","platforms":{"windows-x86_64":{"url":%q,"ed25519":%q}}}`,
			base+"/setup.exe", f.sign("1.3.0", f.installer))
	})
	u := f.updater(nil)
	if got := u.CheckOnce(ctx(t), true); got != Failed("signature") {
		t.Errorf("got %+v", got)
	}
	if _, ok := u.Pending(); ok {
		t.Error("a swapped version must not be kept")
	}
}

func Test_go_old_installer_replayed_as_newer_is_rejected(t *testing.T) {
	f := newFakeServer(t)
	old := []byte("MZ installer of 1.1.0")
	f.installer = old
	// An authentic 1.1.0 installer with its own valid signature, replayed over
	// plain http as 1.3.0 to an app running 1.2.0: a downgrade.
	f.setManifest(func(base string) (int, string) {
		return 200, fmt.Sprintf(`{"version":"1.3.0","platforms":{"windows-x86_64":{"url":%q,"ed25519":%q}}}`,
			base+"/setup.exe", f.sign("1.1.0", old))
	})
	if got := f.updater(nil).CheckOnce(ctx(t), true); got != Failed("signature") {
		t.Errorf("got %+v", got)
	}
}

func Test_go_nsis_entry_without_ed25519_falls_back(t *testing.T) {
	f := newFakeServer(t)
	f.setManifest(func(base string) (int, string) {
		return 200, fmt.Sprintf(`{"version":"1.3.0","platforms":{
			"windows-x86_64-nsis":{"url":"http://127.0.0.1:1/tauri-only","signature":"minisign"},
			"windows-x86_64":{"url":%q,"signature":"minisign","ed25519":%q}}}`,
			base+"/setup.exe", f.sign("1.3.0", f.installer))
	})
	if got := f.updater(nil).CheckOnce(ctx(t), true); got.Status != StatusReady {
		t.Errorf("got %+v", got)
	}
}

func Test_go_every_entry_is_validated(t *testing.T) {
	for name, entry := range map[string]string{
		"relative url":   `{"url":"setup.exe","signature":"x"}`,
		"bad url":        `{"url":"http://[::1","signature":"x"}`,
		"no url":         `{"signature":"x","ed25519":"x"}`,
		"no signature":   `{"url":"http://h/setup.exe"}`,
		"url not string": `{"url":3,"signature":"x"}`,
	} {
		for _, version := range []string{"1.3.0", "1.2.0"} { // newer, and not newer
			t.Run(name+" "+version, func(t *testing.T) {
				f := newFakeServer(t)
				f.setManifest(func(base string) (int, string) {
					return 200, fmt.Sprintf(`{"version":%q,"platforms":{
						"darwin-aarch64":%s,
						"windows-x86_64":{"url":%q,"ed25519":%q}}}`,
						version, entry, base+"/setup.exe", f.sign(version, f.installer))
				})
				if got := f.updater(nil).CheckOnce(ctx(t), true); got != Failed("manifest") {
					t.Errorf("got %+v", got)
				}
			})
		}
	}
	f := newFakeServer(t)
	f.setManifest(func(string) (int, string) { return 200, `{"version":"1.3.0","url":"http://h/setup.exe"}` })
	if got := f.updater(nil).CheckOnce(ctx(t), true); got != Failed("manifest") {
		t.Errorf("dynamic without signature: %+v", got)
	}
}

func Test_go_installer_gets_the_app_arguments(t *testing.T) {
	f := newFakeServer(t)
	u := f.updater(nil)
	u.cfg.AppArgs = []string{"--flag", "some space", "/dev"}
	if u.CheckOnce(ctx(t), true).Status != StatusReady {
		t.Fatal("no update ready")
	}
	var args string
	u.cfg.Launch = func(_, a string) error { args = a; return nil }
	u.cfg.Quit = func() {}
	if err := u.Install(); err != nil {
		t.Fatal(err)
	}
	if want := `/S /UPDATE /R /ARGS --flag "some space" "/dev"`; args != want {
		t.Errorf("args %s, want %s", args, want)
	}
}

func Test_go_install_verifies_again(t *testing.T) {
	f := newFakeServer(t)
	u := f.updater(nil)
	if u.CheckOnce(ctx(t), true).Status != StatusReady {
		t.Fatal("no update ready")
	}
	u.mu.Lock()
	p, _ := u.slot.Payload()
	tampered := append([]byte(nil), p.bytes...)
	tampered[len(tampered)-1] ^= 1
	u.slot.Store(u.slot.info, payload{release: p.release, bytes: tampered})
	u.mu.Unlock()
	launched := false
	u.cfg.Launch = func(string, string) error { launched = true; return nil }
	if err := u.Install(); err != ErrInstallStart {
		t.Errorf("install: %v", err)
	}
	if launched {
		t.Error("bytes changed after the download must not be launched")
	}
}
