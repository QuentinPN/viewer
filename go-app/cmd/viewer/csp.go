package main

import "net/http"

// contentSecurityPolicy is src-tauri/tauri.conf.json app.security.csp, plus
// 'self' in connect-src: the Wails runtime calls its own origin
// (http://wails.localhost/wails/runtime), where Tauri's runtime used ipc:.
const contentSecurityPolicy = "default-src 'self'; img-src 'self' data: blob: http://emby-img.localhost http://requestimg.localhost; style-src 'self' 'unsafe-inline'; connect-src 'self' ipc: http://ipc.localhost; object-src 'none'; base-uri 'none'; form-action 'none'"

// withCSP is the asset server middleware serving the CSP on every response.
func withCSP(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Security-Policy", contentSecurityPolicy)
		next.ServeHTTP(w, r)
	})
}
