package main

import (
	"context"
	"fmt"

	"viewer/internal/app"
	"viewer/internal/bridge"
	"viewer/internal/emby"
	"viewer/internal/segments"
)

// Emby commands of commands.rs (ADR 0004). Results carry user and server
// summaries, never the token.
func registerEmby(reg *bridge.Registry, a *app.App) {
	e := a.Emby
	reg.Register("emby_server_info", noArgs(func(ctx context.Context) (any, error) {
		return result(e.ServerInfo(ctx))
	}))
	reg.Register("emby_account_tracks", noArgs(func(ctx context.Context) (any, error) {
		return result(e.AccountTracks(ctx))
	}))
	reg.Register("emby_probe", arg1("emby_probe", "url", func(ctx context.Context, url string) (any, error) {
		return result(e.Probe(ctx, url))
	}))
	reg.Register("emby_sign_in", withArgs("emby_sign_in", func(ctx context.Context, args tauriArgs) (any, error) {
		var url, username, password string
		if err := getAll(args, "url", &url, "username", &username, "password", &password); err != nil {
			return nil, err
		}
		return result(e.SignIn(ctx, url, username, password))
	}))
	reg.Register("emby_restore", noArgs(func(ctx context.Context) (any, error) {
		return result(e.Restore(ctx))
	}))
	// Sets the open session aside without revoking it (ADR 0012); a linked TV is
	// told `bye stopped` first.
	reg.Register("emby_change_server", noArgs(func(context.Context) (any, error) {
		a.Sync.Stop(app.SyncByeWait)
		return unit(e.ChangeServer())
	}))
	reg.Register("emby_shelf", noArgs(func(context.Context) (any, error) {
		return e.Shelf(), nil
	}))
	// « Oublier ce serveur » (ADR 0103): removed locally at once; the revocations
	// run afterwards, best effort, and never delay the answer.
	reg.Register("emby_forget_server", withArgs("emby_forget_server", func(_ context.Context, args tauriArgs) (any, error) {
		var serverID, userID string
		if err := getAll(args, "serverId", &serverID, "userId", &userID); err != nil {
			return nil, err
		}
		// Read before the Emby service deletes the token with the account (ADR 0125).
		urRevocation := a.Request.RevocationFor(serverID, userID)
		revocation, err := e.ForgetServer(serverID, userID)
		if err != nil {
			return nil, err
		}
		if revocation != nil {
			a.Go(func() { revocation.Send(detached(a)) })
		}
		if urRevocation != nil {
			a.Go(func() { urRevocation.Send(detached(a)) })
		}
		return nil, nil
	}))
	reg.Register("emby_resume_saved", withArgs("emby_resume_saved", func(ctx context.Context, args tauriArgs) (any, error) {
		var serverID, userID string
		if err := getAll(args, "serverId", &serverID, "userId", &userID); err != nil {
			return nil, err
		}
		return result(e.ResumeSaved(ctx, serverID, userID))
	}))
	reg.Register("emby_sign_out", noArgs(func(ctx context.Context) (any, error) {
		a.Sync.Stop(app.SyncByeWait)
		// Read before the Emby service deletes the token with the account (ADR 0125).
		var urRevocation interface{ Send(context.Context) }
		if u := e.CurrentUser(); u != nil {
			if r := a.Request.RevocationFor(u.Server.ID, u.UserID); r != nil {
				urRevocation = r
			}
		}
		// Revoked only once the Emby sign-out succeeded: a failed one keeps the session.
		if err := e.SignOut(ctx); err != nil {
			return nil, err
		}
		if urRevocation != nil {
			a.Go(func() { urRevocation.Send(detached(a)) })
		}
		return nil, nil
	}))
	reg.Register("emby_last_server", noArgs(func(context.Context) (any, error) {
		return e.LastServerURL(), nil
	}))
	reg.Register("emby_libraries", noArgs(func(ctx context.Context) (any, error) {
		return result(e.Libraries(ctx))
	}))
	reg.Register("emby_items", withArgs("emby_items", func(ctx context.Context, args tauriArgs) (any, error) {
		var libraryID string
		var startIndex, limit uint32
		var options *emby.ItemsOptions
		if err := getAll(args, "libraryId", &libraryID, "startIndex", &startIndex, "limit", &limit, "options", &options); err != nil {
			return nil, err
		}
		return result(e.Items(ctx, libraryID, startIndex, limit, orDefault(options)))
	}))
	// Titles of a library grid in order with their sort name (letter rail, ADR 0101).
	reg.Register("emby_sort_names", withArgs("emby_sort_names", func(ctx context.Context, args tauriArgs) (any, error) {
		var libraryID string
		var options *emby.ItemsOptions
		if err := getAll(args, "libraryId", &libraryID, "options", &options); err != nil {
			return nil, err
		}
		return result(e.SortNames(ctx, libraryID, orDefault(options)))
	}))
	// One random title among those the grid shows (ADR 0101).
	reg.Register("emby_random_item", withArgs("emby_random_item", func(ctx context.Context, args tauriArgs) (any, error) {
		var libraryID string
		var options *emby.ItemsOptions
		if err := getAll(args, "libraryId", &libraryID, "options", &options); err != nil {
			return nil, err
		}
		return result(e.RandomItem(ctx, libraryID, orDefault(options)))
	}))
	reg.Register("emby_production_years", arg1("emby_production_years", "libraryId", func(ctx context.Context, id string) (any, error) {
		return result(e.ProductionYears(ctx, id))
	}))
	// Artwork cache figures (ADR 0019, 0021): null when the app has no cache folder.
	reg.Register("artwork_cache_stats", noArgs(func(context.Context) (any, error) {
		if a.Artwork == nil {
			return nil, nil
		}
		return a.Artwork.Stats(), nil
	}))
	reg.Register("artwork_cache_clear", noArgs(func(context.Context) (any, error) {
		if a.Artwork == nil {
			return nil, nil
		}
		cleared := a.Artwork.Clear()
		a.Log().Info(fmt.Sprintf("artwork cache cleared: %d images, %d bytes", cleared.Entries, cleared.Bytes))
		return cleared, nil
	}))
	// Bitrate caps of the current server (ADR 0037).
	reg.Register("emby_playback_quality", noArgs(func(context.Context) (any, error) {
		return result(e.PlaybackQuality())
	}))
	// Sets both caps of the current server; null is « Maximale ».
	reg.Register("emby_set_playback_quality", withArgs("emby_set_playback_quality", func(_ context.Context, args tauriArgs) (any, error) {
		var caps emby.QualityCaps
		if err := getAll(args, "local", &caps.Local, "remote", &caps.Remote); err != nil {
			return nil, err
		}
		return result(e.SetPlaybackQuality(caps))
	}))
	reg.Register("emby_genres", arg1("emby_genres", "libraryId", func(ctx context.Context, id string) (any, error) {
		return result(e.Genres(ctx, id))
	}))
	reg.Register("emby_continue_watching", noArgs(func(ctx context.Context) (any, error) {
		return result(e.ContinueWatching(ctx))
	}))
	reg.Register("emby_next_up", noArgs(func(ctx context.Context) (any, error) {
		return result(e.NextUp(ctx))
	}))
	reg.Register("emby_search", arg1("emby_search", "query", func(ctx context.Context, q string) (any, error) {
		return result(e.Search(ctx, q))
	}))
	reg.Register("emby_latest", arg1("emby_latest", "libraryId", func(ctx context.Context, id string) (any, error) {
		return result(e.Latest(ctx, id))
	}))
	reg.Register("emby_hero", noArgs(func(ctx context.Context) (any, error) {
		return result(e.Hero(ctx))
	}))
	reg.Register("emby_favorites", noArgs(func(ctx context.Context) (any, error) {
		return result(e.Favorites(ctx))
	}))
	reg.Register("emby_set_favorite", withArgs("emby_set_favorite", func(ctx context.Context, args tauriArgs) (any, error) {
		var itemID string
		var favorite bool
		if err := getAll(args, "itemId", &itemID, "favorite", &favorite); err != nil {
			return nil, err
		}
		return result(e.SetFavorite(ctx, itemID, favorite))
	}))
	// Marks an item played or unplayed (ADR 0096); the new state goes to every
	// view on the playback-stopped event, as after a playback, and is returned.
	reg.Register("emby_set_played", withArgs("emby_set_played", func(ctx context.Context, args tauriArgs) (any, error) {
		var itemID string
		var played bool
		if err := getAll(args, "itemId", &itemID, "played", &played); err != nil {
			return nil, err
		}
		state, err := e.SetPlayed(ctx, itemID, played)
		if err != nil {
			return nil, err
		}
		a.Emit(emby.PlaybackStoppedEvent, state)
		return state, nil
	}))
	// Takes a film or episode out of « Reprendre » (ADR 0096); announced the same way.
	reg.Register("emby_hide_from_resume", arg1("emby_hide_from_resume", "itemId", func(ctx context.Context, itemID string) (any, error) {
		state, err := e.HideFromResume(ctx, itemID)
		if err != nil {
			return nil, err
		}
		a.Emit(emby.PlaybackStoppedEvent, state)
		return state, nil
	}))
	reg.Register("emby_series_next_episode", arg1("emby_series_next_episode", "seriesId", func(ctx context.Context, id string) (any, error) {
		return result(e.SeriesNextEpisode(ctx, id))
	}))
	reg.Register("emby_playlists", noArgs(func(ctx context.Context) (any, error) {
		return result(e.Playlists(ctx))
	}))
	reg.Register("emby_home_playlists", noArgs(func(ctx context.Context) (any, error) {
		return result(e.HomePlaylists(ctx))
	}))
	reg.Register("emby_playlist_items", arg1("emby_playlist_items", "playlistId", func(ctx context.Context, id string) (any, error) {
		return result(e.PlaylistItems(ctx, id))
	}))
	reg.Register("emby_add_to_playlist", withArgs("emby_add_to_playlist", func(ctx context.Context, args tauriArgs) (any, error) {
		var playlistID, itemID string
		if err := getAll(args, "playlistId", &playlistID, "itemId", &itemID); err != nil {
			return nil, err
		}
		return result(e.AddToPlaylist(ctx, playlistID, itemID))
	}))
	reg.Register("emby_create_playlist", withArgs("emby_create_playlist", func(ctx context.Context, args tauriArgs) (any, error) {
		var name, itemID string
		if err := getAll(args, "name", &name, "itemId", &itemID); err != nil {
			return nil, err
		}
		return result(e.CreatePlaylist(ctx, name, itemID))
	}))
	reg.Register("emby_item_detail", arg1("emby_item_detail", "itemId", func(ctx context.Context, id string) (any, error) {
		return result(e.ItemDetail(ctx, id))
	}))
	// Intro and credits of an item (ADR 0030). `external: false` keeps everything
	// on the Emby server; a failing external source only gives fewer segments.
	reg.Register("emby_segments", withArgs("emby_segments", func(ctx context.Context, args tauriArgs) (any, error) {
		var itemID string
		var external bool
		if err := getAll(args, "itemId", &itemID, "external", &external); err != nil {
			return nil, err
		}
		key, err := e.SegmentsKey(itemID, external)
		if err != nil {
			return nil, err
		}
		if found, ok := a.Segments.Cached(key); ok {
			return emby.List[segments.Segment](found), nil
		}
		markers, query, err := e.SegmentQuery(ctx, itemID)
		if err != nil {
			return nil, err
		}
		return emby.List[segments.Segment](a.Segments.Lookup(ctx, key, markers, query, external)), nil
	}))
	reg.Register("emby_chapters", arg1("emby_chapters", "itemId", func(ctx context.Context, id string) (any, error) {
		return result(e.Chapters(ctx, id))
	}))
	reg.Register("emby_seasons", arg1("emby_seasons", "seriesId", func(ctx context.Context, id string) (any, error) {
		return result(e.Seasons(ctx, id))
	}))
	// Titles of a person, collection, genre or studio (ADR 0121).
	reg.Register("emby_titles", arg1("emby_titles", "source", func(ctx context.Context, source emby.TitlesSource) (any, error) {
		return result(e.Titles(ctx, source))
	}))
	// Id of the first trailer file of a film or series, to play (ADR 0121).
	reg.Register("emby_local_trailer", arg1("emby_local_trailer", "itemId", func(ctx context.Context, id string) (any, error) {
		return result(e.LocalTrailer(ctx, id))
	}))
	reg.Register("emby_episodes", withArgs("emby_episodes", func(ctx context.Context, args tauriArgs) (any, error) {
		var seriesID, seasonID string
		var startIndex, limit uint32
		if err := getAll(args, "seriesId", &seriesID, "seasonId", &seasonID, "startIndex", &startIndex, "limit", &limit); err != nil {
			return nil, err
		}
		return result(e.Episodes(ctx, seriesID, seasonID, startIndex, limit))
	}))
	reg.Register("emby_play", withArgs("emby_play", func(ctx context.Context, args tauriArgs) (any, error) {
		return embyPlay(ctx, appPlayback{a}, args)
	}))
	reg.Register("emby_change_quality", withArgs("emby_change_quality", func(ctx context.Context, args tauriArgs) (any, error) {
		return embyChangeQuality(ctx, appPlayback{a}, args)
	}))
}

// getAll reads pairs of key and destination, in order, stopping at the first error
// (Tauri reads the arguments in the order of the Rust signature).
func getAll(args tauriArgs, pairs ...any) error {
	for i := 0; i+1 < len(pairs); i += 2 {
		if err := args.get(pairs[i].(string), pairs[i+1]); err != nil {
			return err
		}
	}
	return nil
}

func orDefault[T any](p *T) T {
	if p == nil {
		var zero T
		return zero
	}
	return *p
}

// detached is the context of a fire-and-forget task (`tauri::async_runtime::spawn`):
// it outlives the command, and ends with the app.
func detached(a *app.App) context.Context { return a.Context() }
