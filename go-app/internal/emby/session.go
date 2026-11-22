package emby

// Sign-in lifecycle, port of emby/session.rs: probe, sign in, restore at startup,
// sign out, the shelf of set-aside sessions, and every library, home and playback
// call of the open session. Non-secret session metadata lives in `session.json`; the
// token lives in a CredentialStore.
//
// Concurrency, as in Rust: `mu` guards the open session (`current`), `epoch` is bumped
// under it by every sign-in, sign-out, server change and resume, so a restore started
// before commits nothing (ADR 0012). `fileMu` guards session.json. A lock is never
// held across a network call. `tokio::join!` sites run their calls in goroutines and
// wait for all of them before handling the results in Rust's order.

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"log/slog"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

// SessionFile is the contents of `session.json`. Contains no secret. Same keys,
// order and rules as serde: `deviceId` required, the Options written null, `saved`
// and `reauth` default when missing.
type SessionFile struct {
	DeviceID string `json:"deviceId" emby:"required"`
	// LastServerURL prefills the connection form after sign-out or an expired session.
	LastServerURL *string        `json:"lastServerUrl"`
	Session       *SessionRecord `json:"session"`
	// Saved: sessions set aside by "Changer de serveur", newest first (ADR 0012).
	// Their tokens stay in the credential store.
	Saved List[SessionRecord] `json:"saved"`
	// Reauth: non-secret hint of the last session the server rejected: prefills the
	// reauthentication screen, even after a restart.
	Reauth *SessionRecord `json:"reauth"`
}

// SessionRecord is one session of session.json.
type SessionRecord struct {
	ServerURL     string `json:"serverUrl" emby:"required"`
	ServerID      string `json:"serverId" emby:"required"`
	ServerName    string `json:"serverName" emby:"required"`
	ServerVersion string `json:"serverVersion" emby:"required"`
	UserID        string `json:"userId" emby:"required"`
	UserName      string `json:"userName" emby:"required"`
	// Rejected: the server rejected this session before it was put on the shelf: its
	// tile says "Mot de passe requis" (ADR 0103). Left out when false.
	Rejected bool `json:"rejected,omitempty"`
}

func recordOf(user *SignedInUser) SessionRecord {
	return SessionRecord{
		ServerURL: user.Server.URL, ServerID: user.Server.ID, ServerName: user.Server.Name,
		ServerVersion: user.Server.Version, UserID: user.UserID, UserName: user.UserName,
	}
}

func (r *SessionRecord) is(serverID, userID string) bool {
	return r.ServerID == serverID && r.UserID == userID
}

func (r *SessionRecord) sameAccount(o *SessionRecord) bool {
	return r.ServerID == o.ServerID && r.UserID == o.UserID
}

func (r *SessionRecord) account() string { return CredentialAccount(r.ServerID, r.UserID) }

func (r *SessionRecord) summary() SavedServer {
	return SavedServer{
		URL: r.ServerURL, ID: r.ServerID, Name: r.ServerName, Version: r.ServerVersion,
		Encrypted: strings.HasPrefix(r.ServerURL, "https://"), UserID: r.UserID, UserName: r.UserName,
	}
}

func cloneRecord(r *SessionRecord) *SessionRecord {
	if r == nil {
		return nil
	}
	c := *r
	return &c
}

func (f *SessionFile) clone() SessionFile {
	return SessionFile{
		DeviceID: f.DeviceID, LastServerURL: cloneStr(f.LastServerURL), Session: cloneRecord(f.Session),
		Saved: slices.Clone(f.Saved), Reauth: cloneRecord(f.Reauth),
	}
}

// newDeviceID is `Uuid::new_v4().simple()`: 32 lowercase hex digits.
func newDeviceID() string {
	var b [16]byte
	_, _ = rand.Read(b[:]) // crypto/rand.Read never fails (Go 1.24+)
	b[6] = b[6]&0x0f | 0x40
	b[8] = b[8]&0x3f | 0x80
	return hex.EncodeToString(b[:])
}

// LoadSessionFile is `SessionFile::load_or_init`: the file, or an empty one when
// missing or unreadable (serde's rules, decodeStrict), with a fresh device id when
// it has none.
func LoadSessionFile(path string) SessionFile {
	var file SessionFile
	if b, err := os.ReadFile(path); err == nil {
		var read SessionFile
		if decodeStrict(b, &read) == nil {
			file = read
		}
	}
	if file.DeviceID == "" {
		file.DeviceID = newDeviceID()
	}
	return file
}

// shelve puts a record at the front of the shelf (`saved`), replacing the same
// account, and returns the records pushed out beyond MaxSaved.
func (f *SessionFile) shelve(record SessionRecord) []SessionRecord {
	f.Saved = slices.DeleteFunc(f.Saved, func(s SessionRecord) bool { return s.sameAccount(&record) })
	f.Saved = slices.Insert(f.Saved, 0, record)
	keep := min(len(f.Saved), MaxSaved)
	evicted := slices.Clone(f.Saved[keep:])
	f.Saved = f.Saved[:keep]
	return evicted
}

// usesURL: a record still names this address.
func (f *SessionFile) usesURL(url string) bool {
	if f.Session != nil && f.Session.ServerURL == url || f.Reauth != nil && f.Reauth.ServerURL == url {
		return true
	}
	return slices.ContainsFunc(f.Saved, func(r SessionRecord) bool { return r.ServerURL == url })
}

// Save is `save`: atomic write (temp file + rename) so a crash never leaves a half
// file. Pretty JSON as serde's `to_vec_pretty` (two spaces, `<>&` not escaped, no
// final newline), so the Rust app reads it back.
func (f *SessionFile) Save(path string) error {
	ioErr := func(err error) error { return NewCredentialStore("session.json : " + osErrorText(err)) }
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return ioErr(err)
	}
	tmp := strings.TrimSuffix(path, filepath.Ext(path)) + ".json.tmp"
	var buf bytes.Buffer
	enc := json.NewEncoder(&buf)
	enc.SetEscapeHTML(false)
	enc.SetIndent("", "  ")
	if err := enc.Encode(f); err != nil {
		return NewCredentialStore(err.Error())
	}
	if err := os.WriteFile(tmp, bytes.TrimSuffix(buf.Bytes(), []byte("\n")), 0o644); err != nil {
		return ioErr(err)
	}
	if err := os.Rename(tmp, path); err != nil {
		return ioErr(err)
	}
	return nil
}

const (
	// MaxPage is the largest page the UI may ask for.
	MaxPage uint32 = 200
	// homeRow: cards per home row, about two window widths.
	homeRow uint32 = 16
	// heroSize: most titles of the home hero (ADR 0015).
	heroSize = 5
	// SearchShown: search results shown per type (ADR 0011); one more is asked to
	// know if more exist.
	SearchShown = 24
	// SearchEpisodesAsked: episodes asked per search (ADR 0020): Emby also matches the
	// series name, so the episodes of a matching series come first and are dropped.
	SearchEpisodesAsked uint32 = 100
	// searchMinChars: shorter queries are not sent: the server answers nothing to one letter.
	searchMinChars  = 2
	searchMaxChars  = 100
	ticksPerMinute  = 600_000_000
	imageConcurrent = 6
	// MaxSaved: sessions kept aside by "Changer de serveur"; the oldest is forgotten beyond.
	MaxSaved = 8
	// playlistsMax: playlists the card menu offers (ADR 0100).
	playlistsMax uint32 = 200
	// homePlaylists: playlists on the home row (ADR 0122).
	homePlaylists uint32 = 50
	// playlistItemsMax: entries read for one playlist: one request, no paging (ADR 0122).
	playlistItemsMax uint32 = 300
	// PlaylistNameMax is the longest playlist name accepted, in characters.
	PlaylistNameMax = 200
)

type current struct {
	client *Client
	user   SignedInUser
	// libraries: last `/Views` answer: item queries only accept these ids as parent.
	libraries []Library
}

// ArtworkStore is the disk cache of artwork the service uses (ADR 0019), implemented
// by *artworkcache.Cache. An interface because artworkcache imports this package.
// Its Stats and Clear (`artwork_stats`, `clear_artwork` of session.rs) are called by
// the app shell on the cache itself: see ArtworkCache.
type ArtworkStore interface {
	Get(req ImageRequest) (contentType string, data []byte, ok bool)
	Put(req ImageRequest, data []byte) (bool, error)
}

// EmbyService is `EmbyService`. Build it with NewEmbyService and the With* options
// before first use. Safe for concurrent use.
type EmbyService struct {
	store      CredentialStore
	path       string
	deviceName string
	version    string
	log        *slog.Logger

	fileMu sync.Mutex
	file   SessionFile

	mu      sync.Mutex
	current *current

	// imagePermits bounds concurrent artwork downloads to spare the server (ADR 0005).
	imagePermits chan struct{}
	// bitrateOverride: debug limit of the scenarios (ADR 0009); wins over the setting.
	bitrateOverride *uint64
	// quality: bitrate caps per server (ADR 0037).
	quality *QualityStore
	epoch   atomic.Uint64
	// timeouts of new clients; nil keeps the client defaults.
	timeouts *[2]time.Duration

	// expired: client of the last session ended by a 401: its other requests in
	// flight still answer SessionExpired, not Cancelled. Locked after mu.
	expiredMu sync.Mutex
	expired   *Client

	// artwork kept on disk across launches (ADR 0019); nil fetches every time.
	artwork ArtworkStore
}

// NewEmbyService is `EmbyService::new`. `playback-quality.json` sits beside path.
func NewEmbyService(store CredentialStore, path, deviceName, version string) *EmbyService {
	return &EmbyService{
		store: store, path: path, deviceName: deviceName, version: version, log: slog.Default(),
		file:         LoadSessionFile(path),
		imagePermits: make(chan struct{}, imageConcurrent),
		quality:      LoadQualityStore(filepath.Join(filepath.Dir(path), "playback-quality.json")),
	}
}

// WithArtworkCache is `with_artwork_cache`.
func (s *EmbyService) WithArtworkCache(cache ArtworkStore) *EmbyService {
	s.artwork = cache
	return s
}

// ArtworkCache is `artwork_cache`: the disk cache, shared with Request artwork
// (one quota, ADR 0125); nil without one. `artwork_stats` and `clear_artwork` are
// its Stats and Clear (the app shell holds the *artworkcache.Cache).
func (s *EmbyService) ArtworkCache() ArtworkStore { return s.artwork }

// WithTimeouts is `with_timeouts`: short bounds, so tests can simulate a server that
// never answers.
func (s *EmbyService) WithTimeouts(connect, request time.Duration) *EmbyService {
	s.timeouts = &[2]time.Duration{connect, request}
	return s
}

// WithMaxStreamingBitrate is `with_max_streaming_bitrate`: a lower streaming limit,
// e.g. to make the server transcode in tests.
func (s *EmbyService) WithMaxStreamingBitrate(bitrate uint64) *EmbyService {
	s.bitrateOverride = &bitrate
	return s
}

// WithLogger sets the logger (slog.Default by default). Only error kinds are
// logged, never a token or a server's text.
func (s *EmbyService) WithLogger(l *slog.Logger) *EmbyService {
	s.log = l
	return s
}

func (s *EmbyService) newClient(url string) (*Client, error) {
	server, err := ParseServerURL(url)
	if err != nil {
		return nil, err
	}
	if s.timeouts != nil {
		return NewClientWithTimeouts(server, s.device(), s.timeouts[0], s.timeouts[1])
	}
	return NewClient(server, s.device())
}

// PlaybackQuality is `playback_quality`: bitrate caps of the current server (ADR 0037).
func (s *EmbyService) PlaybackQuality() (PlaybackQuality, error) {
	serverID, local, err := s.qualityScope()
	if err != nil {
		return PlaybackQuality{}, err
	}
	return NewPlaybackQuality(s.quality.Get(serverID), local), nil
}

// SetPlaybackQuality keeps the caps of the current server; a value off the list is refused.
func (s *EmbyService) SetPlaybackQuality(caps QualityCaps) (PlaybackQuality, error) {
	serverID, _, err := s.qualityScope()
	if err != nil {
		return PlaybackQuality{}, err
	}
	if err := s.quality.Set(serverID, caps); err != nil {
		return PlaybackQuality{}, err
	}
	return s.PlaybackQuality()
}

func (s *EmbyService) qualityScope() (string, bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.current == nil {
		return "", false, ErrNotSignedIn
	}
	return s.current.user.Server.ID, s.current.client.Server().IsLocal(), nil
}

// streamingLimit: the debug override, then the server's cap for the kind of address
// of the session, then the default limit (ADR 0037).
func (s *EmbyService) streamingLimit(client *Client) StreamingLimit {
	local := client.Server().IsLocal()
	cap := s.bitrateOverride
	if cap == nil {
		cap = s.quality.Get(s.scope()).ForAddress(local)
	}
	limit := StreamingLimit{Bitrate: MaxStreamingBitrate, Cap: cloneU64(cap), LocalAddress: local}
	if cap != nil {
		limit.Bitrate = *cap
	}
	return limit
}

func cloneU64(p *uint64) *uint64 {
	if p == nil {
		return nil
	}
	v := *p
	return &v
}

func (s *EmbyService) device() DeviceIdentity {
	return DeviceIdentity{Name: s.deviceName, ID: s.fileCopy().DeviceID, Version: s.version}
}

func (s *EmbyService) fileCopy() SessionFile {
	s.fileMu.Lock()
	defer s.fileMu.Unlock()
	return s.file.clone()
}

// updateFile changes session.json under its lock and saves it. As in Rust, a failed
// save keeps the change in memory.
func (s *EmbyService) updateFile(f func(*SessionFile)) error {
	s.fileMu.Lock()
	defer s.fileMu.Unlock()
	f(&s.file)
	return s.file.Save(s.path)
}

// setCurrent replaces the open session and starts a new epoch.
func (s *EmbyService) setCurrent(c *current) {
	s.mu.Lock()
	s.epoch.Add(1)
	s.current = c
	s.mu.Unlock()
}

// scope is the server id of the open session, or empty: scopes image URLs (ADR 0012).
func (s *EmbyService) scope() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.current == nil {
		return ""
	}
	return s.current.user.Server.ID
}

// forgetRejected: the server rejected this session: its token is deleted, and
// session.json keeps a non-secret hint to prefill the reauthentication screen.
func (s *EmbyService) forgetRejected(record SessionRecord) {
	if err := s.store.Delete(record.account()); err != nil {
		s.log.Warn("rejected token not deleted", "kind", kindText(err))
	}
	err := s.updateFile(func(f *SessionFile) {
		if f.Session != nil && f.Session.sameAccount(&record) {
			f.Session = nil
		}
		r := record
		f.Reauth = &r
	})
	if err != nil {
		s.log.Warn("session.json not updated", "kind", kindText(err))
	}
}

// CurrentUser is `current_user`.
func (s *EmbyService) CurrentUser() *SignedInUser {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.current == nil {
		return nil
	}
	u := s.current.user
	return &u
}

// Client is `client`: the authenticated client of the open session (for library and
// playback calls).
func (s *EmbyService) Client() (*Client, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.current == nil {
		return nil, ErrNotSignedIn
	}
	return s.current.client, nil
}

// ExchangeIdentity is `exchange_identity`: server id, user id and token of the open
// session, traded for a Request session (ADR 0125). The only way the Emby token
// leaves this service; request.IdentitySource wraps it.
func (s *EmbyService) ExchangeIdentity() (serverID, userID string, token AccessToken, err error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.current == nil {
		return "", "", AccessToken{}, ErrNotSignedIn
	}
	t := s.current.client.accessToken()
	if t == nil {
		return "", "", AccessToken{}, ErrNotSignedIn
	}
	return s.current.user.Server.ID, s.current.user.UserID, *t, nil
}

// RequestAccount is `request::account`, rebuilt here because request
// imports this package; a test pins it to request.Account.
func RequestAccount(serverID, userID string) string {
	return "request/" + CredentialAccount(serverID, userID)
}

// forgetRequest: the Request session of an Emby account ends with it (ADR 0125).
func (s *EmbyService) forgetRequest(serverID, userID string) {
	if err := s.store.Delete(RequestAccount(serverID, userID)); err != nil {
		s.log.Warn("Request token not deleted", "kind", kindText(err))
	}
}

func (s *EmbyService) session() (*Client, string, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.current == nil {
		return nil, "", ErrNotSignedIn
	}
	return s.current.client, s.current.user.UserID, nil
}

// expireOn401 is `expire_on_401` on an error: a token rejected while browsing is
// forgotten like at startup, so the next launch does not retry it. Only the session
// whose token was refused ends: a late 401 from a previous session answers Cancelled
// (ADR 0012). Any other error, and nil, pass through.
func (s *EmbyService) expireOn401(client *Client, err error) error {
	if !errors.Is(err, ErrSessionExpired) {
		return err
	}
	s.mu.Lock()
	s.expiredMu.Lock()
	if s.current == nil || !s.current.client.SameToken(client) {
		// Already ended by a parallel request of the same session, or a late answer
		// for an older session.
		same := s.expired != nil && s.expired.SameToken(client)
		s.expiredMu.Unlock()
		s.mu.Unlock()
		if same {
			return err
		}
		return ErrCancelled
	}
	s.epoch.Add(1)
	c := s.current
	s.current = nil
	s.expired = c.client
	s.expiredMu.Unlock()
	s.mu.Unlock()
	s.forgetRejected(recordOf(&c.user))
	return err
}

// expire is expireOn401 on a result.
func expire[T any](s *EmbyService, client *Client, v T, err error) (T, error) {
	if err = s.expireOn401(client, err); err != nil {
		var zero T
		return zero, err
	}
	return v, nil
}

// result is one call run in a goroutine (a `tokio::join!` branch).
type result[T any] struct {
	v   T
	err error
}

func async[T any](f func() (T, error)) <-chan result[T] {
	ch := make(chan result[T], 1)
	go func() {
		v, err := f()
		ch <- result[T]{v, err}
	}()
	return ch
}

// Libraries is `libraries`: film and series libraries of the user, in server order.
// Other collection types (music, photos, live TV…) are not browsable yet.
func (s *EmbyService) Libraries(ctx context.Context) (List[Library], error) {
	client, userID, err := s.session()
	if err != nil {
		return nil, err
	}
	scope := s.scope()
	views, err := client.Views(ctx, userID)
	err = s.expireOn401(client, err)
	if err != nil {
		return nil, err
	}
	libraries := List[Library]{}
	for _, v := range views.Items {
		kind, ok := LibraryKindFromCollectionType(v.CollectionType)
		if !ok {
			continue
		}
		libraries = append(libraries, Library{
			ID: v.ID, Name: v.Name, Kind: kind,
			Image: ImageRefPtr(scope, v.ID, ImagePrimary, imageTag(v.ImageTags, "Primary"), LibraryWidths),
		})
	}
	s.mu.Lock()
	if s.current != nil {
		s.current.libraries = slices.Clone(libraries)
	}
	s.mu.Unlock()
	return libraries, nil
}

func imageTag(tags map[string]string, name string) *string {
	t, ok := tags[name]
	if !ok {
		return nil
	}
	return &t
}

func first(list []string) *string {
	if len(list) == 0 {
		return nil
	}
	v := list[0]
	return &v
}

func (s *EmbyService) libraryKind(libraryID string) (LibraryKind, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.current == nil {
		return "", false
	}
	for _, l := range s.current.libraries {
		if l.ID == libraryID {
			return l.Kind, true
		}
	}
	return "", false
}

// knownLibrary is the kind of a library of the session; the list is loaded when
// unknown, and an id outside it is refused (Emby would answer with the whole server).
func (s *EmbyService) knownLibrary(ctx context.Context, libraryID string) (LibraryKind, error) {
	if k, ok := s.libraryKind(libraryID); ok {
		return k, nil
	}
	if _, err := s.Libraries(ctx); err != nil {
		return "", err
	}
	if k, ok := s.libraryKind(libraryID); ok {
		return k, nil
	}
	return "", ErrNotFound
}

// checkOptions: options from the UI the server could not filter on are refused
// before any request.
func checkOptions(o ItemsOptions) error {
	if o.Genre != nil && !IsUsableGenre(*o.Genre) {
		return NewInvalidResponse("genre invalide")
	}
	if o.Decade != nil && !IsUsableDecade(*o.Decade) {
		return NewInvalidResponse("décennie invalide")
	}
	return nil
}

// Items is `items`: one page of films or series of a library, sorted and filtered as
// asked (ADR 0013). limit is clamped to 1..=MaxPage.
func (s *EmbyService) Items(ctx context.Context, libraryID string, startIndex, limit uint32, options ItemsOptions) (ItemPage, error) {
	if err := checkOptions(options); err != nil {
		return ItemPage{}, err
	}
	kind, err := s.knownLibrary(ctx, libraryID)
	if err != nil {
		return ItemPage{}, err
	}
	client, userID, err := s.session()
	if err != nil {
		return ItemPage{}, err
	}
	scope := s.scope()
	query := ItemsQuery{ParentID: libraryID, ItemTypes: kind.ItemTypes(), StartIndex: startIndex,
		Limit: min(max(limit, 1), MaxPage), Options: options, Shape: ShapePage}
	page, err := client.Items(ctx, userID, query)
	err = s.expireOn401(client, err)
	if err != nil {
		return ItemPage{}, err
	}
	items := List[MediaItem]{}
	for _, d := range page.Items {
		if m, ok := mediaItem(d, scope); ok {
			items = append(items, m)
		}
	}
	return ItemPage{Items: items, StartIndex: startIndex, NextIndex: saturatingAdd(startIndex, len(page.Items)), Total: page.TotalRecordCount}, nil
}

func saturatingAdd(a uint32, n int) uint32 {
	if uint64(a)+uint64(n) > 0xFFFF_FFFF {
		return 0xFFFF_FFFF
	}
	return a + uint32(n)
}

// SortNames is `sort_names`: every title of a library grid in its order, with its
// sort name: the letter rail jumps from it (ADR 0101). Unlike pages, nothing is
// dropped, so positions match the server's.
func (s *EmbyService) SortNames(ctx context.Context, libraryID string, options ItemsOptions) (List[SortNameEntry], error) {
	if err := checkOptions(options); err != nil {
		return nil, err
	}
	kind, err := s.knownLibrary(ctx, libraryID)
	if err != nil {
		return nil, err
	}
	client, userID, err := s.session()
	if err != nil {
		return nil, err
	}
	query := ItemsQuery{ParentID: libraryID, ItemTypes: kind.ItemTypes(), Options: options, Shape: ShapeSortNames}
	page, err := client.Items(ctx, userID, query)
	err = s.expireOn401(client, err)
	if err != nil {
		return nil, err
	}
	out := List[SortNameEntry]{}
	for _, d := range page.Items {
		name := d.Name
		if d.SortName != nil && strings.TrimSpace(*d.SortName) != "" {
			name = *d.SortName
		}
		out = append(out, SortNameEntry{ID: d.ID, Name: name})
	}
	return out, nil
}

// RandomItem is `random_item`: one title drawn by the server among those the grid
// shows (ADR 0101), nil when none matches.
func (s *EmbyService) RandomItem(ctx context.Context, libraryID string, options ItemsOptions) (*MediaItem, error) {
	if err := checkOptions(options); err != nil {
		return nil, err
	}
	kind, err := s.knownLibrary(ctx, libraryID)
	if err != nil {
		return nil, err
	}
	client, userID, err := s.session()
	if err != nil {
		return nil, err
	}
	scope := s.scope()
	query := ItemsQuery{ParentID: libraryID, ItemTypes: kind.ItemTypes(), Limit: 1, Options: options, Shape: ShapeRandom}
	page, err := client.Items(ctx, userID, query)
	err = s.expireOn401(client, err)
	if err != nil {
		return nil, err
	}
	for _, d := range page.Items {
		if m, ok := mediaItem(d, scope); ok {
			return &m, nil
		}
	}
	return nil, nil
}

// ProductionYears is `production_years`: production years of a library, newest
// first, without repeats (decade filter, ADR 0101).
func (s *EmbyService) ProductionYears(ctx context.Context, libraryID string) (List[int32], error) {
	kind, err := s.knownLibrary(ctx, libraryID)
	if err != nil {
		return nil, err
	}
	client, userID, err := s.session()
	if err != nil {
		return nil, err
	}
	page, err := client.ProductionYears(ctx, userID, libraryID, kind.ItemTypes())
	err = s.expireOn401(client, err)
	if err != nil {
		return nil, err
	}
	years := List[int32]{}
	for _, d := range page.Items {
		if d.ProductionYear != nil && *d.ProductionYear >= 1880 && *d.ProductionYear <= 2199 {
			years = append(years, *d.ProductionYear)
		}
	}
	slices.SortFunc(years, func(a, b int32) int { return int(b) - int(a) })
	return slices.Compact(years), nil
}

// Genres is `genres`: genres offered as a filter of a library grid, by name. Names
// the server could not filter on (`|` is its separator) are left out.
func (s *EmbyService) Genres(ctx context.Context, libraryID string) (List[string], error) {
	kind, err := s.knownLibrary(ctx, libraryID)
	if err != nil {
		return nil, err
	}
	client, userID, err := s.session()
	if err != nil {
		return nil, err
	}
	page, err := client.Genres(ctx, userID, libraryID, kind.ItemTypes())
	err = s.expireOn401(client, err)
	if err != nil {
		return nil, err
	}
	names := List[string]{}
	for _, d := range page.Items {
		if n := strings.TrimSpace(d.Name); IsUsableGenre(n) {
			names = append(names, n)
		}
	}
	return slices.Compact(names), nil
}

// ContinueWatching is `continue_watching`: home row "Reprendre": films and episodes
// started but not finished.
func (s *EmbyService) ContinueWatching(ctx context.Context) (List[WatchItem], error) {
	client, userID, err := s.session()
	if err != nil {
		return nil, err
	}
	scope := s.scope()
	res, err := client.ResumeItems(ctx, userID, homeRow)
	err = s.expireOn401(client, err)
	if err != nil {
		return nil, err
	}
	return watchItems(res.Items, scope), nil
}

func watchItems(items []BaseItemDto, scope string) List[WatchItem] {
	out := List[WatchItem]{}
	for _, d := range items {
		if w, ok := watchItem(d, scope); ok {
			out = append(out, w)
		}
	}
	return out
}

// Hero is `hero`, the home hero (ADR 0015): up to five titles with a backdrop, the
// ones to resume first, then films and series added recently and not watched; one
// title per film or series. A series plays its first episode not watched yet.
func (s *EmbyService) Hero(ctx context.Context) (List[HeroItem], error) {
	client, userID, err := s.session()
	if err != nil {
		return nil, err
	}
	scope := s.scope()
	resumedCh := async(func() (itemsPage, error) { return client.HeroResume(ctx, userID, homeRow) })
	latestCh := async(func() (itemsPage, error) { return client.HeroLatest(ctx, userID, 2*heroSize) })
	r, l := <-resumedCh, <-latestCh
	resumed, err := expire(s, client, r.v, r.err)
	if err != nil {
		return nil, err
	}
	latest, err := expire(s, client, l.v, l.err)
	if err != nil {
		return nil, err
	}
	var candidates []HeroItem
	for _, d := range resumed.Items {
		if h, ok := resumedHero(d, scope); ok {
			candidates = append(candidates, h)
		}
	}
	for _, d := range latest.Items {
		if h, ok := latestHero(d, scope); ok {
			candidates = append(candidates, h)
		}
	}
	hero := List[HeroItem]{}
	for _, item := range candidates {
		if len(hero) == heroSize {
			break
		}
		if !slices.ContainsFunc(hero, func(h HeroItem) bool { return h.Detail.ID == item.Detail.ID }) {
			hero = append(hero, item)
		}
	}
	// A handful of series at most: one request each, in turn.
	for i := range hero {
		item := &hero[i]
		if item.PlayID != nil || item.Detail.ItemType != "Series" {
			continue
		}
		page, err := client.FirstUnplayedEpisode(ctx, userID, item.Detail.ID)
		err = s.expireOn401(client, err)
		if err != nil {
			return nil, err
		}
		for _, dto := range page.Items {
			if dto.ItemType != "Episode" || !isID(dto.ID) {
				continue
			}
			userData := userDataOf(dto.UserData)
			item.Resume = resume(userData, dto.RunTimeTicks)
			ep := heroEpisode(&dto)
			item.Episode = &ep
			id := dto.ID
			item.PlayID = &id
			break
		}
	}
	return hero, nil
}

// NextUp is `next_up`: home row "À suivre": the next episode of each series in progress.
func (s *EmbyService) NextUp(ctx context.Context) (List[WatchItem], error) {
	client, userID, err := s.session()
	if err != nil {
		return nil, err
	}
	scope := s.scope()
	// Emby 4.10.1.0 answers an empty `/Shows/NextUp` once an episode is watched, and
	// lists the next one in its "resume" home section instead (ADR 0010).
	showsCh := async(func() (itemsPage, error) { return client.NextUp(ctx, userID, homeRow) })
	sectionCh := async(func() ([]BaseItemDto, error) { return s.resumeSection(ctx, client, userID) })
	sh, sec := <-showsCh, <-sectionCh
	shows, err := expire(s, client, sh.v, sh.err)
	if err != nil {
		return nil, err
	}
	if sec.err != nil {
		return nil, sec.err
	}
	items := shows.Items
	for _, dto := range sec.v {
		if !slices.ContainsFunc(items, func(i BaseItemDto) bool { return i.ID == dto.ID }) {
			items = append(items, dto)
		}
	}
	out := List[WatchItem]{}
	for _, i := range items {
		if len(out) == int(homeRow) {
			break
		}
		if i.ItemType != "Episode" {
			continue
		}
		if u := i.UserData; u != nil && (u.Played || u.PlaybackPositionTicks != 0) {
			continue
		}
		if w, ok := watchItem(i, scope); ok {
			out = append(out, w)
		}
	}
	return out, nil
}

// resumeSection: items of the server's "resume" home section when it includes Next
// Up; none when the server has no home sections (404) or no such section.
func (s *EmbyService) resumeSection(ctx context.Context, client *Client, userID string) ([]BaseItemDto, error) {
	sections, err := client.HomeSections(ctx, userID)
	if errors.Is(err, ErrNotFound) {
		return nil, nil
	}
	if sections, err = expire(s, client, sections, err); err != nil {
		return nil, err
	}
	var section *HomeSectionDto
	for i := range sections {
		if sec := &sections[i]; sec.SectionType == "resume" && sec.IncludeNextUpInResume && isID(sec.ID) {
			section = sec
			break
		}
	}
	if section == nil {
		return nil, nil
	}
	page, err := client.SectionItems(ctx, userID, section.ID, homeRow)
	err = s.expireOn401(client, err)
	if err != nil {
		return nil, err
	}
	return page.Items, nil
}

// Search is `search`: films, series and episodes whose title matches query (ADR
// 0011, 0020), all searched at once. A query shorter than two characters once
// cleaned reaches no server.
func (s *EmbyService) Search(ctx context.Context, query string) (SearchResults, error) {
	query = CleanSearch(query)
	if runeCount(query) < searchMinChars {
		return SearchResults{Movies: List[MediaItem]{}, Series: List[MediaItem]{}, Episodes: List[WatchItem]{}}, nil
	}
	client, userID, err := s.session()
	if err != nil {
		return SearchResults{}, err
	}
	scope := s.scope()
	asked := uint32(SearchShown + 1)
	moviesCh := async(func() (itemsPage, error) { return client.Search(ctx, userID, query, "Movie", asked) })
	seriesCh := async(func() (itemsPage, error) { return client.Search(ctx, userID, query, "Series", asked) })
	episodesCh := async(func() (itemsPage, error) {
		return client.Search(ctx, userID, query, "Episode", SearchEpisodesAsked)
	})
	m, se, ep := <-moviesCh, <-seriesCh, <-episodesCh
	keep := func(dtos []BaseItemDto, itemType string) (List[MediaItem], bool) {
		out := List[MediaItem]{}
		for _, d := range dtos {
			if d.ItemType != itemType {
				continue
			}
			if mi, ok := mediaItem(d, scope); ok {
				out = append(out, mi)
			}
		}
		if len(out) > SearchShown {
			return out[:SearchShown], true
		}
		return out, false
	}
	moviesPage, err := expire(s, client, m.v, m.err)
	if err != nil {
		return SearchResults{}, err
	}
	movies, moreMovies := keep(moviesPage.Items, "Movie")
	seriesPage, err := expire(s, client, se.v, se.err)
	if err != nil {
		return SearchResults{}, err
	}
	series, moreSeries := keep(seriesPage.Items, "Series")
	// Only episodes whose own title matches: a full page may hide others.
	episodesPage, err := expire(s, client, ep.v, ep.err)
	if err != nil {
		return SearchResults{}, err
	}
	pageFull := len(episodesPage.Items) >= int(SearchEpisodesAsked)
	episodes := List[WatchItem]{}
	for _, d := range episodesPage.Items {
		if d.ItemType != "Episode" || !TitleMatches(d.Name, query) {
			continue
		}
		if w, ok := watchItem(d, scope); ok {
			episodes = append(episodes, w)
		}
	}
	more := len(episodes) > SearchShown
	if more {
		episodes = episodes[:SearchShown]
	}
	return SearchResults{
		Query: query, Movies: movies, Series: series, Episodes: episodes,
		MoreMovies: moreMovies, MoreSeries: moreSeries, MoreEpisodes: more || pageFull,
	}, nil
}

// Latest is `latest`: home row "Ajoutés récemment" of a film or series library: films
// and series (a series stands for its new episodes), newest first.
func (s *EmbyService) Latest(ctx context.Context, libraryID string) (List[MediaItem], error) {
	kind, err := s.knownLibrary(ctx, libraryID)
	if err != nil {
		return nil, err
	}
	client, userID, err := s.session()
	if err != nil {
		return nil, err
	}
	scope := s.scope()
	items, err := client.Latest(ctx, userID, libraryID, homeRow)
	err = s.expireOn401(client, err)
	if err != nil {
		return nil, err
	}
	out := List[MediaItem]{}
	for _, d := range items {
		if d.ItemType != kind.ItemTypes() {
			continue
		}
		if m, ok := mediaItem(d, scope); ok {
			out = append(out, m)
		}
	}
	return out, nil
}

// Favorites is `favorites`: home row "Favoris" (ADR 0014): favorite films and
// series, by title.
func (s *EmbyService) Favorites(ctx context.Context) (List[MediaItem], error) {
	client, userID, err := s.session()
	if err != nil {
		return nil, err
	}
	scope := s.scope()
	page, err := client.Favorites(ctx, userID, homeRow)
	err = s.expireOn401(client, err)
	if err != nil {
		return nil, err
	}
	out := List[MediaItem]{}
	for _, d := range page.Items {
		if d.ItemType != "Movie" && d.ItemType != "Series" {
			continue
		}
		if m, ok := mediaItem(d, scope); ok {
			out = append(out, m)
		}
	}
	return out, nil
}

var errInvalidID = NewInvalidResponse("identifiant invalide")

// SetFavorite is `set_favorite`: adds a film or series to the user's favorites, or
// removes it; returns the state the server reports.
func (s *EmbyService) SetFavorite(ctx context.Context, itemID string, favorite bool) (bool, error) {
	if !isID(itemID) {
		return false, errInvalidID
	}
	client, userID, err := s.session()
	if err != nil {
		return false, err
	}
	data, err := client.SetFavorite(ctx, userID, itemID, favorite)
	err = s.expireOn401(client, err)
	if err != nil {
		return false, err
	}
	return data.IsFavorite, nil
}

// SetPlayed is `set_played`: marks a film, episode, season or series played or
// unplayed (ADR 0096), then reads back what the server holds.
func (s *EmbyService) SetPlayed(ctx context.Context, itemID string, played bool) (PlaybackStopped, error) {
	if !isID(itemID) {
		return PlaybackStopped{}, errInvalidID
	}
	client, userID, err := s.session()
	if err != nil {
		return PlaybackStopped{}, err
	}
	var date *string
	if played {
		d := DatePlayedNow()
		date = &d
	}
	data, err := client.SetPlayed(ctx, userID, itemID, date)
	err = s.expireOn401(client, err)
	if err != nil {
		return PlaybackStopped{}, err
	}
	return s.stateAfterChange(ctx, itemID, data), nil
}

// HideFromResume is `hide_from_resume`: takes a film or episode out of "Reprendre"
// (ADR 0096), then reads back the state as SetPlayed.
func (s *EmbyService) HideFromResume(ctx context.Context, itemID string) (PlaybackStopped, error) {
	if !isID(itemID) {
		return PlaybackStopped{}, errInvalidID
	}
	client, userID, err := s.session()
	if err != nil {
		return PlaybackStopped{}, err
	}
	data, err := client.HideFromResume(ctx, userID, itemID, true)
	err = s.expireOn401(client, err)
	if err != nil {
		return PlaybackStopped{}, err
	}
	return s.stateAfterChange(ctx, itemID, data), nil
}

// SeriesNextEpisode is `series_next_episode`: next episode to play of a series, as
// the home hero picks it (ADR 0100); nil when all are watched.
func (s *EmbyService) SeriesNextEpisode(ctx context.Context, seriesID string) (*WatchItem, error) {
	if !isID(seriesID) {
		return nil, errInvalidID
	}
	client, userID, err := s.session()
	if err != nil {
		return nil, err
	}
	scope := s.scope()
	page, err := client.FirstUnplayedEpisode(ctx, userID, seriesID)
	err = s.expireOn401(client, err)
	if err != nil {
		return nil, err
	}
	for _, d := range page.Items {
		if d.ItemType != "Episode" {
			continue
		}
		if w, ok := watchItem(d, scope); ok {
			return &w, nil
		}
	}
	return nil, nil
}

// Playlists is `playlists`: playlists of the user, by name (ADR 0100).
func (s *EmbyService) Playlists(ctx context.Context) (List[Playlist], error) {
	client, userID, err := s.session()
	if err != nil {
		return nil, err
	}
	page, err := client.Playlists(ctx, userID, playlistsMax)
	err = s.expireOn401(client, err)
	if err != nil {
		return nil, err
	}
	out := List[Playlist]{}
	for _, p := range page.Items {
		if p.ItemType != "Playlist" || !isID(p.ID) {
			continue
		}
		out = append(out, Playlist{ID: p.ID, Name: textOr(&p.Name, "Sans titre")})
	}
	return out, nil
}

// HomePlaylists is `home_playlists`: home row « Playlists » (ADR 0122): the user's
// video playlists, A → Z.
func (s *EmbyService) HomePlaylists(ctx context.Context) (List[PlaylistCard], error) {
	client, userID, err := s.session()
	if err != nil {
		return nil, err
	}
	scope := s.scope()
	page, err := client.HomePlaylists(ctx, userID, homePlaylists)
	err = s.expireOn401(client, err)
	if err != nil {
		return nil, err
	}
	return videoPlaylists(page.Items, scope), nil
}

// PlaylistItems is `playlist_items`: films and episodes of a playlist, in its order (ADR 0122).
func (s *EmbyService) PlaylistItems(ctx context.Context, playlistID string) (List[WatchItem], error) {
	if !isID(playlistID) {
		return nil, errInvalidID
	}
	client, userID, err := s.session()
	if err != nil {
		return nil, err
	}
	scope := s.scope()
	page, err := client.PlaylistItems(ctx, userID, playlistID, playlistItemsMax)
	err = s.expireOn401(client, err)
	if err != nil {
		return nil, err
	}
	return playlistEntries(page.Items, scope), nil
}

// AddToPlaylist is `add_to_playlist`: appends a film or episode to one of the
// user's playlists (ADR 0100); returns how many items the server says it added.
func (s *EmbyService) AddToPlaylist(ctx context.Context, playlistID, itemID string) (uint32, error) {
	if !isID(playlistID) || !isID(itemID) {
		return 0, errInvalidID
	}
	client, userID, err := s.session()
	if err != nil {
		return 0, err
	}
	res, err := client.AddToPlaylist(ctx, userID, playlistID, itemID)
	err = s.expireOn401(client, err)
	if err != nil {
		return 0, err
	}
	return res.ItemAddedCount, nil
}

// CreatePlaylist is `create_playlist`: creates a video playlist named name holding
// the item (ADR 0100).
func (s *EmbyService) CreatePlaylist(ctx context.Context, name, itemID string) (PlaylistCreated, error) {
	name, ok := playlistName(name)
	if !ok {
		return PlaylistCreated{}, NewInvalidResponse("nom de playlist invalide")
	}
	if !isID(itemID) {
		return PlaylistCreated{}, errInvalidID
	}
	client, userID, err := s.session()
	if err != nil {
		return PlaylistCreated{}, err
	}
	res, err := client.CreatePlaylist(ctx, userID, name, itemID)
	err = s.expireOn401(client, err)
	if err != nil {
		return PlaylistCreated{}, err
	}
	if !isID(res.ID) {
		return PlaylistCreated{}, errInvalidID
	}
	return PlaylistCreated{ID: res.ID, Name: textOr(&res.Name, name), Added: res.ItemAddedCount}, nil
}

// stateAfterChange: state of an item the server just changed: read again for the
// resume fraction, else taken from the user data it answered (the change is made).
func (s *EmbyService) stateAfterChange(ctx context.Context, itemID string, data UserItemData) PlaybackStopped {
	state, err := s.PlaybackState(ctx, itemID)
	if err != nil {
		s.log.Warn("state after a watched change not read", "kind", kindText(err))
		return PlaybackStopped{ItemID: itemID, Played: data.Played, Resume: resume(data, nil)}
	}
	return state
}

var errOtherItem = NewInvalidResponse("le serveur a renvoyé un autre élément")

// ItemDetail is `item_detail`: detail page of a film, series or episode. Other item
// types are refused. An episode also gets its neighbours in the series (ADR 0016).
func (s *EmbyService) ItemDetail(ctx context.Context, itemID string) (ItemDetail, error) {
	client, userID, err := s.session()
	if err != nil {
		return ItemDetail{}, err
	}
	dto, err := client.Item(ctx, userID, itemID)
	err = s.expireOn401(client, err)
	if err != nil {
		return ItemDetail{}, err
	}
	if dto.ID != itemID {
		return ItemDetail{}, errOtherItem
	}
	detail, err := itemDetail(dto, s.scope())
	if err != nil {
		return ItemDetail{}, err
	}
	if ep := detail.Episode; ep != nil {
		around, err := client.AdjacentEpisodes(ctx, userID, ep.SeriesID, itemID)
		err = s.expireOn401(client, err)
		if err != nil {
			return ItemDetail{}, err
		}
		ep.Previous, ep.Next = neighbours(around.Items, ep.SeriesID, itemID)
	}
	return detail, nil
}

// Titles is `titles`: titles of a person, collection, genre or studio opened from a
// detail page (ADR 0121): films and series only, once each, in the source's order.
func (s *EmbyService) Titles(ctx context.Context, source TitlesSource) (List[MediaItem], error) {
	client, userID, err := s.session()
	if err != nil {
		return nil, err
	}
	scope := s.scope()
	var page itemsPage
	if source.Kind == TitlesSimilar {
		params, ok := SimilarParams(source.ID, userID)
		if !ok {
			return nil, errInvalidID
		}
		page, err = client.Similar(ctx, source.ID, params)
	} else {
		params, perr := TitlesParams(source)
		if perr != nil {
			return nil, perr
		}
		page, err = client.Titles(ctx, userID, params)
	}
	if page, err = expire(s, client, page, err); err != nil {
		return nil, err
	}
	out := List[MediaItem]{}
	for _, d := range OrderTitles(source, page.Items) {
		if m, ok := mediaItem(d, scope); ok {
			out = append(out, m)
		}
	}
	return out, nil
}

// LocalTrailer is `local_trailer`: id of the first trailer file of a film or series
// (ADR 0121), played like any item; nil when the server lists none.
func (s *EmbyService) LocalTrailer(ctx context.Context, itemID string) (*string, error) {
	if !IsItemID(itemID) {
		return nil, errInvalidID
	}
	client, userID, err := s.session()
	if err != nil {
		return nil, err
	}
	list, err := client.LocalTrailers(ctx, userID, itemID)
	err = s.expireOn401(client, err)
	if err != nil {
		return nil, err
	}
	for _, d := range list {
		if IsItemID(d.ID) {
			id := d.ID
			return &id, nil
		}
	}
	return nil, nil
}

// Chapters is `chapters`: chapters of a film or episode for the player (ADR 0034),
// from the item's `Chapters`; none when it has fewer than two.
func (s *EmbyService) Chapters(ctx context.Context, itemID string) (List[Chapter], error) {
	client, userID, err := s.session()
	if err != nil {
		return nil, err
	}
	dto, err := client.Item(ctx, userID, itemID)
	err = s.expireOn401(client, err)
	if err != nil {
		return nil, err
	}
	if dto.ID != itemID {
		return nil, errOtherItem
	}
	return Chapters(dto.Chapters, dto.RunTimeTicks), nil
}

// Seasons is `seasons`: seasons of a series, in server order; unusable entries are dropped.
func (s *EmbyService) Seasons(ctx context.Context, seriesID string) (List[Season], error) {
	client, userID, err := s.session()
	if err != nil {
		return nil, err
	}
	res, err := client.Seasons(ctx, userID, seriesID)
	err = s.expireOn401(client, err)
	if err != nil {
		return nil, err
	}
	out := List[Season]{}
	for _, d := range res.Items {
		if !isID(d.ID) || (d.SeriesID != nil && *d.SeriesID != seriesID) {
			continue
		}
		u := userDataOf(d.UserData)
		out = append(out, Season{ID: d.ID, Name: d.Name, Index: d.IndexNumber, UnplayedCount: u.UnplayedItemCount, Played: u.Played})
	}
	return out, nil
}

// PlaybackState is `playback_state`: watched status and resume point the server
// holds for an item.
func (s *EmbyService) PlaybackState(ctx context.Context, itemID string) (PlaybackStopped, error) {
	client, userID, err := s.session()
	if err != nil {
		return PlaybackStopped{}, err
	}
	dto, err := client.Item(ctx, userID, itemID)
	err = s.expireOn401(client, err)
	if err != nil {
		return PlaybackStopped{}, err
	}
	if dto.ID != itemID {
		return PlaybackStopped{}, errOtherItem
	}
	u := userDataOf(dto.UserData)
	return PlaybackStopped{ItemID: dto.ID, Played: u.Played, Resume: resume(u, dto.RunTimeTicks)}, nil
}

// Episodes is `episodes`: one page of episodes of a season, limit clamped to
// 1..=MaxPage. A season that belongs to another series is reported as not found.
// Specials aired during the season come within it, with their own `SeasonId` and
// season index 0 (Emby 4.10.1.0): they are kept.
func (s *EmbyService) Episodes(ctx context.Context, seriesID, seasonID string, startIndex, limit uint32) (EpisodePage, error) {
	client, userID, err := s.session()
	if err != nil {
		return EpisodePage{}, err
	}
	scope := s.scope()
	query := EpisodesQuery{SeriesID: seriesID, SeasonID: seasonID, StartIndex: startIndex, Limit: min(max(limit, 1), MaxPage)}
	page, err := client.Episodes(ctx, userID, query)
	err = s.expireOn401(client, err)
	if err != nil {
		return EpisodePage{}, err
	}
	for _, e := range page.Items {
		if e.SeriesID == nil || *e.SeriesID != seriesID {
			return EpisodePage{}, ErrNotFound
		}
	}
	items := List[Episode]{}
	for _, d := range page.Items {
		if e, ok := episode(d, scope); ok {
			items = append(items, e)
		}
	}
	return EpisodePage{Items: items, StartIndex: startIndex, NextIndex: saturatingAdd(startIndex, len(page.Items)), Total: page.TotalRecordCount}, nil
}

// Image is `image`: artwork of the open session's server for the `emby-img`
// protocol, from the disk cache when it has it, else downloaded (at most six at
// once) and kept (ADR 0019).
func (s *EmbyService) Image(ctx context.Context, req ImageRequest) (string, []byte, error) {
	// Session and server first: the cache never answers for another session.
	if _, _, err := s.session(); err != nil {
		return "", nil, err
	}
	if req.ServerID != s.scope() {
		return "", nil, ErrForbidden
	}
	if s.artwork != nil {
		if ct, data, ok := s.artwork.Get(req); ok {
			return ct, data, nil
		}
	}
	select {
	case s.imagePermits <- struct{}{}:
	case <-ctx.Done():
		return "", nil, ErrCancelled
	}
	defer func() { <-s.imagePermits }()
	// Read again after the wait: the session may have ended meanwhile.
	client, _, err := s.session()
	if err != nil {
		return "", nil, err
	}
	if req.ServerID != s.scope() {
		return "", nil, ErrForbidden
	}
	ct, data, err := client.Image(ctx, req)
	if err = s.expireOn401(client, err); err != nil {
		return "", nil, err
	}
	if s.artwork != nil {
		if _, err := s.artwork.Put(req, data); err != nil {
			s.log.Debug("artwork not cached", "item", req.ItemID, "kind", ioKindText(err))
		}
	}
	return ct, data, nil
}

// LastServerURL is `last_server_url`.
func (s *EmbyService) LastServerURL() *string { return s.fileCopy().LastServerURL }

// AccountTracks is `account_tracks`: audio and subtitle preferences of the signed-in
// Emby account (ADR 0115).
func (s *EmbyService) AccountTracks(ctx context.Context) (AccountTracks, error) {
	client, userID, err := s.session()
	if err != nil {
		return AccountTracks{}, err
	}
	dto, err := client.User(ctx, userID)
	err = s.expireOn401(client, err)
	if err != nil {
		return AccountTracks{}, err
	}
	return AccountTracksOf(dto.Configuration), nil
}

// ServerInfo is `server_info`: the open session's server as it answers now
// (Réglages › Serveur).
func (s *EmbyService) ServerInfo(ctx context.Context) (ServerSummary, error) {
	client, err := s.Client()
	if err != nil {
		return ServerSummary{}, err
	}
	return summary(ctx, client)
}

// Probe is `probe`: validates the address and reads the server's public identity.
func (s *EmbyService) Probe(ctx context.Context, url string) (ServerSummary, error) {
	client, err := s.newClient(url)
	if err != nil {
		return ServerSummary{}, err
	}
	return summary(ctx, client)
}

// SignIn is `sign_in`.
func (s *EmbyService) SignIn(ctx context.Context, url, username, password string) (SignedInUser, error) {
	client, err := s.newClient(url)
	if err != nil {
		return SignedInUser{}, err
	}
	server, err := summary(ctx, client)
	if err != nil {
		return SignedInUser{}, err
	}
	auth, err := client.Authenticate(ctx, strings.TrimSpace(username), password)
	if err != nil {
		return SignedInUser{}, err
	}
	if auth.ServerID != server.ID {
		return SignedInUser{}, NewInvalidResponse("l'identifiant du serveur a changé pendant la connexion")
	}
	token := NewAccessToken(auth.AccessToken)
	admin := false
	if auth.User.Policy != nil {
		admin = auth.User.Policy.IsAdministrator
	}
	user := SignedInUser{Server: server, UserID: auth.User.ID, UserName: auth.User.Name, IsAdministrator: admin}
	if err := s.store.Save(CredentialAccount(user.Server.ID, user.UserID), token); err != nil {
		return SignedInUser{}, err
	}
	record := recordOf(&user)
	err = s.updateFile(func(f *SessionFile) {
		url := record.ServerURL
		f.LastServerURL = &url
		f.Saved = slices.DeleteFunc(f.Saved, func(r SessionRecord) bool { return r.sameAccount(&record) })
		f.Reauth = nil
		r := record
		f.Session = &r
	})
	if err != nil {
		return SignedInUser{}, err
	}
	s.setCurrent(&current{client: client.WithToken(token), user: user})
	return user, nil
}

func asEmbyError(err error) EmbyError {
	var e EmbyError
	if errors.As(err, &e) {
		return e
	}
	return NewUnreachable(err.Error())
}

// Restore is `restore`: validates the saved session (ADR 0012), at startup and
// whenever the UI suspects the network. Only a rejection by the server forgets the
// token: timeouts, 5xx and unreachable servers keep everything for a later retry.
// ErrCancelled: a sign-in, sign-out or server change happened meanwhile.
func (s *EmbyService) Restore(ctx context.Context) (SessionStatus, error) {
	epoch := s.epoch.Load()
	file := s.fileCopy()
	if file.Session == nil {
		if file.Reauth != nil {
			return StatusAuthenticationRequired(file.Reauth.summary(), ErrSessionExpired), nil
		}
		return StatusSignedOut(), nil
	}
	record := *file.Session
	token, err := s.store.Load(record.account())
	if err != nil {
		return SessionStatus{}, err
	}
	if token == nil {
		// The token was removed outside the app: same as a rejected one.
		s.forgetRejected(record)
		return StatusAuthenticationRequired(record.summary(), ErrSessionExpired), nil
	}
	client, user, verr := s.validate(ctx, record, *token)
	s.mu.Lock()
	if s.epoch.Load() != epoch {
		s.mu.Unlock()
		return SessionStatus{}, ErrCancelled
	}
	if verr == nil {
		// Re-validating the open session keeps its library list.
		var libraries []Library
		if c := s.current; c != nil {
			if r := recordOf(&c.user); r.sameAccount(&record) {
				libraries = c.libraries
			}
		}
		s.current = &current{client: client, user: user, libraries: libraries}
		s.mu.Unlock()
		return StatusAuthenticated(user), nil
	}
	e := asEmbyError(verr)
	if e.Kind == KindSessionExpired || e.Kind == KindAccountUnavailable {
		s.epoch.Add(1)
		s.current = nil
		s.mu.Unlock()
		s.forgetRejected(record)
		return StatusAuthenticationRequired(record.summary(), e), nil
	}
	s.mu.Unlock()
	return StatusServerUnreachable(record.summary(), e), nil
}

// validate: public probe **without** the token, then `GET /Users/{Id}` with it: the
// token is never sent to a server whose id is not the saved one.
func (s *EmbyService) validate(ctx context.Context, record SessionRecord, token AccessToken) (*Client, SignedInUser, error) {
	client, err := s.newClient(record.ServerURL)
	if err != nil {
		return nil, SignedInUser{}, err
	}
	server, err := summary(ctx, client)
	if err != nil {
		return nil, SignedInUser{}, err
	}
	if server.ID != record.ServerID {
		return nil, SignedInUser{}, ErrServerMismatch
	}
	client = client.WithToken(token)
	dto, err := client.User(ctx, record.UserID)
	if errors.Is(err, ErrForbidden) || errors.Is(err, ErrNotFound) {
		return nil, SignedInUser{}, ErrAccountUnavailable
	}
	if err != nil {
		return nil, SignedInUser{}, err
	}
	var policy UserPolicy
	if dto.Policy != nil {
		policy = *dto.Policy
	}
	if policy.IsDisabled {
		return nil, SignedInUser{}, ErrAccountUnavailable
	}
	if dto.ID != record.UserID {
		return nil, SignedInUser{}, NewInvalidResponse("le serveur a renvoyé un autre utilisateur")
	}
	return client, SignedInUser{Server: server, UserID: dto.ID, UserName: dto.Name, IsAdministrator: policy.IsAdministrator}, nil
}

// ChangeServer is `change_server`, "Changer de serveur": closes the session without
// revoking it and sets it aside so the user can come back to it (ADR 0012). A
// rejected session (the reauthentication hint) stays on the shelf with "Mot de
// passe requis" (ADR 0103).
func (s *EmbyService) ChangeServer() error {
	s.setCurrent(nil)
	var evicted []SessionRecord
	err := s.updateFile(func(f *SessionFile) {
		if hint := f.Reauth; hint != nil {
			f.Reauth = nil
			h := *hint
			h.Rejected = true
			evicted = append(evicted, f.shelve(h)...)
		}
		if r := f.Session; r != nil {
			f.Session = nil
			evicted = append(evicted, f.shelve(*r)...)
		}
	})
	if err != nil {
		return err
	}
	s.deleteEvicted(evicted)
	return nil
}

func (s *EmbyService) deleteEvicted(evicted []SessionRecord) {
	for _, r := range evicted {
		if err := s.store.Delete(r.account()); err != nil {
			s.log.Warn("evicted token not deleted", "kind", kindText(err))
		}
	}
}

func (s *EmbyService) hasToken(r *SessionRecord) bool {
	t, err := s.store.Load(r.account())
	return err == nil && t != nil
}

// Shelf is `shelf`: tiles of the pre-connection shelf (ADR 0103), newest first, one
// per server and user: the session left by a failed check (never the open one), the
// rejected session, then the shelf.
func (s *EmbyService) Shelf() List[ShelfServer] {
	file := s.fileCopy()
	var open *SessionRecord
	if u := s.CurrentUser(); u != nil {
		r := recordOf(u)
		open = &r
	}
	var records []SessionRecord
	if file.Session != nil && !(open != nil && open.sameAccount(file.Session)) {
		records = append(records, *file.Session)
	}
	if file.Reauth != nil {
		r := *file.Reauth
		r.Rejected = true
		records = append(records, r)
	}
	records = append(records, file.Saved...)
	tiles := List[ShelfServer]{}
	var seen []SessionRecord
	for _, record := range records {
		if slices.ContainsFunc(seen, func(s SessionRecord) bool { return s.sameAccount(&record) }) {
			continue
		}
		access := ShelfSignedOut
		switch {
		case s.hasToken(&record):
			access = ShelfSession
		case record.Rejected:
			access = ShelfRejected
		}
		tiles = append(tiles, ShelfServer{Server: record.summary(), Access: access})
		seen = append(seen, record)
	}
	return tiles
}

// ForgetServer is `forget_server`, "Oublier ce serveur" (ADR 0103): the record
// (address, user name) leaves session.json and the token leaves the credential
// store. Nothing changes on the server; the returned revocation is a best effort the
// caller may run later, it never holds the removal back. nil without a token.
func (s *EmbyService) ForgetServer(serverID, userID string) (*Revocation, error) {
	file := s.fileCopy()
	var found *SessionRecord
	for _, r := range append([]*SessionRecord{file.Session, file.Reauth}, recordPtrs(file.Saved)...) {
		if r != nil && r.is(serverID, userID) {
			found = r
			break
		}
	}
	if found == nil {
		return nil, ErrNotFound
	}
	record := *found
	if u := s.CurrentUser(); u != nil {
		if r := recordOf(u); r.sameAccount(&record) {
			s.setCurrent(nil)
		}
	}
	// The token first: a vault failure leaves everything as it was.
	token, terr := s.store.Load(record.account())
	if terr != nil {
		token = nil
	}
	if err := s.store.Delete(record.account()); err != nil {
		return nil, err
	}
	s.forgetRequest(serverID, userID)
	err := s.updateFile(func(f *SessionFile) {
		if f.Session != nil && f.Session.is(serverID, userID) {
			f.Session = nil
		}
		if f.Reauth != nil && f.Reauth.is(serverID, userID) {
			f.Reauth = nil
		}
		f.Saved = slices.DeleteFunc(f.Saved, func(r SessionRecord) bool { return r.is(serverID, userID) })
		if f.LastServerURL != nil && *f.LastServerURL == record.ServerURL && !f.usesURL(record.ServerURL) {
			f.LastServerURL = nil
		}
	})
	if err != nil {
		return nil, err
	}
	if token == nil {
		return nil, nil
	}
	client, err := s.newClient(record.ServerURL)
	if err != nil {
		return nil, nil
	}
	return &Revocation{client: client, serverID: record.ServerID, token: *token, log: s.log}, nil
}

func recordPtrs(list []SessionRecord) []*SessionRecord {
	out := make([]*SessionRecord, len(list))
	for i := range list {
		out[i] = &list[i]
	}
	return out
}

// SavedSessions is `saved_sessions`: sessions set aside whose token is still in the store.
func (s *EmbyService) SavedSessions() List[SavedServer] {
	out := List[SavedServer]{}
	for _, r := range s.fileCopy().Saved {
		if s.hasToken(&r) {
			out = append(out, r.summary())
		}
	}
	return out
}

// ResumeSaved is `resume_saved`: brings a set-aside session back, then validates it
// like at startup. The session left by a failed check (vault error at startup) is
// accepted too.
func (s *EmbyService) ResumeSaved(ctx context.Context, serverID, userID string) (SessionStatus, error) {
	s.setCurrent(nil)
	found := false
	var evicted []SessionRecord
	err := s.updateFile(func(f *SessionFile) {
		if f.Session != nil && f.Session.is(serverID, userID) {
			found = true
			return
		}
		pos := slices.IndexFunc(f.Saved, func(r SessionRecord) bool { return r.is(serverID, userID) })
		if pos < 0 {
			return
		}
		record := f.Saved[pos]
		f.Saved = slices.Delete(f.Saved, pos, pos+1)
		record.Rejected = false
		if hint := f.Reauth; hint != nil {
			f.Reauth = nil
			h := *hint
			h.Rejected = true
			evicted = append(evicted, f.shelve(h)...)
		}
		if previous := f.Session; previous != nil {
			f.Session = nil
			evicted = append(evicted, f.shelve(*previous)...)
		}
		url := record.ServerURL
		f.LastServerURL = &url
		f.Session = &record
		found = true
	})
	if err != nil {
		return SessionStatus{}, err
	}
	s.deleteEvicted(evicted)
	if !found {
		return SessionStatus{}, ErrNotFound
	}
	return s.Restore(ctx)
}

// SignOut is `sign_out`: revokes the token on the server when reachable, then always
// forgets it locally: an offline sign-out must not leave a usable token on disk. The
// address and user name stay on the shelf, without token (ADR 0103).
func (s *EmbyService) SignOut(ctx context.Context) error {
	s.mu.Lock()
	s.epoch.Add(1)
	c := s.current
	s.current = nil
	s.mu.Unlock()
	record := s.fileCopy().Session
	if c != nil {
		if err := c.client.Logout(ctx); err != nil {
			s.log.Warn("Emby logout not confirmed by the server", "kind", kindText(err))
		}
	}
	if record != nil {
		if err := s.store.Delete(CredentialAccount(record.ServerID, record.UserID)); err != nil {
			return err
		}
		s.forgetRequest(record.ServerID, record.UserID)
	}
	var evicted []SessionRecord
	err := s.updateFile(func(f *SessionFile) {
		f.Session = nil
		f.Reauth = nil
		if record != nil {
			r := *record
			r.Rejected = false
			evicted = f.shelve(r)
		}
	})
	if err != nil {
		return err
	}
	s.deleteEvicted(evicted)
	return nil
}

// Revocation is the best-effort revocation of a forgotten session (ADR 0103): the
// public probe goes **without** the token, which is only sent to the server with the
// saved id, like a restore (ADR 0012).
type Revocation struct {
	client   *Client
	serverID string
	token    AccessToken
	log      *slog.Logger
}

// Send is `send`; a failure is only logged.
func (r *Revocation) Send(ctx context.Context) {
	server, err := summary(ctx, r.client)
	switch {
	case err != nil:
	case server.ID == r.serverID:
		err = r.client.WithToken(r.token).Logout(ctx)
	default:
		err = ErrServerMismatch
	}
	if err != nil {
		r.log.Info("forgotten session not revoked on the server", "kind", kindText(err))
	}
}

func summary(ctx context.Context, client *Client) (ServerSummary, error) {
	info, err := client.PublicInfo(ctx)
	if err != nil {
		return ServerSummary{}, err
	}
	return ServerSummary{
		URL: client.Server().String(), Name: info.ServerName, Version: info.Version, ID: info.ID,
		Encrypted: client.Server().IsHTTPS(),
	}, nil
}
