package bridge_test

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/wailsapp/wails/v3/pkg/application"

	"viewer/internal/bridge"
)

// signedOut mimics a domain error (the future emby.EmbyError): it serializes
// itself as {kind, message}.
type signedOut struct{}

func (signedOut) Error() string { return "aucune session ouverte" }
func (signedOut) MarshalJSON() ([]byte, error) {
	return json.Marshal(map[string]string{"kind": "notSignedIn", "message": "aucune session ouverte"})
}

func golden(t *testing.T, name string) []byte {
	t.Helper()
	b, err := os.ReadFile(filepath.Join("..", "..", "testdata", "golden", name))
	if err != nil {
		t.Fatal(err)
	}
	return compact(t, b)
}

// goldenField returns one top-level field of a golden object, compacted.
func goldenField(t *testing.T, name, field string) []byte {
	t.Helper()
	var m map[string]json.RawMessage
	if err := json.Unmarshal(golden(t, name), &m); err != nil {
		t.Fatal(err)
	}
	v, ok := m[field]
	if !ok {
		t.Fatalf("%s has no field %q", name, field)
	}
	return compact(t, v)
}

func compact(t *testing.T, b []byte) []byte {
	t.Helper()
	var buf bytes.Buffer
	if err := json.Compact(&buf, b); err != nil {
		t.Fatalf("invalid JSON %q: %v", b, err)
	}
	return buf.Bytes()
}

func newRegistry() *bridge.Registry {
	reg := bridge.NewRegistry()
	reg.Register("echo_args", func(_ context.Context, args json.RawMessage) (any, error) {
		return args, nil
	})
	reg.Register("app_info", func(context.Context, json.RawMessage) (any, error) {
		return map[string]string{"version": "1.5.0"}, nil
	})
	reg.Register("fails_struct", func(context.Context, json.RawMessage) (any, error) {
		return nil, fmt.Errorf("wrapped: %w", signedOut{})
	})
	reg.Register("fails_string", func(context.Context, json.RawMessage) (any, error) {
		return nil, errors.New("message d'erreur")
	})
	reg.Register("fails_unit", func(context.Context, json.RawMessage) (any, error) {
		return nil, bridge.ErrUnit
	})
	reg.Register("bad_result", func(context.Context, json.RawMessage) (any, error) {
		return make(chan int), nil
	})
	reg.Register("needs_ctx", func(ctx context.Context, _ json.RawMessage) (any, error) {
		return ctx.Err() == nil, nil
	})
	return reg
}

func TestInvokeDispatchesByName(t *testing.T) {
	b := bridge.New(newRegistry())
	out, err := b.Invoke(context.Background(), "app_info", nil)
	if err != nil {
		t.Fatal(err)
	}
	if got, want := string(out), `{"version":"1.5.0"}`; got != want {
		t.Fatalf("got %s, want %s", got, want)
	}
}

func TestInvokePassesArgsAndDefaultsToEmptyObject(t *testing.T) {
	b := bridge.New(newRegistry())
	for _, tc := range []struct{ in, want string }{
		{`{"itemId":"42","resume":true}`, `{"itemId":"42","resume":true}`},
		{"", `{}`},
		{"null", `{}`},
	} {
		out, err := b.Invoke(context.Background(), "echo_args", json.RawMessage(tc.in))
		if err != nil {
			t.Fatal(err)
		}
		if string(out) != tc.want {
			t.Errorf("args %q: got %s, want %s", tc.in, out, tc.want)
		}
	}
}

func TestUnknownCommandIsStructured(t *testing.T) {
	b := bridge.New(newRegistry())
	_, err := b.Invoke(context.Background(), "emby_restore", nil)
	var ce *bridge.CommandError
	if !errors.As(err, &ce) || ce.Kind != "unknownCommand" {
		t.Fatalf("got %#v, want CommandError unknownCommand", err)
	}
	want := `{"kind":"unknownCommand","message":"commande non portée : emby_restore"}`
	if got := string(bridge.MarshalError(err)); got != want {
		t.Fatalf("got %s, want %s", got, want)
	}
}

func TestUnserializableResultIsStructured(t *testing.T) {
	b := bridge.New(newRegistry())
	_, err := b.Invoke(context.Background(), "bad_result", nil)
	var ce *bridge.CommandError
	if !errors.As(err, &ce) || ce.Kind != "internal" {
		t.Fatalf("got %#v, want CommandError internal", err)
	}
}

func TestRegisterPanicsOnDuplicateOrEmpty(t *testing.T) {
	cases := map[string]func(r *bridge.Registry){
		"duplicate": func(r *bridge.Registry) {
			h := func(context.Context, json.RawMessage) (any, error) { return nil, nil }
			r.Register("x", h)
			r.Register("x", h)
		},
		"empty name": func(r *bridge.Registry) {
			r.Register("", func(context.Context, json.RawMessage) (any, error) { return nil, nil })
		},
		"nil handler": func(r *bridge.Registry) { r.Register("x", nil) },
	}
	for name, f := range cases {
		t.Run(name, func(t *testing.T) {
			defer func() {
				if recover() == nil {
					t.Fatal("Register did not panic")
				}
			}()
			f(bridge.NewRegistry())
		})
	}
}

func TestMarshalErrorShapesMatchGoldens(t *testing.T) {
	cases := []struct {
		name string
		err  error
		want []byte
	}{
		{"struct error", signedOut{}, golden(t, "errors.service.not_signed_in.json")},
		{"wrapped struct error", fmt.Errorf("ctx: %w", signedOut{}), golden(t, "errors.service.not_signed_in.json")},
		{"string error", errors.New("message d'erreur"), goldenField(t, "errors.other_shapes.json", "string")},
		{"unit error", bridge.ErrUnit, goldenField(t, "errors.other_shapes.json", "unit")},
	}
	for _, tc := range cases {
		if got := bridge.MarshalError(tc.err); !bytes.Equal(got, tc.want) {
			t.Errorf("%s: got %s, want %s", tc.name, got, tc.want)
		}
	}
}

// TestThroughWailsBindings goes through Wails' own binding layer, as a JS
// Call.ByName does: it pins the FQN the frontend uses and the `cause` the
// rejected promise carries (null included: it must not be dropped).
func TestThroughWailsBindings(t *testing.T) {
	// Bindings.Add logs through the global App: create one (never Run), as
	// Wails' own binding benchmarks do.
	_ = application.New(application.Options{})
	bindings := application.NewBindings(nil, nil)
	svc := application.NewServiceWithOptions(bridge.New(newRegistry()), application.ServiceOptions{
		MarshalError: bridge.MarshalError,
	})
	if err := bindings.Add(svc); err != nil {
		t.Fatal(err)
	}
	method := bindings.Get(&application.CallOptions{MethodName: bridge.InvokeFQN})
	if method == nil {
		t.Fatalf("no bound method %s", bridge.InvokeFQN)
	}
	call := func(args ...string) (any, error) {
		raw := make([]json.RawMessage, len(args))
		for i, a := range args {
			raw[i] = json.RawMessage(a)
		}
		return method.Call(context.Background(), raw)
	}

	res, err := call(`"app_info"`, `null`)
	if err != nil {
		t.Fatal(err)
	}
	resJSON, _ := json.Marshal(res)
	if string(resJSON) != `{"version":"1.5.0"}` {
		t.Fatalf("result %s", resJSON)
	}

	if res, err := call(`"needs_ctx"`, `{}`); err != nil || string(res.(json.RawMessage)) != "true" {
		t.Fatalf("ctx not injected: %v %v", res, err)
	}

	for _, tc := range []struct {
		cmd  string
		want []byte
	}{
		{"fails_struct", golden(t, "errors.service.not_signed_in.json")},
		{"fails_string", goldenField(t, "errors.other_shapes.json", "string")},
		{"fails_unit", goldenField(t, "errors.other_shapes.json", "unit")},
		{"not_ported", []byte(`{"kind":"unknownCommand","message":"commande non portée : not_ported"}`)},
	} {
		_, err := call(`"`+tc.cmd+`"`, `{}`)
		var ce *application.CallError
		if !errors.As(err, &ce) || ce.Kind != application.RuntimeError {
			t.Fatalf("%s: got %#v, want RuntimeError CallError", tc.cmd, err)
		}
		wire, _ := json.Marshal(ce)
		var envelope map[string]json.RawMessage
		if err := json.Unmarshal(wire, &envelope); err != nil {
			t.Fatal(err)
		}
		cause, ok := envelope["cause"]
		if !ok {
			t.Fatalf("%s: no cause in %s", tc.cmd, wire)
		}
		if !bytes.Equal(compact(t, cause), tc.want) {
			t.Errorf("%s: cause %s, want %s", tc.cmd, cause, tc.want)
		}
	}
}

func TestHandlerPanicIsStructured(t *testing.T) {
	reg := newRegistry()
	reg.Register("boom", func(context.Context, json.RawMessage) (any, error) { panic("oups") })
	var logged bytes.Buffer
	prev := slog.Default()
	slog.SetDefault(slog.New(slog.NewTextHandler(&logged, nil)))
	defer slog.SetDefault(prev)
	_, err := bridge.New(reg).Invoke(context.Background(), "boom", nil)
	want := `{"kind":"panic","message":"oups"}`
	if err == nil || string(bridge.MarshalError(err)) != want {
		t.Fatalf("got %v, want %s", err, want)
	}
	// The stack reaches the log; the UI only gets the rejected call.
	if !strings.Contains(logged.String(), "command panicked: oups") || !strings.Contains(logged.String(), "target=panic") {
		t.Fatalf("panique non journalisée : %s", logged.String())
	}
}
