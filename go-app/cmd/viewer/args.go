package main

import (
	"context"
	"encoding/json"
	"fmt"

	"viewer/internal/bridge"
	"viewer/internal/strictjson"
)

// tauriArgs reads the arguments of a command the way Tauri does: an object whose
// camelCase keys are decoded one by one with serde's rules (a missing or null
// `Option` is None, a missing required one is refused). Errors are bare strings
// with Tauri's texts, as Tauri rejects bad arguments before the Rust body runs.
type tauriArgs struct {
	cmd    string
	fields map[string]json.RawMessage
}

// argRules are serde's: exact key case, null only for an Option (a pointer).
var argRules = strictjson.Rules{Tag: "serde"}

func readArgs(cmd string, raw json.RawMessage) (tauriArgs, error) {
	a := tauriArgs{cmd: cmd}
	if err := json.Unmarshal(raw, &a.fields); err != nil || a.fields == nil {
		return a, fmt.Errorf("invalid args for command `%s`: expected an object", cmd)
	}
	return a, nil
}

// get decodes the argument key into dst. A missing key decodes as null, so a
// pointer (an Option) gets nil and anything else is refused.
func (a tauriArgs) get(key string, dst any) error {
	raw, ok := a.fields[key]
	if !ok {
		raw = json.RawMessage("null")
	}
	if err := argRules.Decode(raw, dst); err != nil {
		if !ok {
			return fmt.Errorf("command %s missing required key %s", a.cmd, key)
		}
		return fmt.Errorf("invalid args `%s` for command `%s`: %v", key, a.cmd, err)
	}
	return nil
}

// noArgs is a command without argument: like Tauri, its arguments are not read.
func noArgs(f func(ctx context.Context) (any, error)) bridge.Handler {
	return func(ctx context.Context, _ json.RawMessage) (any, error) { return f(ctx) }
}

// withArgs is a command with arguments, read before f runs.
func withArgs(cmd string, f func(ctx context.Context, a tauriArgs) (any, error)) bridge.Handler {
	return func(ctx context.Context, raw json.RawMessage) (any, error) {
		a, err := readArgs(cmd, raw)
		if err != nil {
			return nil, err
		}
		return f(ctx, a)
	}
}

// arg1 reads one argument then runs f: the common shape `(state, key: T)`.
func arg1[T any](cmd, key string, f func(ctx context.Context, v T) (any, error)) bridge.Handler {
	return withArgs(cmd, func(ctx context.Context, a tauriArgs) (any, error) {
		var v T
		if err := a.get(key, &v); err != nil {
			return nil, err
		}
		return f(ctx, v)
	})
}

// unit is the result of a command returning `()` or `Result<(), E>`: JSON null.
func unit(err error) (any, error) {
	if err != nil {
		return nil, err
	}
	return nil, nil
}

// result is a command's `Result<T, E>`: v on success, the error alone otherwise.
func result[T any](v T, err error) (any, error) {
	if err != nil {
		return nil, err
	}
	return v, nil
}
