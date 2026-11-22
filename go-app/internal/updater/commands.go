package updater

import (
	"context"
	"encoding/json"
)

// The three commands of updater.rs, as bridge.Handler values (same names,
// no argument, same result JSON). Not wired here: the app-shell registers them
// (MAP.md, "Unité updater").

// CheckHandler is app_update_check: release builds download like the
// background loop, debug builds only report a newer version. The result is a
// CheckOutcome, never an error.
func CheckHandler(u *Updater, debugBuild bool) func(ctx context.Context, args json.RawMessage) (any, error) {
	return func(ctx context.Context, _ json.RawMessage) (any, error) {
		return u.CheckOnce(ctx, !debugBuild), nil
	}
}

// PendingHandler is app_update_pending: the downloaded UpdateInfo, or null.
func PendingHandler(u *Updater) func(ctx context.Context, args json.RawMessage) (any, error) {
	return func(context.Context, json.RawMessage) (any, error) {
		info, ok := u.Pending()
		if !ok {
			return nil, nil
		}
		return info, nil
	}
}

// InstallHandler is app_update_install: runs the installer and quits; the error
// is a bare string (ErrNotReady or ErrInstallStart). Success is null.
func InstallHandler(u *Updater) func(ctx context.Context, args json.RawMessage) (any, error) {
	return func(context.Context, json.RawMessage) (any, error) {
		if err := u.Install(); err != nil {
			return nil, err
		}
		return nil, nil
	}
}
