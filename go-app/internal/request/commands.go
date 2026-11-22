package request

// Commands of the « Demander » tab, port of
// src-tauri/src/request/commands.rs. Arguments are typed and validated here, as
// Tauri does before the Rust body runs; models come back with their ImageRefs
// filled. Each handler has the signature of bridge.Handler and keeps its Tauri name.

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"strconv"
	"strings"
)

// queryMax is the longest search accepted by the server.
const queryMax = 100

// Handler is the shape of bridge.Handler, without importing the bridge.
type Handler = func(ctx context.Context, args json.RawMessage) (any, error)

// IdentitySource is `identity(&EmbyService)`: the server id, user id and token of
// the open Emby session (`EmbyService::exchange_identity`). Its EmbyError is mapped
// like `From<EmbyError>`: NotSignedIn becomes embySignedOut.
type IdentitySource func() (Identity, error)

// BrowseFilters are the filters of « Parcourir » (`GET /api/discover/{type}`): a
// page plus the same filters as a Découvrir row's `seeAll`, so « Tout voir » keeps
// every one.
type BrowseFilters struct {
	Page *uint32 `json:"page"`
	SeeAllFilters
}

// Query is `BrowseFilters::query`.
func (b BrowseFilters) Query() []Param {
	f := b.SeeAllFilters
	page := uint32(1)
	if b.Page != nil {
		page = max(*b.Page, 1)
	}
	q := []Param{{"page", strconv.FormatUint(uint64(page), 10)}}
	if f.Genres != nil && len(*f.Genres) > 0 {
		ids := make([]string, len(*f.Genres))
		for i, g := range *f.Genres {
			ids[i] = strconv.FormatInt(g, 10)
		}
		q = append(q, Param{"genres", strings.Join(ids, ",")})
	}
	i32 := func(v *int32) *int64 {
		if v == nil {
			return nil
		}
		n := int64(*v)
		return &n
	}
	numbers := []struct {
		key string
		v   *int64
	}{
		{"yearFrom", i32(f.YearFrom)},
		{"yearTo", i32(f.YearTo)},
		{"rating", i32(f.Rating)},
		{"runtimeMin", i32(f.RuntimeMin)},
		{"runtimeMax", i32(f.RuntimeMax)},
		{"provider", f.Provider},
		{"company", f.Company},
		{"network", f.Network},
	}
	for _, n := range numbers {
		if n.v != nil {
			q = append(q, Param{n.key, strconv.FormatInt(*n.v, 10)})
		}
	}
	if f.Language != nil {
		q = append(q, Param{"language", *f.Language})
	}
	if f.Sort != nil {
		q = append(q, Param{"sort", string(*f.Sort)})
	}
	return q
}

// RequestsQuery is `requests_query`: `?status=a,b`, several statuses in one
// comma-separated value, never repeated.
func RequestsQuery(status []RequestStatus, page uint32) []Param {
	q := []Param{{"mine", "true"}, {"page", strconv.FormatUint(uint64(max(page, 1)), 10)}}
	if len(status) > 0 {
		names := make([]string, len(status))
		for i, s := range status {
			names[i] = string(s)
		}
		q = append(q, Param{"status", strings.Join(names, ",")})
	}
	return q
}

// PathSegment is `path_segment`: an opaque id as one path segment, every byte but
// the unreserved ones percent-encoded, so `/`, `..` or `?` cannot reach another
// endpoint.
func PathSegment(id string) string {
	var b strings.Builder
	for i := 0; i < len(id); i++ {
		c := id[i]
		switch {
		case (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') ||
			c == '-' || c == '_' || c == '~':
			b.WriteByte(c)
		default:
			b.WriteByte('%')
			b.WriteByte(hexDigits[c>>4])
			b.WriteByte(hexDigits[c&0xf])
		}
	}
	return b.String()
}

// args reads the arguments of a command the way Tauri does: an object, each named
// argument decoded with serde's rules (a missing or null `Option` is None, a missing
// required one is refused). The error is a bare string, as Tauri's.
type args struct {
	cmd    string
	fields map[string]json.RawMessage
}

func readArgs(cmd string, raw json.RawMessage) (args, error) {
	a := args{cmd: cmd}
	if err := json.Unmarshal(raw, &a.fields); err != nil || a.fields == nil {
		return a, fmt.Errorf("invalid args for command `%s`: expected an object", cmd)
	}
	return a, nil
}

// arg decodes the argument key into dst. A missing key decodes as null, so a
// pointer gets nil and anything else is refused.
func (a args) arg(key string, dst any) error {
	raw, ok := a.fields[key]
	if !ok {
		raw = json.RawMessage("null")
	}
	if err := decodeStrict(raw, dst); err != nil {
		if !ok {
			return fmt.Errorf("command %s missing required key %s", a.cmd, key)
		}
		return fmt.Errorf("invalid args `%s` for command `%s`: %v", key, a.cmd, err)
	}
	return nil
}

// Handlers are the 16 `ur_*` commands of commands.rs, by Tauri name, bound to svc
// and to the Emby session given by identity.
func Handlers(svc *Service, identity IdentitySource) map[string]Handler {
	id := func() (Identity, error) {
		i, err := identity()
		if err != nil {
			return Identity{}, fromEmby(err)
		}
		return i, nil
	}
	// noArgs wraps a command without argument; like Tauri, extra keys are ignored.
	noArgs := func(cmd string, f func(ctx context.Context) (any, error)) Handler {
		return func(ctx context.Context, raw json.RawMessage) (any, error) {
			if _, err := readArgs(cmd, raw); err != nil {
				return nil, err
			}
			return f(ctx)
		}
	}
	withArgs := func(cmd string, f func(ctx context.Context, a args) (any, error)) Handler {
		return func(ctx context.Context, raw json.RawMessage) (any, error) {
			a, err := readArgs(cmd, raw)
			if err != nil {
				return nil, err
			}
			return f(ctx, a)
		}
	}
	return map[string]Handler{
		"ur_status": noArgs("ur_status", func(ctx context.Context) (any, error) {
			i, err := id()
			if err != nil {
				return nil, err
			}
			return svc.Status(ctx, i), nil
		}),
		"ur_set_address": withArgs("ur_set_address", func(ctx context.Context, a args) (any, error) {
			var address string
			if err := a.arg("address", &address); err != nil {
				return nil, err
			}
			i, err := id()
			if err != nil {
				return nil, err
			}
			if err := svc.SetAddress(address); err != nil {
				return nil, err
			}
			return svc.Status(ctx, i), nil
		}),
		"ur_sign_in": noArgs("ur_sign_in", func(ctx context.Context) (any, error) {
			i, err := id()
			if err != nil {
				return nil, err
			}
			if err := svc.SignIn(i); err != nil {
				return nil, err
			}
			return svc.Status(ctx, i), nil
		}),
		"ur_sign_out": noArgs("ur_sign_out", func(ctx context.Context) (any, error) {
			i, err := id()
			if err != nil {
				return nil, err
			}
			return nil, svc.SignOut(ctx, i)
		}),
		"ur_discover": noArgs("ur_discover", func(ctx context.Context) (any, error) {
			i, err := id()
			if err != nil {
				return nil, err
			}
			r, err := ServiceGet[DiscoverResponse](ctx, svc, i, "/api/discover", nil)
			if err != nil {
				return nil, err
			}
			return decorated(r), nil
		}),
		"ur_browse": withArgs("ur_browse", func(ctx context.Context, a args) (any, error) {
			var mediaType MediaType
			var filters BrowseFilters
			if err := a.arg("mediaType", &mediaType); err != nil {
				return nil, err
			}
			if err := a.arg("filters", &filters); err != nil {
				return nil, err
			}
			i, err := id()
			if err != nil {
				return nil, err
			}
			r, err := ServiceGet[PagedMedia](ctx, svc, i, "/api/discover/"+string(mediaType), filters.Query())
			if err != nil {
				return nil, err
			}
			return decorated(r), nil
		}),
		"ur_genres": withArgs("ur_genres", func(ctx context.Context, a args) (any, error) {
			var mediaType MediaType
			if err := a.arg("mediaType", &mediaType); err != nil {
				return nil, err
			}
			i, err := id()
			if err != nil {
				return nil, err
			}
			list, err := ServiceGet[GenreList](ctx, svc, i, "/api/genres/"+string(mediaType), nil)
			if err != nil {
				return nil, err
			}
			return list.Genres, nil
		}),
		"ur_search": withArgs("ur_search", func(ctx context.Context, a args) (any, error) {
			var query string
			var page *uint32
			if err := a.arg("query", &query); err != nil {
				return nil, err
			}
			if err := a.arg("page", &page); err != nil {
				return nil, err
			}
			q := []rune(strings.TrimSpace(query))
			if len(q) > queryMax {
				q = q[:queryMax]
			}
			if len(q) == 0 {
				return MediaSearchResults{Page: 1, Results: []MediaSummary{}, People: []SearchPerson{}}, nil
			}
			p := uint32(1)
			if page != nil {
				p = max(*page, 1)
			}
			i, err := id()
			if err != nil {
				return nil, err
			}
			params := []Param{{"q", string(q)}, {"page", strconv.FormatUint(uint64(p), 10)}}
			r, err := ServiceGet[MediaSearchResults](ctx, svc, i, "/api/search", params)
			if err != nil {
				return nil, err
			}
			return decorated(r), nil
		}),
		"ur_media": withArgs("ur_media", func(ctx context.Context, a args) (any, error) {
			var mediaType MediaType
			var tmdbID uint64
			if err := a.arg("mediaType", &mediaType); err != nil {
				return nil, err
			}
			if err := a.arg("tmdbId", &tmdbID); err != nil {
				return nil, err
			}
			i, err := id()
			if err != nil {
				return nil, err
			}
			path := "/api/media/" + string(mediaType) + "/" + strconv.FormatUint(tmdbID, 10)
			r, err := ServiceGet[MediaDetails](ctx, svc, i, path, nil)
			if err != nil {
				return nil, err
			}
			return decorated(r), nil
		}),
		"ur_quota": noArgs("ur_quota", func(ctx context.Context) (any, error) {
			i, err := id()
			if err != nil {
				return nil, err
			}
			r, err := ServiceGet[RequestQuota](ctx, svc, i, "/api/requests/quota", nil)
			if err != nil {
				return nil, err
			}
			return r, nil
		}),
		"ur_request": withArgs("ur_request", func(ctx context.Context, a args) (any, error) {
			var request NewRequest
			if err := a.arg("request", &request); err != nil {
				return nil, err
			}
			i, err := id()
			if err != nil {
				return nil, err
			}
			r, err := ServiceSend[RequestDetail](ctx, svc, i, http.MethodPost, "/api/requests", request)
			if err != nil {
				return nil, err
			}
			return decorated(r), nil
		}),
		"ur_my_requests": withArgs("ur_my_requests", func(ctx context.Context, a args) (any, error) {
			var status *[]RequestStatus
			var page uint32
			if err := a.arg("status", &status); err != nil {
				return nil, err
			}
			if err := a.arg("page", &page); err != nil {
				return nil, err
			}
			i, err := id()
			if err != nil {
				return nil, err
			}
			var list []RequestStatus
			if status != nil {
				list = *status
			}
			r, err := ServiceGet[RequestPage](ctx, svc, i, "/api/requests", RequestsQuery(list, page))
			if err != nil {
				return nil, err
			}
			return decorated(r), nil
		}),
		"ur_request_detail": withArgs("ur_request_detail", func(ctx context.Context, a args) (any, error) {
			var reqID uint64
			if err := a.arg("id", &reqID); err != nil {
				return nil, err
			}
			i, err := id()
			if err != nil {
				return nil, err
			}
			r, err := ServiceGet[RequestDetail](ctx, svc, i, "/api/requests/"+strconv.FormatUint(reqID, 10), nil)
			if err != nil {
				return nil, err
			}
			return decorated(r), nil
		}),
		"ur_cancel": withArgs("ur_cancel", func(ctx context.Context, a args) (any, error) {
			var reqID uint64
			if err := a.arg("id", &reqID); err != nil {
				return nil, err
			}
			i, err := id()
			if err != nil {
				return nil, err
			}
			path := "/api/requests/" + strconv.FormatUint(reqID, 10) + "/cancel"
			r, err := ServiceSend[RequestDetail](ctx, svc, i, http.MethodPost, path, nil)
			if err != nil {
				return nil, err
			}
			return decorated(r), nil
		}),
		"ur_sessions": noArgs("ur_sessions", func(ctx context.Context) (any, error) {
			i, err := id()
			if err != nil {
				return nil, err
			}
			list, err := ServiceGet[SessionList](ctx, svc, i, "/api/auth/sessions", nil)
			if err != nil {
				return nil, err
			}
			return list.Sessions, nil
		}),
		"ur_revoke_session": withArgs("ur_revoke_session", func(ctx context.Context, a args) (any, error) {
			var sessionID string
			if err := a.arg("id", &sessionID); err != nil {
				return nil, err
			}
			i, err := id()
			if err != nil {
				return nil, err
			}
			return nil, svc.SendEmpty(ctx, i, http.MethodDelete, "/api/auth/sessions/"+PathSegment(sessionID))
		}),
	}
}
