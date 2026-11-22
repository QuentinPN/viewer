package app

import "strings"

// BrowserArgsEnv carries the WebView2 switches of the scripted UI tests (ADR
// 0006, scripts/e2e/app.mjs). The Tauri build reads WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS,
// but Wails blanks that variable in a package init (webviewloader
// preventEnvAndRegistryOverrides), before main runs: the debug build reads this
// one instead and passes it through the Wails options.
const BrowserArgsEnv = "VIEWER_E2E_BROWSER_ARGS"

// BrowserArgs are the WebView2 switches for application.WindowsOptions.
type BrowserArgs struct {
	// DisabledFeatures joins Wails' own --disable-features list: Chromium keeps
	// only the last --disable-features switch, so a second one would drop it.
	DisabledFeatures []string
	// Args are the other switches, as given.
	Args []string
}

// BrowserArgsFor reads BrowserArgsEnv's value in debug builds only (a DevTools
// port must never open in a release build): nothing when debug is false.
func BrowserArgsFor(debug bool, value string) BrowserArgs {
	var b BrowserArgs
	if !debug {
		return b
	}
	for _, arg := range strings.Fields(value) {
		if list, ok := strings.CutPrefix(arg, "--disable-features="); ok {
			for _, f := range strings.Split(list, ",") {
				if f != "" {
					b.DisabledFeatures = append(b.DisabledFeatures, f)
				}
			}
			continue
		}
		b.Args = append(b.Args, arg)
	}
	return b
}
