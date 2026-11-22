// Package bridge is the single command entry point of the Wails app: the
// frontend calls Bridge.Invoke with a Tauri command name and its arguments,
// and the registry dispatches to the Go handler registered under that name.
// Keeping the Tauri names lets src/lib/native/ stay a thin layer.
package bridge

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"runtime/debug"
	"slices"
	"sync"
)

// Handler runs one command. args is the JSON object the frontend passed to
// invoke ({} when it passed nothing). The result is marshalled to JSON. A
// non-nil error rejects the frontend promise with the value MarshalError
// gives for it.
type Handler func(ctx context.Context, args json.RawMessage) (any, error)

// Emitter sends a named event to the frontend, the counterpart of Tauri's
// app.emit. The payload is what the frontend listener receives.
type Emitter interface {
	Emit(name string, payload any)
}

// Registry maps command names to handlers. Commands are registered at
// startup; lookups may run concurrently from Wails call goroutines.
type Registry struct {
	mu       sync.RWMutex
	handlers map[string]Handler
}

// NewRegistry returns an empty registry.
func NewRegistry() *Registry {
	return &Registry{handlers: make(map[string]Handler)}
}

// Register adds the handler for a command name. Like http.ServeMux.Handle, it
// panics on an empty name, a nil handler or a duplicate name: these are
// programming errors caught at startup, never reachable from the frontend.
func (r *Registry) Register(name string, h Handler) {
	if name == "" || h == nil {
		panic("bridge: Register needs a name and a handler")
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	if _, dup := r.handlers[name]; dup {
		panic("bridge: command registered twice: " + name)
	}
	r.handlers[name] = h
}

// Names lists the registered command names, sorted.
func (r *Registry) Names() []string {
	r.mu.RLock()
	defer r.mu.RUnlock()
	names := make([]string, 0, len(r.handlers))
	for name := range r.handlers {
		names = append(names, name)
	}
	slices.Sort(names)
	return names
}

func (r *Registry) lookup(name string) (Handler, bool) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	h, ok := r.handlers[name]
	return h, ok
}

// Bridge is the Wails service. Its only exported method is Invoke, so it is
// the only method bound to JS (Wails binds every exported method).
type Bridge struct {
	reg *Registry
}

// New returns the service dispatching to reg.
func New(reg *Registry) *Bridge {
	return &Bridge{reg: reg}
}

// Invoke runs the command cmd. Wails injects ctx (cancelled when the JS call
// is cancelled); JS calls it as Call.ByName(InvokeFQN, cmd, args). A handler
// error is returned unchanged: the service's MarshalError turns it into the
// rejection value.
func (b *Bridge) Invoke(ctx context.Context, cmd string, args json.RawMessage) (json.RawMessage, error) {
	h, ok := b.reg.lookup(cmd)
	if !ok {
		return nil, &CommandError{Kind: "unknownCommand", Message: "commande non portée : " + cmd}
	}
	// JS undefined arrives as null or nothing: hand handlers an empty object,
	// as Tauri does for an invoke without arguments.
	if len(args) == 0 || string(args) == "null" {
		args = json.RawMessage("{}")
	}
	res, err := runHandler(ctx, h, args)
	if err != nil {
		return nil, err
	}
	out, err := json.Marshal(res)
	if err != nil {
		return nil, &CommandError{Kind: "internal", Message: fmt.Sprintf("réponse de %s non sérialisable : %v", cmd, err)}
	}
	return out, nil
}

// runHandler calls h and turns a panic into a {kind: "panic"} error: a buggy
// handler must reject its own call, not crash the app.
func runHandler(ctx context.Context, h Handler, args json.RawMessage) (res any, err error) {
	defer func() {
		if p := recover(); p != nil {
			// Logged with its stack: the UI only sees the rejected call.
			slog.Error(fmt.Sprintf("command panicked: %v\n%s", p, debug.Stack()), "target", "panic")
			res, err = nil, &CommandError{Kind: "panic", Message: fmt.Sprint(p)}
		}
	}()
	return h(ctx, args)
}

// InvokeFQN is the name the frontend passes to Call.ByName.
const InvokeFQN = "viewer/internal/bridge.Bridge.Invoke"

// CommandError is a bridge-level failure, shaped like the Rust EmbyError
// ({kind, message}) so the frontend narrows it the same way.
type CommandError struct {
	Kind    string `json:"kind"`
	Message string `json:"message"`
}

func (e *CommandError) Error() string { return e.Kind + ": " + e.Message }

// MarshalJSON keeps the {kind, message} shape (see MarshalError).
func (e *CommandError) MarshalJSON() ([]byte, error) {
	type plain CommandError
	return json.Marshal((*plain)(e))
}

// ErrUnit is the rejection of a Rust Result<_, ()>: the frontend receives
// null (golden errors.other_shapes.json, "unit").
var ErrUnit error = unitError{}

type unitError struct{}

func (unitError) Error() string                { return "unit error" }
func (unitError) MarshalJSON() ([]byte, error) { return []byte("null"), nil }

// MarshalError is the Bridge service's ServiceOptions.MarshalError: it gives
// the JSON value the frontend's promise rejects with, as Tauri rejects with
// the serialized Rust error.
//   - an error implementing json.Marshaler (anywhere in its chain) is that
//     value: {kind, message} for EmbyError, null for ErrUnit, etc.;
//   - any other error is its message as a bare JSON string, the shape of a
//     Rust Result<_, String> (golden errors.other_shapes.json, "string").
//
// Wails puts the result in the `cause` of the RuntimeError it throws in JS;
// src/lib/native/wails.ts rethrows that cause.
func MarshalError(err error) []byte {
	var m json.Marshaler
	if errors.As(err, &m) {
		if b, mErr := m.MarshalJSON(); mErr == nil && json.Valid(b) {
			return b
		}
	}
	b, _ := json.Marshal(err.Error()) // a string always marshals
	return b
}
