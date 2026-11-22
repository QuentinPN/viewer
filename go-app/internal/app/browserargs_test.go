package app

import (
	"reflect"
	"testing"
)

// Wails blanks WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS in a package init
// (webviewloader preventEnvAndRegistryOverrides), so the scripted UI tests
// (scripts/e2e, ADR 0006) get their DevTools port through VIEWER_E2E_BROWSER_ARGS
// and the Wails options, in debug builds only. --disable-features joins Wails' own list: Chromium keeps
// only the last --disable-features switch.
func Test_go_debug_build_forwards_the_webview2_arguments(t *testing.T) {
	got := BrowserArgsFor(true, " --remote-debugging-port=9340  --disable-features=CalculateNativeWinOcclusion,Foo --disable-renderer-backgrounding ")
	want := BrowserArgs{
		DisabledFeatures: []string{"CalculateNativeWinOcclusion", "Foo"},
		Args:             []string{"--remote-debugging-port=9340", "--disable-renderer-backgrounding"},
	}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("got %#v, want %#v", got, want)
	}
	if BrowserArgsEnv != "VIEWER_E2E_BROWSER_ARGS" {
		t.Fatalf("variable : %s", BrowserArgsEnv)
	}
}

func Test_go_release_build_ignores_the_webview2_arguments(t *testing.T) {
	if got := BrowserArgsFor(false, "--remote-debugging-port=9340 --disable-features=X"); !reflect.DeepEqual(got, BrowserArgs{}) {
		t.Fatalf("release : %#v", got)
	}
	if got := BrowserArgsFor(true, "   "); !reflect.DeepEqual(got, BrowserArgs{}) {
		t.Fatalf("vide : %#v", got)
	}
}
