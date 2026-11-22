package main

import (
	"errors"
	"log/slog"
	"net/http"
	"strings"

	"viewer/internal/app"
	"viewer/internal/emby"
	"viewer/internal/request"
)

// The two artwork protocols of lib.rs: `emby-img` and `requestimg`.
// Only validated paths are served, fetched natively with the session's
// auth, so the token never reaches the WebView.
//
// Wails v3.1.0 only routes `http://wails.localhost` to Go (webview_window_windows.go
// processRequest), so Tauri's `http://emby-img.localhost` and
// `http://requestimg.localhost` never reach this app. The handlers are mounted on the
// app's own origin under /emby-img/ and /requestimg/, and useAppImageOrigins makes
// internal/emby and internal/request write their ImageRefs there.
const (
	// appOrigin is the origin Wails serves the frontend from (assetserver_windows.go).
	appOrigin       = "http://wails.localhost"
	embyImagePrefix = "/" + emby.ImageScheme
	urImagePrefix   = "/" + request.Scheme
	// immutable: the URL carries the image tag (Emby) or the TMDB file name, so a
	// given URL never changes content.
	cacheForever = "max-age=31536000, immutable"
)

// useAppImageOrigins points every artwork URL built from now on at the two paths
// withProtocols serves. main calls it once, before the services start.
func useAppImageOrigins() {
	emby.SetImageOrigin(appOrigin + embyImagePrefix)
	request.SetImageOrigin(appOrigin + urImagePrefix)
}

// withProtocols serves the two artwork paths and passes anything else to next.
func withProtocols(a *app.App, next http.Handler) http.Handler {
	emby := embyImage(a)
	ur := urImage(a)
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case strings.HasPrefix(r.URL.Path, embyImagePrefix+"/"):
			emby.ServeHTTP(w, r)
		case strings.HasPrefix(r.URL.Path, urImagePrefix+"/"):
			ur.ServeHTTP(w, r)
		default:
			next.ServeHTTP(w, r)
		}
	})
}

func serveImage(w http.ResponseWriter, contentType string, data []byte) {
	w.Header().Set("Content-Type", contentType)
	w.Header().Set("Cache-Control", cacheForever)
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(data)
}

// embyImage is lib.rs `emby_image`. The path is the protocol's own
// (`/{serverId}/{itemId}/{Type}`), the query `tag` and `maxWidth`.
func embyImage(a *app.App) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var query *string
		if r.URL.RawQuery != "" || r.URL.ForceQuery {
			q := r.URL.RawQuery
			query = &q
		}
		req, ok := emby.ParseImageRequest(strings.TrimPrefix(r.URL.EscapedPath(), embyImagePrefix), query)
		if !ok {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		contentType, data, err := a.Emby.Image(r.Context(), req)
		if err != nil {
			k, _ := emby.KindOf(err)
			slog.Debug("artwork " + req.ItemID + " not served: " + string(k))
			w.WriteHeader(embyImageStatus(err))
			return
		}
		serveImage(w, contentType, data)
	})
}

func embyImageStatus(err error) int {
	switch {
	case errors.Is(err, emby.ErrNotSignedIn), errors.Is(err, emby.ErrSessionExpired):
		return http.StatusUnauthorized
	case errors.Is(err, emby.ErrNotFound):
		return http.StatusNotFound
	case errors.Is(err, emby.ErrForbidden):
		// An image of another server than the open session's.
		return http.StatusForbidden
	default:
		return http.StatusBadGateway
	}
}

// urImage is lib.rs `request_image`: only `/{size}/{file}` paths, fetched
// with the Request Bearer.
func urImage(a *app.App) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		req, ok := request.ParseUrImage(strings.TrimPrefix(r.URL.EscapedPath(), urImagePrefix))
		if !ok {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		id, err := a.Identity()
		var contentType string
		var data []byte
		if err == nil {
			contentType, data, err = a.Request.Image(r.Context(), id, req)
		}
		if err != nil {
			status, kind := urImageStatus(err)
			slog.Debug("Request artwork not served: " + kind)
			w.WriteHeader(status)
			return
		}
		serveImage(w, contentType, data)
	})
}

// urImageStatus maps a RequestError, or the Emby identity error `identity`
// turns into one (NotSignedIn is embySignedOut, the rest credentialStore).
func urImageStatus(err error) (int, string) {
	var ur request.RequestError
	if !errors.As(err, &ur) {
		if errors.Is(err, emby.ErrNotSignedIn) {
			return http.StatusUnauthorized, string(request.KindEmbySignedOut)
		}
		return http.StatusBadGateway, string(request.KindCredentialStore)
	}
	switch ur.Kind {
	case request.KindEmbySignedOut, request.KindSignedOut, request.KindRejected:
		return http.StatusUnauthorized, string(ur.Kind)
	case request.KindNotFound:
		return http.StatusNotFound, string(ur.Kind)
	case request.KindNotConfigured, request.KindForbidden:
		return http.StatusForbidden, string(ur.Kind)
	case request.KindRateLimited:
		return http.StatusTooManyRequests, string(ur.Kind)
	default:
		return http.StatusBadGateway, string(ur.Kind)
	}
}
