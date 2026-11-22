package updater

import (
	"log/slog"
	"os"
	"path/filepath"
	"strings"
)

// InstallError is a failure of app_update_install: a French sentence for the
// UI, serialized as a bare string like Rust's Result<(), String>.
type InstallError string

func (e InstallError) Error() string { return string(e) }

// The two messages of app_update_install.
const (
	ErrNotReady     InstallError = "Aucune mise à jour n'est prête."
	ErrInstallStart InstallError = "L'installation n'a pas pu démarrer."
)

// Install is app_update_install: it writes the verified installer to a new
// temporary folder, launches it with the quiet NSIS arguments, then quits; the
// installer relaunches the app. It returns only on failure.
//
// As in Rust, the slot stays locked through the launch and the exit, so a
// second call cannot start a second installer. The process must be gone before
// the installer copies its files: Quit defaults to os.Exit(0).
func (u *Updater) Install() error {
	u.mu.Lock()
	defer u.mu.Unlock()
	p, ok := u.slot.Payload()
	if !ok {
		return ErrNotReady
	}
	if err := u.launch(p); err != nil {
		slog.Warn("update install failed: " + Category(err))
		return ErrInstallStart
	}
	quit := u.cfg.Quit
	if quit == nil {
		quit = func() { os.Exit(0) }
	}
	quit()
	return nil
}

// isExe is infer::app::is_exe: the MZ header of a Windows executable.
func isExe(b []byte) bool { return len(b) > 1 && b[0] == 0x4D && b[1] == 0x5A }

// launch is Update::install: the bytes are verified again (cheap, and what the
// plugin does), then written and launched.
func (u *Updater) launch(p payload) error {
	if err := Verify(p.bytes, p.release.version, p.release.signature, u.cfg.PublicKey); err != nil {
		return err
	}
	if !isExe(p.bytes) {
		return newError(KindInvalidUpdaterFormat, nil)
	}
	path, err := u.writeInstaller(p)
	if err != nil {
		return newError(KindIo, err)
	}
	launch := u.cfg.Launch
	if launch == nil {
		launch = shellExecute
	}
	if err := launch(path, u.installerParameters()); err != nil {
		return newError(KindIo, err)
	}
	return nil
}

// writeInstaller is write_to_temp: `Viewer-<version>-updater-*` in the
// temporary folder, holding `Viewer-<version>-installer.exe`. Neither is
// removed: the process exits while the installer runs, as with the plugin.
func (u *Updater) writeInstaller(p payload) (string, error) {
	dir, err := os.MkdirTemp(u.cfg.TempDir, "Viewer-"+p.release.version+"-updater-")
	if err != nil {
		return "", err
	}
	path := filepath.Join(dir, "Viewer-"+p.release.version+"-installer.exe")
	if err := os.WriteFile(path, p.bytes, 0o600); err != nil {
		return "", err
	}
	return path, nil
}

// installerParameters is updater_parameters for NSIS: the quiet arguments
// (ending with /ARGS), then the app's own launch arguments, escaped, so the
// relaunched app gets them back.
func (u *Updater) installerParameters() string {
	args := append([]string(nil), u.cfg.InstallerArgs...)
	for _, a := range u.cfg.AppArgs {
		args = append(args, escapeNSISArg(a))
	}
	return strings.Join(args, " ")
}

// escapeNSISArg is escape_nsis_current_exe_arg (plugin 2.14.1, adapted from
// the std Windows argument quoting): quoted when empty or holding a space, a tab
// or a `/` (so NSIS does not read it as one of its options), with the
// backslashes before a quote doubled plus one, and before the closing quote
// doubled.
func escapeNSISArg(arg string) string {
	quote := arg == "" || strings.ContainsAny(arg, " \t/")
	var b strings.Builder
	if quote {
		b.WriteByte('"')
	}
	backslashes := 0
	for _, r := range arg {
		if r == '\\' {
			backslashes++
		} else {
			if r == '"' {
				b.WriteString(strings.Repeat("\\", backslashes+1))
			}
			backslashes = 0
		}
		b.WriteRune(r)
	}
	if quote {
		b.WriteString(strings.Repeat("\\", backslashes))
		b.WriteByte('"')
	}
	return b.String()
}
