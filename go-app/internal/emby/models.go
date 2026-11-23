package emby

// Emby API models, port of src-tauri/src/emby/models.rs, limited to the
// fields this client uses. Shapes verified against the OpenAPI spec served by Emby
// Server 4.10.1.0 and its actual responses.
//
// Serde mapping:
//   - field order is the Rust struct order: encoding/json keeps it, and the frontend
//     sees the same keys in the same order;
//   - `Option<T>` without skip is a pointer written as null; `skip_serializing_if =
//     "Option::is_none"` is a pointer with `omitzero` (absent when nil);
//   - a `Vec` sent to the UI is a List, written [] when nil, as serde writes an empty Vec;
//   - Emby DTOs (Deserialize only) carry PascalCase tags. encoding/json matches keys
//     without case and reads null as the zero value, where serde is exact and refuses
//     null for a non-Option field: a stricter decoder is a wave-2 choice (MAP.md).

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"strconv"
	"strings"
	"unicode"
	"unicode/utf8"
)

// List is a Vec sent to the UI: nil is written [], never null.
type List[T any] []T

// MarshalJSON writes [] for a nil list (serde never writes null for a Vec).
func (l List[T]) MarshalJSON() ([]byte, error) {
	if l == nil {
		return []byte("[]"), nil
	}
	return json.Marshal([]T(l))
}

// ----------------------------------------------------------------- Emby DTOs

// PublicSystemInfo is `GET /System/Info/Public` (no authentication required in practice).
// Every field is required.
type PublicSystemInfo struct {
	ServerName string `json:"ServerName" emby:"required"`
	Version    string `json:"Version" emby:"required"`
	ID         string `json:"Id" emby:"required"`
}

// AuthenticateUserByName is the `POST /Users/AuthenticateByName` request body.
type AuthenticateUserByName struct {
	Username string `json:"Username"`
	Pw       string `json:"Pw"`
}

// AuthenticationResult is the `POST /Users/AuthenticateByName` response. Every field
// is required. Its String and GoString never print the token.
type AuthenticationResult struct {
	User        UserDto `json:"User" emby:"required"`
	AccessToken string  `json:"AccessToken" emby:"required"`
	ServerID    string  `json:"ServerId" emby:"required"`
}

func (a AuthenticationResult) String() string {
	return fmt.Sprintf("AuthenticationResult{user: %s %q, access_token: <redacted>, server_id: %q}", a.User.ID, a.User.Name, a.ServerID)
}

// GoString keeps %#v from printing the token.
func (a AuthenticationResult) GoString() string { return a.String() }

// UserDto is an Emby user. Id and Name are required.
type UserDto struct {
	ID     string      `json:"Id" emby:"required"`
	Name   string      `json:"Name" emby:"required"`
	Policy *UserPolicy `json:"Policy"`
	// Configuration is an object, or an array of one as the documentation types it:
	// read by the tracks unit, which keeps four fields only.
	Configuration json.RawMessage `json:"Configuration"`
}

type UserPolicy struct {
	IsAdministrator bool `json:"IsAdministrator"`
	IsDisabled      bool `json:"IsDisabled"`
}

// QueryResult is `QueryResult_BaseItemDto` from `/Users/{Id}/Views` and `/Users/{Id}/Items`.
// Paging past the end returns `TotalRecordCount: 0` (Emby 4.10.1.0).
type QueryResult[T any] struct {
	Items            []T    `json:"Items"`
	TotalRecordCount uint32 `json:"TotalRecordCount"`
}

// HomeSectionDto is one section of the user's home screen as the server composes it
// (`/Users/{Id}/HomeSections`, a bare array on Emby 4.10.1.0). Id is required.
type HomeSectionDto struct {
	ID          string `json:"Id" emby:"required"`
	SectionType string `json:"SectionType"`
	// IncludeNextUpInResume: the "resume" section also lists the next episode of each
	// series in progress.
	IncludeNextUpInResume bool `json:"IncludeNextUpInResume"`
}

// BaseItemDto is the subset of `BaseItemDto` used by library views and grids. Ids are
// opaque strings (numeric on Emby 4.10.1.0). Id is required. `Path` is never read: it
// is the file path on the server.
type BaseItemDto struct {
	ID       string `json:"Id" emby:"required"`
	Name     string `json:"Name"`
	ItemType string `json:"Type"`
	// CollectionType of a library view.
	CollectionType *string `json:"CollectionType"`
	// MediaType is `Video` or `Audio` on a playlist.
	MediaType *string `json:"MediaType"`
	// ChildCount only when asked in `Fields`: the entries of a playlist.
	ChildCount *uint32 `json:"ChildCount"`
	// SortName only when asked in `Fields` (letter index).
	SortName                *string           `json:"SortName"`
	ProductionYear          *int32            `json:"ProductionYear"`
	ImageTags               map[string]string `json:"ImageTags"`
	PrimaryImageAspectRatio *float64          `json:"PrimaryImageAspectRatio"`
	RunTimeTicks            *int64            `json:"RunTimeTicks"`
	UserData                *UserItemData     `json:"UserData"`
	// Detail fields: `/Users/{Id}/Items/{Id}` returns them without `Fields`; list
	// endpoints only when asked.
	OriginalTitle *string  `json:"OriginalTitle"`
	Overview      *string  `json:"Overview"`
	Taglines      []string `json:"Taglines"`
	Genres        []string `json:"Genres"`
	// Studios: `Studios[].Id` is a number on Emby 4.10.1.0, unlike other ids: only names are read.
	Studios []NameDto   `json:"Studios"`
	People  []PersonDto `json:"People"`
	// Collections: `[{Name, Id}]` of the collections holding the item.
	Collections json.RawMessage `json:"Collections"`
	// LocalTrailerCount: trailer files stored with the item.
	LocalTrailerCount *uint32  `json:"LocalTrailerCount"`
	OfficialRating    *string  `json:"OfficialRating"`
	CommunityRating   *float64 `json:"CommunityRating"`
	CriticRating      *float64 `json:"CriticRating"`
	// PremiereDate is ISO 8601 in UTC, e.g. `2024-07-04T22:00:00.0000000Z` for a 5 July premiere.
	PremiereDate *string `json:"PremiereDate"`
	EndDate      *string `json:"EndDate"`
	// Status, series only: `Ended` or `Continuing`.
	Status            *string  `json:"Status"`
	BackdropImageTags []string `json:"BackdropImageTags"`
	IndexNumber       *uint32  `json:"IndexNumber"`
	IndexNumberEnd    *uint32  `json:"IndexNumberEnd"`
	ParentIndexNumber *uint32  `json:"ParentIndexNumber"`
	SeriesID          *string  `json:"SeriesId"`
	SeasonID          *string  `json:"SeasonId"`
	// Episodes: the series' artwork, used when the episode has no still.
	SeriesName              *string  `json:"SeriesName"`
	ParentThumbItemID       *string  `json:"ParentThumbItemId"`
	ParentThumbImageTag     *string  `json:"ParentThumbImageTag"`
	ParentBackdropItemID    *string  `json:"ParentBackdropItemId"`
	ParentBackdropImageTags []string `json:"ParentBackdropImageTags"`
	// Episodes: the series' logo and poster, for the home hero.
	ParentLogoItemID      *string          `json:"ParentLogoItemId"`
	ParentLogoImageTag    *string          `json:"ParentLogoImageTag"`
	SeriesPrimaryImageTag *string          `json:"SeriesPrimaryImageTag"`
	MediaSources          []MediaSourceDto `json:"MediaSources"`
	// ProviderIDs: `Imdb`, `Tmdb`, `Tvdb`…; the key case varies (`IMDB` on a series,
	// Emby 4.10.2.0). Sent to the intro services only.
	ProviderIDs map[string]string `json:"ProviderIds"`
	Chapters    []ChapterDto      `json:"Chapters"`
}

// ChapterDto is `ChapterInfo`: `MarkerType` is `Chapter`, `IntroStart`, `IntroEnd` or
// `CreditsStart`. `Chapter` ones are the player's chapters.
type ChapterDto struct {
	StartPositionTicks int64   `json:"StartPositionTicks"`
	Name               *string `json:"Name"`
	MarkerType         *string `json:"MarkerType"`
}

type UserItemData struct {
	Played                bool    `json:"Played"`
	UnplayedItemCount     *uint32 `json:"UnplayedItemCount"`
	PlaybackPositionTicks int64   `json:"PlaybackPositionTicks"`
	IsFavorite            bool    `json:"IsFavorite"`
}

type NameDto struct {
	Name string `json:"Name"`
}

type PersonDto struct {
	Name string  `json:"Name"`
	Role *string `json:"Role"`
	// PersonType: `Actor`, `Director`, `Writer`, `Producer`, `GuestStar`…
	PersonType string `json:"Type"`
	// ID is documented as a string; read as any value so that a number would not make
	// the whole item unreadable, as `Studios[].Id`. nil or null: no id.
	ID json.RawMessage `json:"Id"`
	// PrimaryImageTag: tag of the person's photo, the `Primary` image of the person item.
	PrimaryImageTag *string `json:"PrimaryImageTag"`
}

// MediaSourceDto is one file (version) of an item. `Path` is left out on purpose.
type MediaSourceDto struct {
	// ID is what PlaybackInfo's `MediaSourceId` names.
	ID           *string          `json:"Id"`
	Name         *string          `json:"Name"`
	Container    *string          `json:"Container"`
	Size         *uint64          `json:"Size"`
	Bitrate      *uint64          `json:"Bitrate"`
	MediaStreams []MediaStreamDto `json:"MediaStreams"`
}

type MediaStreamDto struct {
	// StreamType: `Video`, `Audio`, `Subtitle`, `EmbeddedImage`, `Attachment`…
	StreamType string `json:"Type"`
	Index      *int32 `json:"Index"`
	// DeliveryMethod, PlaybackInfo only: how a subtitle reaches the client (`Embed`,
	// `External`, `Encode`, `Hls`).
	DeliveryMethod *string `json:"DeliveryMethod"`
	// DeliveryURL, PlaybackInfo only, with `External`: relative URL holding the token in `api_key`.
	DeliveryURL *string `json:"DeliveryUrl"`
	Codec       *string `json:"Codec"`
	// Language: ISO 639 as stored in the file: `fre`, `fra`, `fr`, `und`… all seen on one server.
	Language   *string `json:"Language"`
	Title      *string `json:"Title"`
	IsDefault  bool    `json:"IsDefault"`
	IsForced   bool    `json:"IsForced"`
	IsExternal bool    `json:"IsExternal"`
	Channels   *uint32 `json:"Channels"`
	Width      *uint32 `json:"Width"`
	Height     *uint32 `json:"Height"`
	BitDepth   *uint32 `json:"BitDepth"`
	// VideoRange: `SDR`, `HDR 10`, `DolbyVision`.
	VideoRange *string `json:"VideoRange"`
	// ExtendedVideoSubTypeDescription: `HDR 10`, `Profile 8.1 (HDR10 compatible)`, `None`.
	ExtendedVideoSubTypeDescription *string `json:"ExtendedVideoSubTypeDescription"`
}

// PlaybackInfoResponse is the `POST /Items/{Id}/PlaybackInfo` response.
type PlaybackInfoResponse struct {
	MediaSources  []MediaSourceInfo `json:"MediaSources"`
	PlaySessionID *string           `json:"PlaySessionId"`
	// ErrorCode: `NotAllowed`, `NoCompatibleStream`, `RateLimitExceeded`.
	ErrorCode *string `json:"ErrorCode"`
}

// MediaSourceInfo is one playable source with the server's decision. Id is required.
// `Path` is left out on purpose; the stream URLs carry the token in `api_key`
// (removed before use).
type MediaSourceInfo struct {
	ID string `json:"Id" emby:"required"`
	// Protocol: `File`, `Http`…
	Protocol             *string `json:"Protocol"`
	Container            *string `json:"Container"`
	RunTimeTicks         *int64  `json:"RunTimeTicks"`
	SupportsDirectPlay   bool    `json:"SupportsDirectPlay"`
	SupportsDirectStream bool    `json:"SupportsDirectStream"`
	SupportsTranscoding  bool    `json:"SupportsTranscoding"`
	DirectStreamURL      *string `json:"DirectStreamUrl"`
	TranscodingURL       *string `json:"TranscodingUrl"`
	// TranscodingSubProtocol: `hls` for a transcode.
	TranscodingSubProtocol *string `json:"TranscodingSubProtocol"`
	TranscodingContainer   *string `json:"TranscodingContainer"`
	// RequiredHTTPHeaders: headers the stream needs, in addition to authentication.
	RequiredHTTPHeaders map[string]string `json:"RequiredHttpHeaders" emby:"option"`
	// DefaultAudioStreamIndex: audio the server picked from the user's preferences.
	DefaultAudioStreamIndex *int32 `json:"DefaultAudioStreamIndex"`
	// DefaultSubtitleStreamIndex: subtitle the server picked from the user's preferences.
	DefaultSubtitleStreamIndex *int32           `json:"DefaultSubtitleStreamIndex"`
	MediaStreams               []MediaStreamDto `json:"MediaStreams"`
}

// AddToPlaylistResultDto is `AddToPlaylistResult` of `POST /Playlists/{Id}/Items`.
type AddToPlaylistResultDto struct {
	ItemAddedCount uint32 `json:"ItemAddedCount"`
}

// PlaylistCreationResultDto is `PlaylistCreationResult` of `POST /Playlists`. Id is required.
type PlaylistCreationResultDto struct {
	ID             string `json:"Id" emby:"required"`
	Name           string `json:"Name"`
	ItemAddedCount uint32 `json:"ItemAddedCount"`
}

// SessionInfoDto is one entry of `GET /Sessions`: the fields linked
// playback reads. Id is required.
type SessionInfoDto struct {
	ID                    string  `json:"Id" emby:"required"`
	DeviceID              *string `json:"DeviceId"`
	DeviceName            *string `json:"DeviceName"`
	Client                *string `json:"Client"`
	SupportsRemoteControl bool    `json:"SupportsRemoteControl"`
}

// ------------------------------------------------------- Emby request bodies

// PlaybackInfoRequest is the `POST /Items/{Id}/PlaybackInfo` request body.
type PlaybackInfoRequest struct {
	UserID              string         `json:"UserId"`
	MaxStreamingBitrate uint64         `json:"MaxStreamingBitrate"`
	DeviceProfile       *DeviceProfile `json:"DeviceProfile"`
	// IsPlayback: the answer is used to start playback, not only to inspect the item.
	IsPlayback         bool `json:"IsPlayback"`
	AutoOpenLiveStream bool `json:"AutoOpenLiveStream"`
	// EnableDirectPlay and EnableDirectStream both false ask for a transcode: the
	// fallback when the player could not open what the server offered.
	EnableDirectPlay   bool `json:"EnableDirectPlay"`
	EnableDirectStream bool `json:"EnableDirectStream"`
	// MediaSourceID and AudioStreamIndex: the audio a transcode must carry.
	// Emby 4.10.1.0 ignores `AudioStreamIndex` without `MediaSourceId`.
	MediaSourceID    *string `json:"MediaSourceId,omitzero"`
	AudioStreamIndex *int32  `json:"AudioStreamIndex,omitzero"`
}

// DeviceProfile is what the player can play, as Emby's `DeviceProfile` describes it.
// Comma separated lists, as the server expects.
type DeviceProfile struct {
	Name                string                   `json:"Name"`
	MaxStreamingBitrate uint64                   `json:"MaxStreamingBitrate"`
	DirectPlayProfiles  List[DirectPlayProfile]  `json:"DirectPlayProfiles"`
	TranscodingProfiles List[TranscodingProfile] `json:"TranscodingProfiles"`
	SubtitleProfiles    List[SubtitleProfile]    `json:"SubtitleProfiles"`
}

type DirectPlayProfile struct {
	Kind       string `json:"Type"`
	Container  string `json:"Container"`
	VideoCodec string `json:"VideoCodec"`
	AudioCodec string `json:"AudioCodec"`
}

type TranscodingProfile struct {
	Kind                string `json:"Type"`
	Container           string `json:"Container"`
	Protocol            string `json:"Protocol"`
	VideoCodec          string `json:"VideoCodec"`
	AudioCodec          string `json:"AudioCodec"`
	Context             string `json:"Context"`
	MaxAudioChannels    string `json:"MaxAudioChannels"`
	BreakOnNonKeyFrames bool   `json:"BreakOnNonKeyFrames"`
}

type SubtitleProfile struct {
	Format string `json:"Format"`
	// Method: `Embed`, `External`, `Encode`, `Hls`.
	Method string `json:"Method"`
}

// PlaybackReportBody is the body of `POST /Sessions/Playing`, `/Sessions/Playing/Progress`
// and `/Sessions/Playing/Stopped`. Fields a report does not use are omitted.
type PlaybackReportBody struct {
	ItemID        string  `json:"ItemId"`
	MediaSourceID string  `json:"MediaSourceId"`
	PlaySessionID *string `json:"PlaySessionId,omitzero"`
	PositionTicks int64   `json:"PositionTicks"`
	RunTimeTicks  *int64  `json:"RunTimeTicks,omitzero"`
	IsPaused      *bool   `json:"IsPaused,omitzero"`
	CanSeek       *bool   `json:"CanSeek,omitzero"`
	PlayMethod    *string `json:"PlayMethod,omitzero"`
	EventName     *string `json:"EventName,omitzero"`
	Failed        *bool   `json:"Failed,omitzero"`
}

// ------------------------------------------------------------ IPC to the UI

// ServerSummary is what the UI learns about a server before signing in.
type ServerSummary struct {
	URL     string `json:"url"`
	Name    string `json:"name"`
	Version string `json:"version"`
	ID      string `json:"id"`
	// Encrypted is false for plain HTTP (allowed on the local network only).
	Encrypted bool `json:"encrypted"`
}

// SignedInUser is the signed-in user as exposed to the UI: never the token.
type SignedInUser struct {
	Server          ServerSummary `json:"server"`
	UserID          string        `json:"userId"`
	UserName        string        `json:"userName"`
	IsAdministrator bool          `json:"isAdministrator"`
}

// SavedServer is a session known from `session.json`, shown on the recovery,
// reauthentication and server screens. Contains no secret.
type SavedServer struct {
	URL       string `json:"url"`
	ID        string `json:"id"`
	Name      string `json:"name"`
	Version   string `json:"version"`
	Encrypted bool   `json:"encrypted"`
	UserID    string `json:"userId"`
	UserName  string `json:"userName"`
}

// ShelfAccess is what a tile of the pre-connection shelf can do.
type ShelfAccess string

const (
	// ShelfSession: a token is in the credential store: the tile validates it, no password.
	ShelfSession ShelfAccess = "session"
	// ShelfRejected: the server already rejected this session: "Mot de passe requis".
	ShelfRejected ShelfAccess = "rejected"
	// ShelfSignedOut: signed out on purpose: the password is asked, no rejection was seen.
	ShelfSignedOut ShelfAccess = "signedOut"
)

// ShelfServer is one tile of the pre-connection shelf. Contains no secret.
type ShelfServer struct {
	Server SavedServer `json:"server"`
	Access ShelfAccess `json:"access"`
}

// SessionState is the `state` tag of SessionStatus.
type SessionState string

const (
	StateSignedOut              SessionState = "signedOut"
	StateAuthenticated          SessionState = "authenticated"
	StateServerUnreachable      SessionState = "serverUnreachable"
	StateAuthenticationRequired SessionState = "authenticationRequired"
)

// SessionStatus is the outcome of validating the saved session. Network
// failures keep the session; only a rejection by the server asks for the password
// again. Serialized as serde's internally tagged enum: `state` first, then the
// fields of the variant. Build it with the Status* functions.
type SessionStatus struct {
	State SessionState
	// User, for StateAuthenticated.
	User *SignedInUser
	// Server and Err, for StateServerUnreachable and StateAuthenticationRequired.
	Server *SavedServer
	Err    *EmbyError
}

func StatusSignedOut() SessionStatus { return SessionStatus{State: StateSignedOut} }

func StatusAuthenticated(user SignedInUser) SessionStatus {
	return SessionStatus{State: StateAuthenticated, User: &user}
}

func StatusServerUnreachable(server SavedServer, err EmbyError) SessionStatus {
	return SessionStatus{State: StateServerUnreachable, Server: &server, Err: &err}
}

func StatusAuthenticationRequired(server SavedServer, err EmbyError) SessionStatus {
	return SessionStatus{State: StateAuthenticationRequired, Server: &server, Err: &err}
}

func (s SessionStatus) MarshalJSON() ([]byte, error) {
	switch s.State {
	case StateSignedOut:
		return json.Marshal(struct {
			State SessionState `json:"state"`
		}{s.State})
	case StateAuthenticated:
		if s.User == nil {
			return nil, errors.New("SessionStatus authenticated sans utilisateur")
		}
		return json.Marshal(struct {
			State SessionState `json:"state"`
			User  SignedInUser `json:"user"`
		}{s.State, *s.User})
	case StateServerUnreachable, StateAuthenticationRequired:
		if s.Server == nil || s.Err == nil {
			return nil, fmt.Errorf("SessionStatus %s sans serveur ou sans erreur", s.State)
		}
		return json.Marshal(struct {
			State  SessionState `json:"state"`
			Server SavedServer  `json:"server"`
			Error  EmbyError    `json:"error"`
		}{s.State, *s.Server, *s.Err})
	default:
		return nil, fmt.Errorf("SessionStatus : état inconnu %q", s.State)
	}
}

// LibraryKind is a library kind this client browses. Other collection types are not listed yet.
type LibraryKind string

const (
	LibraryMovies  LibraryKind = "movies"
	LibraryTvShows LibraryKind = "tvShows"
)

// LibraryKindFromCollectionType maps Emby's `CollectionType`.
func LibraryKindFromCollectionType(value *string) (LibraryKind, bool) {
	if value == nil {
		return "", false
	}
	switch *value {
	case "movies":
		return LibraryMovies, true
	case "tvshows":
		return LibraryTvShows, true
	}
	return "", false
}

// ItemTypes is `IncludeItemTypes` for the grid: films or series, not episodes or folders.
func (k LibraryKind) ItemTypes() string {
	if k == LibraryTvShows {
		return "Series"
	}
	return "Movie"
}

// SortKey is the order of a library grid. The zero value is SortName,
// the serde default.
type SortKey int

const (
	SortName SortKey = iota
	SortDateAdded
	SortYear
	SortRating
)

var sortKeyNames = [...]string{"name", "dateAdded", "year", "rating"}

func (k SortKey) String() string {
	if int(k) >= 0 && int(k) < len(sortKeyNames) {
		return sortKeyNames[k]
	}
	return "SortKey(" + strconv.Itoa(int(k)) + ")"
}

// UnmarshalJSON accepts the camelCase names only, as serde.
func (k *SortKey) UnmarshalJSON(b []byte) error {
	i, err := unitVariant(b, sortKeyNames[:], "SortKey")
	if err == nil {
		*k = SortKey(i)
	}
	return err
}

// SortBy is `SortBy`; the title breaks ties so pages stay stable.
func (k SortKey) SortBy() string {
	switch k {
	case SortDateAdded:
		return "DateCreated,SortName"
	case SortYear:
		return "ProductionYear,SortName"
	case SortRating:
		return "CommunityRating,SortName"
	default:
		return "SortName"
	}
}

// WatchFilter is the watched state kept in a library grid. The zero value
// is WatchAll, the serde default.
type WatchFilter int

const (
	WatchAll WatchFilter = iota
	WatchUnplayed
	WatchPlayed
	WatchInProgress
)

var watchFilterNames = [...]string{"all", "unplayed", "played", "inProgress"}

func (w WatchFilter) String() string {
	if int(w) >= 0 && int(w) < len(watchFilterNames) {
		return watchFilterNames[w]
	}
	return "WatchFilter(" + strconv.Itoa(int(w)) + ")"
}

// UnmarshalJSON accepts the camelCase names only, as serde.
func (w *WatchFilter) UnmarshalJSON(b []byte) error {
	i, err := unitVariant(b, watchFilterNames[:], "WatchFilter")
	if err == nil {
		*w = WatchFilter(i)
	}
	return err
}

// unitVariant decodes a serde unit variant: a JSON string among names.
func unitVariant(b []byte, names []string, typ string) (int, error) {
	var s string
	if bytes.Equal(bytes.TrimSpace(b), []byte("null")) {
		return 0, fmt.Errorf("%s : null refusé", typ)
	}
	if err := json.Unmarshal(b, &s); err != nil {
		return 0, fmt.Errorf("%s : %w", typ, err)
	}
	for i, n := range names {
		if n == s {
			return i, nil
		}
	}
	return 0, fmt.Errorf("%s : variante inconnue %q", typ, s)
}

// ItemsOptions holds the sort and filters of a library grid, as the UI chooses them.
// Every key is optional; the zero value is the default.
type ItemsOptions struct {
	Sort       SortKey
	Descending bool
	Watch      WatchFilter
	Favorites  bool
	Genre      *string
	// Decade is the first year of a decade (`1990`), or nil for every year.
	Decade *int32
}

// DefaultItemsOptions is `ItemsOptions::default()`.
func DefaultItemsOptions() ItemsOptions { return ItemsOptions{} }

// UnmarshalJSON follows serde's `#[serde(rename_all = "camelCase", default)]`: exact
// key names, missing keys keep the default, unknown keys are ignored, null is refused
// for the non-Option fields and means "none" for genre and decade.
func (o *ItemsOptions) UnmarshalJSON(b []byte) error {
	var raw map[string]json.RawMessage
	if bytes.Equal(bytes.TrimSpace(b), []byte("null")) {
		return errors.New("ItemsOptions : null refusé")
	}
	if err := json.Unmarshal(b, &raw); err != nil {
		return fmt.Errorf("ItemsOptions : %w", err)
	}
	out := ItemsOptions{}
	for key, v := range raw {
		var err error
		switch key {
		case "sort":
			err = json.Unmarshal(v, &out.Sort)
		case "descending":
			err = strictBool(v, &out.Descending)
		case "watch":
			err = json.Unmarshal(v, &out.Watch)
		case "favorites":
			err = strictBool(v, &out.Favorites)
		case "genre":
			out.Genre, err = optional[string](v)
		case "decade":
			out.Decade, err = optional[int32](v)
		}
		if err != nil {
			return fmt.Errorf("ItemsOptions.%s : %w", key, err)
		}
	}
	*o = out
	return nil
}

func strictBool(v json.RawMessage, dst *bool) error {
	if bytes.Equal(bytes.TrimSpace(v), []byte("null")) {
		return errors.New("null refusé")
	}
	return json.Unmarshal(v, dst)
}

// optional decodes an `Option<T>`: null is nil.
func optional[T any](v json.RawMessage) (*T, error) {
	if bytes.Equal(bytes.TrimSpace(v), []byte("null")) {
		return nil, nil
	}
	var out T
	if err := json.Unmarshal(v, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

// SortOrder is `SortOrder`.
func (o ItemsOptions) SortOrder() string {
	if o.Descending {
		return "Descending"
	}
	return "Ascending"
}

// Filters is `Filters`, empty when nothing is filtered.
func (o ItemsOptions) Filters() string {
	var parts []string
	switch o.Watch {
	case WatchUnplayed:
		parts = append(parts, "IsUnplayed")
	case WatchPlayed:
		parts = append(parts, "IsPlayed")
	case WatchInProgress:
		parts = append(parts, "IsResumable")
	}
	if o.Favorites {
		parts = append(parts, "IsFavorite")
	}
	return strings.Join(parts, ",")
}

// Years is `Years` of the decade; false without one or with an unusable one.
func (o ItemsOptions) Years() (string, bool) {
	if o.Decade == nil || !IsUsableDecade(*o.Decade) {
		return "", false
	}
	d := *o.Decade
	years := make([]string, 0, 10)
	for y := d; y < d+10; y++ {
		years = append(years, strconv.Itoa(int(y)))
	}
	return strings.Join(years, ","), true
}

// IsUsableDecade: a decade the server can filter on, a multiple of ten in a sane range.
func IsUsableDecade(decade int32) bool {
	return decade%10 == 0 && decade >= 1880 && decade <= 2190
}

// SortNameEntry is one title of a library in grid order, for the letter index.
type SortNameEntry struct {
	ID string `json:"id"`
	// Name is `SortName`, or `Name` when the server sends none.
	Name string `json:"name"`
}

// MaxGenreChars is the longest genre name sent to the server, in characters.
const MaxGenreChars = 100

// IsUsableGenre: a genre the server can filter on: `Genres` is `|`-separated on Emby.
func IsUsableGenre(name string) bool {
	if strings.TrimSpace(name) == "" || utf8.RuneCountInString(name) > MaxGenreChars {
		return false
	}
	return !strings.ContainsFunc(name, func(r rune) bool { return r == '|' || unicode.IsControl(r) })
}

// ImageRef is an image ready for `<img>`: URLs point at the local `emby-img`
// protocol.
type ImageRef struct {
	Src    string `json:"src"`
	Srcset string `json:"srcset"`
}

type Library struct {
	ID    string      `json:"id"`
	Name  string      `json:"name"`
	Kind  LibraryKind `json:"kind"`
	Image *ImageRef   `json:"image"`
}

type MediaItem struct {
	ID   string `json:"id"`
	Name string `json:"name"`
	// ItemType is `"Movie"` or `"Series"`.
	ItemType       string    `json:"itemType"`
	Year           *int32    `json:"year"`
	RuntimeMinutes *uint32   `json:"runtimeMinutes"`
	Poster         *ImageRef `json:"poster"`
	// AspectRatio is width / height of the poster, when the server knows it.
	AspectRatio *float64 `json:"aspectRatio"`
	Played      bool     `json:"played"`
	// UnplayedCount, series only: episodes not yet watched.
	UnplayedCount *uint32 `json:"unplayedCount"`
	// Favorite labels the card menu's favourite action.
	Favorite bool `json:"favorite"`
}

// WatchKind is the kind of item the home rows "Reprendre" and "À suivre" play.
type WatchKind string

const (
	WatchMovie   WatchKind = "movie"
	WatchEpisode WatchKind = "episode"
)

// WatchItem is a film or episode of the home rows "Reprendre" and "À suivre":
// the card plays it, so it carries what the label needs and a 16:9 image.
type WatchItem struct {
	ID   string    `json:"id"`
	Name string    `json:"name"`
	Kind WatchKind `json:"kind"`
	// SeriesName, SeasonIndex and Index: episodes only.
	SeriesName  *string `json:"seriesName"`
	SeasonIndex *uint32 `json:"seasonIndex"`
	Index       *uint32 `json:"index"`
	// Image: episode still, else the series or film thumb, else its backdrop.
	Image          *ImageRef    `json:"image"`
	RuntimeMinutes *uint32      `json:"runtimeMinutes"`
	Resume         *ResumePoint `json:"resume"`
	// Played and Favorite label the card menu's actions.
	Played   bool `json:"played"`
	Favorite bool `json:"favorite"`
}

// SearchResults is the answer of a search: films, series and
// episodes whose title matches `query`.
type SearchResults struct {
	// Query is the query as sent to the server, after cleaning; empty when too short.
	Query  string          `json:"query"`
	Movies List[MediaItem] `json:"movies"`
	Series List[MediaItem] `json:"series"`
	// Episodes: 16:9 cards, as on the home page, with their series and numbers.
	Episodes List[WatchItem] `json:"episodes"`
	// MoreMovies: more films than shown: the server's total is not reliable with `SearchTerm`.
	MoreMovies   bool `json:"moreMovies"`
	MoreSeries   bool `json:"moreSeries"`
	MoreEpisodes bool `json:"moreEpisodes"`
}

type ItemPage struct {
	Items      List[MediaItem] `json:"items"`
	StartIndex uint32          `json:"startIndex"`
	// NextIndex is where the next page starts: StartIndex plus what the server
	// returned, even if some unusable items were dropped from Items.
	NextIndex uint32 `json:"nextIndex"`
	Total     uint32 `json:"total"`
}

// HeroItem is one title of the home hero: a wide backdrop, what the main
// button plays, and the film or series that "Plus d'infos" opens.
type HeroItem struct {
	// Detail is the film or series of the detail page; for a resumed episode, its series.
	Detail   MediaItem  `json:"detail"`
	Reason   HeroReason `json:"reason"`
	Backdrop ImageRef   `json:"backdrop"`
	Logo     *ImageRef  `json:"logo"`
	Overview *string    `json:"overview"`
	// PlayID is the film or episode the main button plays; nil when nothing is left to watch.
	PlayID *string `json:"playId"`
	// Episode, when the main button plays an episode.
	Episode *HeroEpisode `json:"episode"`
	Resume  *ResumePoint `json:"resume"`
}

type HeroReason string

const (
	// HeroResume: started but not finished ("Reprendre").
	HeroResume HeroReason = "resume"
	// HeroLatest: recently added, not watched.
	HeroLatest HeroReason = "latest"
)

type HeroEpisode struct {
	Name        string  `json:"name"`
	SeasonIndex *uint32 `json:"seasonIndex"`
	Index       *uint32 `json:"index"`
}

// DetailKind is a kind of item a detail page shows. Episodes open from their series.
type DetailKind string

const (
	DetailMovie   DetailKind = "movie"
	DetailSeries  DetailKind = "series"
	DetailEpisode DetailKind = "episode"
)

// EpisodeContext is where an episode sits in its series, and its neighbours.
type EpisodeContext struct {
	SeriesID   string  `json:"seriesId"`
	SeriesName string  `json:"seriesName"`
	SeasonID   *string `json:"seasonId"`
	// SeasonIndex: `0` is the specials season.
	SeasonIndex *uint32 `json:"seasonIndex"`
	Index       *uint32 `json:"index"`
	IndexEnd    *uint32 `json:"indexEnd"`
	// Previous episode of the series, across seasons, in server order.
	Previous *EpisodeLink `json:"previous"`
	Next     *EpisodeLink `json:"next"`
}

type EpisodeLink struct {
	ID          string  `json:"id"`
	Name        string  `json:"name"`
	SeasonIndex *uint32 `json:"seasonIndex"`
	Index       *uint32 `json:"index"`
	IndexEnd    *uint32 `json:"indexEnd"`
}

// ResumePoint is where playback stopped, for an item started but not finished.
type ResumePoint struct {
	PositionSeconds uint32 `json:"positionSeconds"`
	// Fraction is `0.0..=1.0` of the runtime, when the runtime is known.
	Fraction *float64 `json:"fraction"`
}

type CastMember struct {
	Name string  `json:"name"`
	Role *string `json:"role"`
	// ID is the person item, for its page; nil without a usable id.
	ID    *string   `json:"id"`
	Photo *ImageRef `json:"photo"`
}

type VideoInfo struct {
	Codec    *string `json:"codec"`
	Width    *uint32 `json:"width"`
	Height   *uint32 `json:"height"`
	BitDepth *uint32 `json:"bitDepth"`
	// Range: `SDR`, `HDR 10`, `Dolby Vision Profile 8.1 (HDR10 compatible)`…
	Range *string `json:"range"`
}

// StreamInfo is an audio or subtitle track as described by the server, before any
// playback decision.
type StreamInfo struct {
	Language *string `json:"language"`
	Codec    *string `json:"codec"`
	Title    *string `json:"title"`
	// Channels: audio only.
	Channels   *uint32 `json:"channels"`
	IsDefault  bool    `json:"isDefault"`
	IsForced   bool    `json:"isForced"`
	IsExternal bool    `json:"isExternal"`
}

// MediaInfo is one version of the item: technical information only. Whether it
// plays directly is decided later from PlaybackInfo, never from these fields.
type MediaInfo struct {
	// ID is passed back to `emby_play` to play this version; nil when the
	// server gave no usable id.
	ID        *string          `json:"id"`
	Name      *string          `json:"name"`
	Container *string          `json:"container"`
	SizeBytes *uint64          `json:"sizeBytes"`
	Bitrate   *uint64          `json:"bitrate"`
	Video     *VideoInfo       `json:"video"`
	Audio     List[StreamInfo] `json:"audio"`
	Subtitles List[StreamInfo] `json:"subtitles"`
}

type ItemDetail struct {
	ID   string     `json:"id"`
	Kind DetailKind `json:"kind"`
	Name string     `json:"name"`
	// OriginalTitle only when it differs from Name.
	OriginalTitle *string `json:"originalTitle"`
	Year          *int32  `json:"year"`
	// EndYear, series only, from `EndDate`.
	EndYear *int32 `json:"endYear"`
	// Continuing, series only: true while new episodes are expected.
	Continuing *bool        `json:"continuing"`
	Overview   *string      `json:"overview"`
	Tagline    *string      `json:"tagline"`
	Genres     List[string] `json:"genres"`
	Studios    List[string] `json:"studios"`
	Directors  List[string] `json:"directors"`
	Writers    List[string] `json:"writers"`
	// Cast: actors; guest stars for an episode.
	Cast           List[CastMember] `json:"cast"`
	OfficialRating *string          `json:"officialRating"`
	// CommunityRating: out of 10, one decimal.
	CommunityRating *float64 `json:"communityRating"`
	// CriticRating: out of 100.
	CriticRating *uint32 `json:"criticRating"`
	// RuntimeMinutes: movie and episode only.
	RuntimeMinutes *uint32 `json:"runtimeMinutes"`
	PremiereDate   *string `json:"premiereDate"`
	// Poster: the series poster for an episode.
	Poster *ImageRef `json:"poster"`
	// Backdrop: the episode's own image for an episode, else the series backdrop.
	Backdrop *ImageRef `json:"backdrop"`
	// Logo: the series logo for an episode.
	Logo   *ImageRef `json:"logo"`
	Played bool      `json:"played"`
	// Favorite: in the user's favorites.
	Favorite bool         `json:"favorite"`
	Resume   *ResumePoint `json:"resume"`
	// UnplayedCount: series only.
	UnplayedCount *uint32 `json:"unplayedCount"`
	// Media: movie and episode only; empty for a series.
	Media List[MediaInfo] `json:"media"`
	// Episode: episode only.
	Episode *EpisodeContext `json:"episode"`
	// Collections holding the film or series, at most three.
	Collections List[CollectionRef] `json:"collections"`
	// HasLocalTrailer: a trailer file is stored with the item.
	HasLocalTrailer bool `json:"hasLocalTrailer"`
}

type Season struct {
	ID   string `json:"id"`
	Name string `json:"name"`
	// Index: `0` is the specials season on Emby.
	Index         *uint32 `json:"index"`
	UnplayedCount *uint32 `json:"unplayedCount"`
	Played        bool    `json:"played"`
}

type Episode struct {
	ID    string  `json:"id"`
	Name  string  `json:"name"`
	Index *uint32 `json:"index"`
	// IndexEnd: last number of a double episode (`IndexNumberEnd`).
	IndexEnd       *uint32 `json:"indexEnd"`
	SeasonIndex    *uint32 `json:"seasonIndex"`
	Overview       *string `json:"overview"`
	RuntimeMinutes *uint32 `json:"runtimeMinutes"`
	PremiereDate   *string `json:"premiereDate"`
	// Thumb: 16:9 still, when the server has one.
	Thumb  *ImageRef    `json:"thumb"`
	Played bool         `json:"played"`
	Resume *ResumePoint `json:"resume"`
}

type EpisodePage struct {
	Items      List[Episode] `json:"items"`
	StartIndex uint32        `json:"startIndex"`
	NextIndex  uint32        `json:"nextIndex"`
	Total      uint32        `json:"total"`
}

// PlayMethod is how the server delivers the media.
type PlayMethod string

const (
	MethodDirectPlay   PlayMethod = "directPlay"
	MethodDirectStream PlayMethod = "directStream"
	MethodTranscode    PlayMethod = "transcode"
)

// AsEmby is the value of `PlayMethod` in Emby's playback reports.
func (m PlayMethod) AsEmby() string {
	switch m {
	case MethodDirectStream:
		return "DirectStream"
	case MethodTranscode:
		return "Transcode"
	default:
		return "DirectPlay"
	}
}

// PlaybackStart is what the UI learns about a started playback: no URL, no token.
type PlaybackStart struct {
	ItemID string `json:"itemId"`
	// MediaSourceID is the version played: a fallback or a resume plays the same one.
	MediaSourceID string `json:"mediaSourceId"`
	// Resume is the start asked for: a fallback keeps "Reprendre" or "Lire depuis le début".
	Resume    bool       `json:"resume"`
	Method    PlayMethod `json:"method"`
	Container *string    `json:"container"`
	// TranscodeReasons: why the server transcodes, in French.
	TranscodeReasons *string `json:"transcodeReasons"`
	// Title is what the player shows at the top; nil when the item could not be read.
	Title *PlaybackTitle `json:"title"`
	// BitrateCap sent to the server; nil for "Maximale".
	BitrateCap *uint64 `json:"bitrateCap"`
	// LocalAddress: whether the session's address counts as local, so which cap applied.
	LocalAddress bool `json:"localAddress"`
	// Origin is where the stream comes from: OriginEmby, or OriginStremio for an
	// addon's stream, which Emby never hears about.
	Origin PlaybackOrigin `json:"origin"`
}

// PlaybackOrigin is the `origin` of a PlaybackStart.
type PlaybackOrigin string

const (
	OriginEmby    PlaybackOrigin = "emby"
	OriginStremio PlaybackOrigin = "stremio"
)

// PlaybackTitle is the name of what plays and, for an episode, where it sits in its
// series.
type PlaybackTitle struct {
	Name       string  `json:"name"`
	SeriesName *string `json:"seriesName"`
	// SeasonIndex: `0` is the specials season.
	SeasonIndex *uint32 `json:"seasonIndex"`
	Index       *uint32 `json:"index"`
	IndexEnd    *uint32 `json:"indexEnd"`
}

// PlaybackStopped is the server state of an item after a playback stopped, sent to
// the UI so the detail page shows the new resume point or watched status without
// reloading.
type PlaybackStopped struct {
	ItemID string       `json:"itemId"`
	Played bool         `json:"played"`
	Resume *ResumePoint `json:"resume"`
}

// Playlist is a playlist of the user, offered by the card menu.
type Playlist struct {
	ID   string `json:"id"`
	Name string `json:"name"`
}

// PlaylistCard is a video playlist of the home row « Playlists ».
type PlaylistCard struct {
	ID     string    `json:"id"`
	Name   string    `json:"name"`
	Poster *ImageRef `json:"poster"`
	// AspectRatio is width / height of the poster, when the server knows it.
	AspectRatio *float64 `json:"aspectRatio"`
	// Count is the entries the server counts, of any kind; nil when it does not say.
	Count *uint32 `json:"count"`
}

// PlaylistCreated is a playlist just created, and how many items the server says it holds.
type PlaylistCreated struct {
	ID    string `json:"id"`
	Name  string `json:"name"`
	Added uint32 `json:"added"`
}
