package main

import (
	"context"
	"maps"
	"slices"

	"viewer/internal/emby"
	"viewer/internal/player"
)

// stremioPlayback is what stremio_play drives (appPlayback in the app, the
// recording fake in tests): no PlaybackInfo, Begin or Playing, Emby never hears of
// an addon's stream.
type stremioPlayback interface {
	ResendStop(ctx context.Context)
	// ReportEvent feeds the reporter alone.
	ReportEvent(ev player.PlayerEvent)
	// SyncEvent feeds the linked playback alone.
	SyncEvent(ev player.PlayerEvent)
	Engine() (loader, error)
}

// stremioOpenFailed is the detail of every stremio_play failure: an engine's text
// may quote the link, a signed debrid URL.
const stremioOpenFailed = "le lien de cette source ne s'ouvre pas"

// stremioPlay is `stremio_play` once the stream is looked up: link and headers
// come from the addon (Go side), itemID and title from the Emby item of the list.
//
// The reporter and the linked playback follow one Emby playback, announced by
// emby_play: a Shutdown fed to them alone (not to the UI, the engine stays open)
// ends the one still going or announced, reported as stopped like any end, and
// ResendStop waits until that report went out. The addon's file then loads with no
// playback announced, so its events report nothing to Emby and offer nothing to a
// TV. Checked first, a bad link or a missing engine leaves that playback alone.
func stremioPlay(ctx context.Context, p stremioPlayback, itemID string, title *emby.PlaybackTitle, link string, headers map[string]string) (any, error) {
	source, err := player.ParseSource(link)
	if err != nil || source.Kind != player.SourceHTTP {
		return nil, emby.NewPlayer(stremioOpenFailed)
	}
	engine, err := p.Engine()
	if err != nil {
		return nil, emby.NewPlayer(stremioOpenFailed)
	}
	p.ReportEvent(player.Shutdown{})
	p.SyncEvent(player.Shutdown{})
	p.ResendStop(ctx)
	// A debrid link answers with a redirect; headers must not follow it elsewhere.
	request := player.PlaybackRequest{Source: link, FollowRedirects: len(headers) == 0}
	for _, name := range slices.Sorted(maps.Keys(headers)) {
		request.HTTPHeaders = append(request.HTTPHeaders, player.Header{name, headers[name]})
	}
	if err := engine.Load(&request, source); err != nil {
		return nil, emby.NewPlayer(stremioOpenFailed)
	}
	return emby.PlaybackStart{
		ItemID: itemID,
		Method: emby.MethodDirectPlay,
		Title:  title,
		Origin: emby.OriginStremio,
	}, nil
}
