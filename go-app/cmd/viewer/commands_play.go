package main

import (
	"context"
	"math"

	"viewer/internal/app"
	"viewer/internal/emby"
	"viewer/internal/player"
	"viewer/internal/stremio"
)

// playback is what emby_play and emby_change_quality drive, in the order of
// commands.rs (appPlayback in the app, a recording fake in tests).
type playback interface {
	ResendStop(ctx context.Context)
	PrepareSource(ctx context.Context, itemID string, mediaSourceID *string, resume, forceTranscode bool, prefs emby.TrackPreferences) (emby.PrepareResult, error)
	PrepareAtCap(ctx context.Context, itemID, mediaSourceID string, cap *uint64, startTicks int64, prefs emby.TrackPreferences) (emby.PrepareResult, error)
	Begin(p emby.ActivePlayback)
	Switch(p emby.ActivePlayback)
	// ReportEvent feeds the reporter alone.
	ReportEvent(ev player.PlayerEvent)
	// Playing tells the linked playback (sync) what starts.
	Playing(itemID, mediaSourceID string, direct bool)
	// EndStremio saves and stops following the Stremio playback going on.
	EndStremio()
	Engine() (loader, error)
}

type loader interface {
	Load(request *player.PlaybackRequest, source player.MediaSource) error
}

type appPlayback struct{ a *app.App }

func (p appPlayback) ResendStop(ctx context.Context) { p.a.Reporter.ResendStop(ctx) }
func (p appPlayback) PrepareSource(ctx context.Context, itemID string, mediaSourceID *string, resume, force bool, prefs emby.TrackPreferences) (emby.PrepareResult, error) {
	return p.a.Emby.PrepareSource(ctx, itemID, mediaSourceID, resume, force, prefs)
}
func (p appPlayback) PrepareAtCap(ctx context.Context, itemID, mediaSourceID string, cap *uint64, startTicks int64, prefs emby.TrackPreferences) (emby.PrepareResult, error) {
	return p.a.Emby.PrepareAtCap(ctx, itemID, mediaSourceID, cap, startTicks, prefs)
}
func (p appPlayback) Begin(ap emby.ActivePlayback)      { p.a.Reporter.Begin(ap) }
func (p appPlayback) Switch(ap emby.ActivePlayback)     { p.a.Reporter.Switch(ap) }
func (p appPlayback) ReportEvent(ev player.PlayerEvent) { p.a.Reporter.PlayerEvent(ev) }
func (p appPlayback) SyncEvent(ev player.PlayerEvent)   { p.a.Sync.PlayerEvent(ev) }
func (p appPlayback) Playing(itemID, mediaSourceID string, direct bool) {
	p.a.Sync.Playing(itemID, mediaSourceID, direct)
}
func (p appPlayback) EndStremio() { p.a.StremioResume.End() }
func (p appPlayback) SavedStremio(target string) (stremio.ResumePoint, bool) {
	return p.a.StremioResume.Saved(target)
}
func (p appPlayback) TrackStremio(target string, start *float64) {
	p.a.StremioResume.Begin(target, start)
}
func (p appPlayback) Engine() (loader, error) {
	e, err := p.a.Engine()
	if err != nil {
		return nil, err
	}
	return e, nil
}

// playbackPreferences is `PlaybackPreferences`: Réglages › Sous-titres, kept on
// this PC and sent with each playback. `rename_all = "camelCase",
// default`: a missing field keeps its default, unknown keys are ignored.
type playbackPreferences struct {
	Tracks        emby.TrackPreferences `json:"tracks"`
	SubtitleStyle player.SubtitleStyle  `json:"subtitleStyle"`
}

func readPreferences(args tauriArgs) (playbackPreferences, error) {
	var prefs *playbackPreferences
	if err := args.get("preferences", &prefs); err != nil {
		return playbackPreferences{}, err
	}
	if prefs == nil {
		return playbackPreferences{}, nil
	}
	return *prefs, nil
}

// embyPlay is `emby_play`: PlaybackInfo, choice of the source, then the
// player. The UI gets the method only, never the URL or token. With resume, starts
// where the server says the user stopped; the playback is reported.
func embyPlay(ctx context.Context, p playback, args tauriArgs) (any, error) {
	var itemID string
	var resume bool
	var transcodeFallback *bool
	var mediaSourceID *string
	if err := getAll(args, "itemId", &itemID, "resume", &resume,
		"transcodeFallback", &transcodeFallback, "mediaSourceId", &mediaSourceID); err != nil {
		return nil, err
	}
	prefs, err := readPreferences(args)
	if err != nil {
		return nil, err
	}
	if err := prefs.Tracks.Validate(); err != nil {
		return nil, err
	}
	// A stop report lost earlier goes out before the new playback is negotiated.
	p.ResendStop(ctx)
	prepared, err := p.PrepareSource(ctx, itemID, mediaSourceID, resume,
		transcodeFallback != nil && *transcodeFallback, prefs.Tracks)
	if err != nil {
		return nil, err
	}
	plan := prepared.Plan
	p.Begin(emby.NewActivePlayback(itemID, &plan))
	p.Playing(itemID, plan.MediaSourceID, plan.Method == emby.MethodDirectPlay)
	request := emby.EngineRequest(&plan, prepared.Headers, prefs.SubtitleStyle)
	// Nothing was loaded: drop the announced playback, nothing is reported. The
	// reporter alone gets Shutdown (commands.rs:671): the engine is not torn down.
	fail := func(err error) error {
		p.ReportEvent(player.Shutdown{})
		return emby.NewPlayer(err.Error())
	}
	source, err := player.ParseSource(request.Source)
	if err != nil {
		return nil, fail(err)
	}
	engine, err := p.Engine()
	if err != nil {
		return nil, fail(err)
	}
	p.EndStremio()
	if err := engine.Load(&request, source); err != nil {
		return nil, fail(err)
	}
	return emby.NewPlaybackStart(itemID, resume, &plan), nil
}

// embyChangeQuality is `emby_change_quality`: a new PlaybackInfo for
// the version playing at `cap` (null: « Maximale »), loaded at positionSeconds in
// the same engine. The session goes on: a QualityChange progress, no stop.
func embyChangeQuality(ctx context.Context, p playback, args tauriArgs) (any, error) {
	var itemID, mediaSourceID string
	var capBps *uint64
	var position float64
	if err := getAll(args, "itemId", &itemID, "mediaSourceId", &mediaSourceID,
		"cap", &capBps, "positionSeconds", &position); err != nil {
		return nil, err
	}
	prefs, err := readPreferences(args)
	if err != nil {
		return nil, err
	}
	if math.IsInf(position, 0) || math.IsNaN(position) || position < 0 {
		return nil, emby.NewInvalidResponse("position invalide")
	}
	if err := prefs.Tracks.Validate(); err != nil {
		return nil, err
	}
	startTicks := int64(math.Round(position * 10_000_000))
	prepared, err := p.PrepareAtCap(ctx, itemID, mediaSourceID, capBps, startTicks, prefs.Tracks)
	if err != nil {
		return nil, err
	}
	plan := prepared.Plan
	request := emby.EngineRequest(&plan, prepared.Headers, prefs.SubtitleStyle)
	// Checked before the switch is announced: a bad URL leaves the stream playing.
	source, err := player.ParseSource(request.Source)
	if err != nil {
		return nil, emby.NewPlayer(err.Error())
	}
	engine, err := p.Engine()
	if err != nil {
		return nil, emby.NewPlayer(err.Error())
	}
	p.Switch(emby.NewActivePlayback(itemID, &plan))
	p.EndStremio()
	if err := engine.Load(&request, source); err != nil {
		return nil, emby.NewPlayer(err.Error())
	}
	return emby.NewPlaybackStart(itemID, true, &plan), nil
}
