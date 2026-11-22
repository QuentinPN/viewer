package sysinfo

import (
	"context"
	"encoding/json"
)

// SystemReportHandler is the `system_report` command (no argument). Rust runs it
// on a blocking thread; Wails calls handlers on their own goroutine, so the probe
// runs on the caller's. It has the signature of bridge.Handler. debugBuild says
// whether this is the separate debug build (ADR 0105).
func SystemReportHandler(debugBuild bool) func(ctx context.Context, args json.RawMessage) (any, error) {
	return func(context.Context, json.RawMessage) (any, error) {
		return Read(debugBuild), nil
	}
}
