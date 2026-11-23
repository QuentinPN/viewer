package stremio

// Bridge commands of Réglages › Stremio. Arguments are validated here; a domain
// failure rejects with the {kind, message} of Error (the shape of the ur_*
// commands), a malformed call with a bare string, as the ur_* commands do. No
// message ever repeats an argument.

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
)

// Handler is the shape of bridge.Handler, without importing the bridge.
type Handler = func(ctx context.Context, args json.RawMessage) (any, error)

// decodeArgs reads the arguments object of cmd into dst, refusing unknown keys.
// The decoder's own text is dropped: it may quote the input.
func decodeArgs(cmd string, raw json.RawMessage, dst any) error {
	dec := json.NewDecoder(bytes.NewReader(raw))
	dec.DisallowUnknownFields()
	trimmed := bytes.TrimSpace(raw)
	if len(trimmed) == 0 || trimmed[0] != '{' || dec.Decode(dst) != nil {
		return fmt.Errorf("invalid args for command `%s`: expected an object with only the known keys", cmd)
	}
	return nil
}

// Handlers are the stremio_* commands, bound to svc:
//   - stremio_status `{}` → Status;
//   - stremio_sign_in `{email, password}` → Status once signed in;
//   - stremio_sign_out `{}` → Status.
func Handlers(svc *Service) map[string]Handler {
	noArgs := func(cmd string, f func(ctx context.Context) (any, error)) Handler {
		return func(ctx context.Context, raw json.RawMessage) (any, error) {
			if err := decodeArgs(cmd, raw, &struct{}{}); err != nil {
				return nil, err
			}
			return f(ctx)
		}
	}
	return map[string]Handler{
		"stremio_status": noArgs("stremio_status", func(ctx context.Context) (any, error) {
			return svc.Status(ctx), nil
		}),
		"stremio_sign_in": func(ctx context.Context, raw json.RawMessage) (any, error) {
			const cmd = "stremio_sign_in"
			var a struct {
				Email    *string `json:"email"`
				Password *string `json:"password"`
			}
			if err := decodeArgs(cmd, raw, &a); err != nil {
				return nil, err
			}
			if a.Email == nil {
				return nil, fmt.Errorf("command %s missing required key email", cmd)
			}
			if a.Password == nil {
				return nil, fmt.Errorf("command %s missing required key password", cmd)
			}
			if err := svc.SignIn(ctx, *a.Email, *a.Password); err != nil {
				return nil, err
			}
			return svc.Status(ctx), nil
		},
		"stremio_sign_out": noArgs("stremio_sign_out", func(ctx context.Context) (any, error) {
			if err := svc.SignOut(); err != nil {
				return nil, err
			}
			return svc.Status(ctx), nil
		}),
	}
}
