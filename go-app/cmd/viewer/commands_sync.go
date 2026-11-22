package main

import (
	"context"

	"viewer/internal/app"
	"viewer/internal/bridge"
	"viewer/internal/devsync"
	"viewer/internal/emby"
)

// Linked playback (ADR 0124) and session socket (ADR 0110) commands of commands.rs.
func registerSync(reg *bridge.Registry, a *app.App) {
	// Viewer TVs open on the account the playback can be sent to.
	reg.Register("sync_devices", noArgs(func(ctx context.Context) (any, error) {
		return result(devsync.Devices(ctx, a.Emby))
	}))
	// Sends the current playback to a TV of sync_devices; the outcome arrives as
	// sync://status and sync://notice events.
	reg.Register("sync_invite", arg1("sync_invite", "sessionId", func(ctx context.Context, sessionID string) (any, error) {
		devices, err := devsync.Devices(ctx, a.Emby)
		if err != nil {
			return nil, err
		}
		for _, d := range devices {
			if d.SessionID == sessionID {
				return unit(a.Sync.Invite(ctx, devsync.Peer(d)))
			}
		}
		return nil, emby.NewSync("cette TV n'est plus ouverte")
	}))
	// Ends the link; the playback goes on here.
	reg.Register("sync_unlink", noArgs(func(context.Context) (any, error) {
		a.Sync.Unlink()
		return nil, nil
	}))
	reg.Register("sync_status", noArgs(func(ctx context.Context) (any, error) {
		return a.Sync.Status(ctx), nil
	}))
	// Listens for titles added on the server while the home is shown (ADR 0110);
	// each settled burst emits emby://library-changed.
	reg.Register("emby_library_events_start", noArgs(func(context.Context) (any, error) {
		a.Library.Start()
		return nil, nil
	}))
	// The home is no longer shown.
	reg.Register("emby_library_events_stop", noArgs(func(context.Context) (any, error) {
		a.Library.Stop()
		return nil, nil
	}))
}
