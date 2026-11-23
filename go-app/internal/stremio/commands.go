package stremio

// Bridge commands of Réglages › Stremio and « Autres sources ». Arguments are validated here; a domain
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

// requireID refuses a missing or empty id argument.
func requireID(cmd, key string, v *string) error {
	if v == nil || *v == "" {
		return fmt.Errorf("command %s missing required key %s", cmd, key)
	}
	return nil
}

// Handlers are the stremio_* commands but stremio_play (PlayHandler), bound to svc
// and to the Emby items read by resolve:
//   - stremio_status `{}` → Status;
//   - stremio_sign_in `{email, password}` → Status once signed in;
//   - stremio_sign_out `{}` → Status;
//   - stremio_sources `{itemId}` (Emby item) or `{external: {imdb, name, season?,
//     episode?}}` (title outside Emby), exactly one → Sources;
//   - stremio_addon_streams `{query, addonId}` → AddonStreams.
func Handlers(svc *Service, resolve ItemResolver) map[string]Handler {
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
		"stremio_sources": func(ctx context.Context, raw json.RawMessage) (any, error) {
			const cmd = "stremio_sources"
			var a struct {
				ItemID   *string `json:"itemId"`
				External *struct {
					Imdb    *string `json:"imdb"`
					Name    *string `json:"name"`
					Season  *int64  `json:"season"`
					Episode *int64  `json:"episode"`
				} `json:"external"`
			}
			if err := decodeArgs(cmd, raw, &a); err != nil {
				return nil, err
			}
			if (a.ItemID == nil) == (a.External == nil) {
				return nil, fmt.Errorf("invalid args for command `%s`: expected exactly one of itemId, external", cmd)
			}
			if e := a.External; e != nil {
				if e.Imdb == nil {
					return nil, fmt.Errorf("command %s missing required key external.imdb", cmd)
				}
				if e.Name == nil {
					return nil, fmt.Errorf("command %s missing required key external.name", cmd)
				}
				return svc.ExternalSources(ctx, External{Imdb: *e.Imdb, Name: *e.Name, Season: e.Season, Episode: e.Episode})
			}
			if err := requireID(cmd, "itemId", a.ItemID); err != nil {
				return nil, err
			}
			return svc.Sources(ctx, *a.ItemID, resolve)
		},
		"stremio_addon_streams": func(ctx context.Context, raw json.RawMessage) (any, error) {
			const cmd = "stremio_addon_streams"
			var a struct {
				Query   *string `json:"query"`
				AddonID *string `json:"addonId"`
			}
			if err := decodeArgs(cmd, raw, &a); err != nil {
				return nil, err
			}
			if err := requireID(cmd, "query", a.Query); err != nil {
				return nil, err
			}
			if a.AddonID == nil {
				return nil, fmt.Errorf("command %s missing required key addonId", cmd)
			}
			return svc.AddonStreams(ctx, *a.Query, *a.AddonID)
		},
	}
}

// PlayHandler is stremio_play `{streamId, resume?}`: the stream is looked up here
// (ErrExpired when unknown) and play opens it, where its content stopped last time
// with resume (default false). The URL never comes from the UI, so this command
// exists in release builds too.
func PlayHandler(svc *Service, play func(ctx context.Context, s Selected, resume bool) (any, error)) Handler {
	return func(ctx context.Context, raw json.RawMessage) (any, error) {
		const cmd = "stremio_play"
		var a struct {
			StreamID *string `json:"streamId"`
			Resume   *bool   `json:"resume"`
		}
		if err := decodeArgs(cmd, raw, &a); err != nil {
			return nil, err
		}
		if err := requireID(cmd, "streamId", a.StreamID); err != nil {
			return nil, err
		}
		s, err := svc.Selected(*a.StreamID)
		if err != nil {
			return nil, err
		}
		return play(ctx, s, a.Resume != nil && *a.Resume)
	}
}
