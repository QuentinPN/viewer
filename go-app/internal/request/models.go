package request

// Request API models, port of src-tauri/src/request/models.rs, field for
// field from contracts/request/openapi.json (ADR 0125).
//
// serde mapping (decode.go for the reading side):
//   - a non-`Option` field is a non-pointer, required when read;
//   - an `Option` is a pointer without omitempty: written `null`, read as nil when
//     null or missing; `Option<Vec>` is a pointer to a slice, so null and [] differ;
//   - `skip_deserializing` fields carry the tag `ur:"skip"`: built here, never read
//     from the server;
//   - the one `skip_serializing_if = "Option::is_none"` (NewRequest.seasons) is
//     `omitempty` on a pointer.
// Key names are serde's camelCase: `can_request4k` is `canRequest4k`, `four_k` is
// `fourK`, `is4k` stays `is4k`.

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"

	"viewer/internal/emby"
	"viewer/internal/strictjson"
)

// ------------------------------------------------------------------ enums

// MediaType is `MediaType` (lowercase).
type MediaType string

const (
	MediaMovie MediaType = "movie"
	MediaTv    MediaType = "tv"
)

func (m *MediaType) UnmarshalJSON(b []byte) error {
	return decodeEnum(b, (*string)(m), "MediaType", "movie", "tv")
}

// MediaStatus is the availability of a title or a season (lowercase).
type MediaStatus string

func (m *MediaStatus) UnmarshalJSON(b []byte) error {
	return decodeEnum(b, (*string)(m), "MediaStatus",
		"available", "partial", "downloading", "requested", "pending", "declined")
}

// RequestStatus is `RequestStatus` (lowercase).
type RequestStatus string

func (s *RequestStatus) UnmarshalJSON(b []byte) error {
	return decodeEnum(b, (*string)(s), "RequestStatus",
		"pending", "approved", "declined", "cancelled", "sent", "failed", "available")
}

// Role is `Role` (snake_case).
type Role string

const RoleMember Role = "member"

func (r *Role) UnmarshalJSON(b []byte) error {
	return decodeEnum(b, (*string)(r), "Role", "member", "auto_approve", "manager", "owner")
}

// SeriesType is `SeriesType` (lowercase).
type SeriesType string

func (s *SeriesType) UnmarshalJSON(b []byte) error {
	return decodeEnum(b, (*string)(s), "SeriesType", "standard", "anime", "daily")
}

// EventType is `EventType` (lowercase).
type EventType string

func (e *EventType) UnmarshalJSON(b []byte) error {
	return decodeEnum(b, (*string)(e), "EventType",
		"requested", "approved", "declined", "cancelled", "sent", "failed", "retried", "available")
}

// CrewJob is `CrewJob` (lowercase).
type CrewJob string

func (c *CrewJob) UnmarshalJSON(b []byte) error {
	return decodeEnum(b, (*string)(c), "CrewJob", "director", "writer", "creator")
}

// SortOrder is `SortOrder` (lowercase).
type SortOrder string

func (s *SortOrder) UnmarshalJSON(b []byte) error {
	return decodeEnum(b, (*string)(s), "SortOrder", "popularity", "rating", "release", "title")
}

// ------------------------------------------------------------------ session

type ApiVersion struct {
	APIVersion string   `json:"apiVersion"`
	Features   []string `json:"features"`
}

type Capabilities struct {
	CanRequestMovies        bool `json:"canRequestMovies"`
	CanRequestTv            bool `json:"canRequestTv"`
	CanRequest4k            bool `json:"canRequest4k"`
	AutoApproveMovies       bool `json:"autoApproveMovies"`
	AutoApproveTv           bool `json:"autoApproveTv"`
	AutoApprove4k           bool `json:"autoApprove4k"`
	CanRequestOnBehalf      bool `json:"canRequestOnBehalf"`
	CanRequestQuotaExempt   bool `json:"canRequestQuotaExempt"`
	CanAdvancedRequest      bool `json:"canAdvancedRequest"`
	CanAutoRequestWatchlist bool `json:"canAutoRequestWatchlist"`
	CanManageRequests       bool `json:"canManageRequests"`
	CanManageIssues         bool `json:"canManageIssues"`
	CanManageBlocklist      bool `json:"canManageBlocklist"`
	CanViewStats            bool `json:"canViewStats"`
	CanManageUsers          bool `json:"canManageUsers"`
	CanRequest              bool `json:"canRequest"`
	IsOwner                 bool `json:"isOwner"`
	QuotaExempt             bool `json:"quotaExempt"`
}

type Viewer struct {
	ID           string       `json:"id"`
	Name         string       `json:"name"`
	Role         Role         `json:"role"`
	Capabilities Capabilities `json:"capabilities"`
	AvatarURL    *string      `json:"avatarUrl"`
}

type ViewerResponse struct {
	Viewer Viewer `json:"viewer"`
}

// SessionToken is the Request session token. Printing, logging or marshaling it
// never shows the value: only Expose does.
type SessionToken struct {
	value string
}

func NewSessionToken(value string) SessionToken { return SessionToken{value: value} }

func (t SessionToken) Expose() string { return t.value }

func (t SessionToken) String() string { return redactedText("SessionToken") }

func (t SessionToken) GoString() string { return t.String() }

func (t SessionToken) Format(f fmt.State, _ rune) { writeRedacted(f, "SessionToken") }

func (t SessionToken) LogValue() slog.Value { return slog.StringValue(t.String()) }

// MarshalJSON keeps the token out of any JSON (the Rust type has no Serialize).
func (t SessionToken) MarshalJSON() ([]byte, error) {
	return refuseMarshal("SessionToken")
}

// UnmarshalJSON is `#[serde(transparent)]` over a String.
func (t *SessionToken) UnmarshalJSON(b []byte) error {
	if strictjson.IsNull(bytes.TrimSpace(b)) {
		return errors.New("invalid type: null, expected a string")
	}
	var s string
	if err := json.Unmarshal(b, &s); err != nil {
		return err
	}
	t.value = s
	return nil
}

// EmbyTokenSession is the answer of `POST /api/auth/emby-token`. Never sent to the
// UI: it holds the token.
type EmbyTokenSession struct {
	Token     SessionToken `json:"token"`
	ExpiresAt string       `json:"expiresAt"`
	Viewer    Viewer       `json:"viewer"`
}

type AuthSession struct {
	ID         string  `json:"id"`
	DeviceName *string `json:"deviceName"`
	UserAgent  *string `json:"userAgent"`
	CreatedAt  string  `json:"createdAt"`
	LastSeenAt string  `json:"lastSeenAt"`
	ExpiresAt  string  `json:"expiresAt"`
	Current    bool    `json:"current"`
}

type SessionList struct {
	Sessions []AuthSession `json:"sessions"`
}

// ------------------------------------------------------------------ media

type Genre struct {
	ID   int64  `json:"id"`
	Name string `json:"name"`
}

type GenreList struct {
	Genres []Genre `json:"genres"`
}

// Blocked is `#[serde(tag = "by", rename_all = "lowercase")] enum Blocked { Title,
// Keyword { keyword } }`. Keyword is set for the keyword variant only.
type Blocked struct {
	By      string
	Keyword string
}

func (b Blocked) MarshalJSON() ([]byte, error) {
	if b.By == "keyword" {
		return json.Marshal(struct {
			By      string `json:"by"`
			Keyword string `json:"keyword"`
		}{b.By, b.Keyword})
	}
	return json.Marshal(struct {
		By string `json:"by"`
	}{b.By})
}

func (b *Blocked) UnmarshalJSON(data []byte) error {
	var tag struct {
		By *string `json:"by"`
	}
	if err := decodeStrict(data, &tag); err != nil {
		return err
	}
	if tag.By == nil {
		return errors.New("missing field `by`")
	}
	switch *tag.By {
	case "title":
		*b = Blocked{By: "title"}
		return nil
	case "keyword":
		var kw struct {
			Keyword string `json:"keyword"`
		}
		if err := decodeStrict(data, &kw); err != nil {
			return err
		}
		*b = Blocked{By: "keyword", Keyword: kw.Keyword}
		return nil
	}
	return fmt.Errorf("unknown variant `%s`, expected `title` or `keyword`", *tag.By)
}

type MediaSummary struct {
	TmdbID       int64        `json:"tmdbId"`
	MediaType    MediaType    `json:"type"`
	Title        string       `json:"title"`
	Year         *int32       `json:"year"`
	PosterPath   *string      `json:"posterPath"`
	BackdropPath *string      `json:"backdropPath"`
	Overview     string       `json:"overview"`
	Blocked      *Blocked     `json:"blocked"`
	Status       *MediaStatus `json:"status"`
	Progress     *float64     `json:"progress"`
	EmbyItemID   *string      `json:"embyItemId"`
	// Poster and Backdrop are built here on the `requestimg` protocol; never read from
	// the server.
	Poster   *emby.ImageRef `json:"poster" ur:"skip"`
	Backdrop *emby.ImageRef `json:"backdrop" ur:"skip"`
}

type PagedMedia struct {
	Page       uint32         `json:"page"`
	TotalPages uint32         `json:"totalPages"`
	Results    []MediaSummary `json:"results"`
}

type SeeAllFilters struct {
	Genres     *[]int64   `json:"genres"`
	YearFrom   *int32     `json:"yearFrom"`
	YearTo     *int32     `json:"yearTo"`
	Rating     *int32     `json:"rating"`
	RuntimeMin *int32     `json:"runtimeMin"`
	RuntimeMax *int32     `json:"runtimeMax"`
	Language   *string    `json:"language"`
	Provider   *int64     `json:"provider"`
	Sort       *SortOrder `json:"sort"`
	Company    *int64     `json:"company"`
	Network    *int64     `json:"network"`
}

type SeeAll struct {
	MediaType MediaType     `json:"type"`
	Filters   SeeAllFilters `json:"filters"`
}

type DiscoverRow struct {
	ID     string         `json:"id"`
	Title  string         `json:"title"`
	Items  []MediaSummary `json:"items"`
	SeeAll *SeeAll        `json:"seeAll"`
}

type DiscoverResponse struct {
	Rows   []DiscoverRow `json:"rows"`
	Layout []string      `json:"layout"`
}

type SearchPerson struct {
	ID          int64   `json:"id"`
	Name        string  `json:"name"`
	ProfilePath *string `json:"profilePath"`
	// Profile is built here on the `requestimg` protocol; never read from the server.
	Profile  *emby.ImageRef `json:"profile" ur:"skip"`
	KnownFor []string       `json:"knownFor"`
}

type MediaSearchResults struct {
	Page       uint32         `json:"page"`
	TotalPages uint32         `json:"totalPages"`
	Results    []MediaSummary `json:"results"`
	People     []SearchPerson `json:"people"`
}

type EpisodeCount struct {
	Present  uint32 `json:"present"`
	Expected uint32 `json:"expected"`
}

type FourK struct {
	Status   *MediaStatus  `json:"status"`
	Progress *float64      `json:"progress"`
	Episodes *EpisodeCount `json:"episodes"`
}

type Season struct {
	SeasonNumber uint32        `json:"seasonNumber"`
	EpisodeCount uint32        `json:"episodeCount"`
	AirDate      *string       `json:"airDate"`
	Episodes     *EpisodeCount `json:"episodes"`
	FourK        *FourK        `json:"fourK"`
	Status       *MediaStatus  `json:"status"`
	Progress     *float64      `json:"progress"`
}

type CastMember struct {
	ID          int64   `json:"id"`
	Name        string  `json:"name"`
	Character   string  `json:"character"`
	ProfilePath *string `json:"profilePath"`
	// Profile is built here on the `requestimg` protocol; never read from the server.
	Profile *emby.ImageRef `json:"profile" ur:"skip"`
}

type CrewMember struct {
	ID          int64     `json:"id"`
	Name        string    `json:"name"`
	Jobs        []CrewJob `json:"jobs"`
	ProfilePath *string   `json:"profilePath"`
	// Profile is built here on the `requestimg` protocol; never read from the server.
	Profile *emby.ImageRef `json:"profile" ur:"skip"`
}

type Collection struct {
	ID           int64   `json:"id"`
	Name         string  `json:"name"`
	PosterPath   *string `json:"posterPath"`
	BackdropPath *string `json:"backdropPath"`
	// Poster and Backdrop are built here on the `requestimg` protocol; never read from
	// the server.
	Poster   *emby.ImageRef `json:"poster" ur:"skip"`
	Backdrop *emby.ImageRef `json:"backdrop" ur:"skip"`
}

type WatchProvider struct {
	ID       int64   `json:"id"`
	Name     string  `json:"name"`
	LogoPath *string `json:"logoPath"`
}

type WatchProviders struct {
	Flatrate []WatchProvider `json:"flatrate"`
	Rent     []WatchProvider `json:"rent"`
	Buy      []WatchProvider `json:"buy"`
}

type ExternalIDs struct {
	Imdb *string `json:"imdb"`
	Tvdb *int64  `json:"tvdb"`
}

type LastAired struct {
	SeasonNumber  uint32 `json:"seasonNumber"`
	EpisodeNumber uint32 `json:"episodeNumber"`
}

type MediaDetails struct {
	TmdbID          int64          `json:"tmdbId"`
	MediaType       MediaType      `json:"type"`
	Title           string         `json:"title"`
	Year            *int32         `json:"year"`
	PosterPath      *string        `json:"posterPath"`
	BackdropPath    *string        `json:"backdropPath"`
	Overview        string         `json:"overview"`
	Blocked         *Blocked       `json:"blocked"`
	Status          *MediaStatus   `json:"status"`
	Progress        *float64       `json:"progress"`
	EmbyItemID      *string        `json:"embyItemId"`
	Genres          []Genre        `json:"genres"`
	Runtime         *uint32        `json:"runtime"`
	Seasons         []Season       `json:"seasons"`
	Cast            []CastMember   `json:"cast"`
	Crew            *[]CrewMember  `json:"crew"`
	Collection      *Collection    `json:"collection"`
	TrailerKey      *string        `json:"trailerKey"`
	WatchProviders  WatchProviders `json:"watchProviders"`
	Recommendations []MediaSummary `json:"recommendations"`
	Similar         []MediaSummary `json:"similar"`
	ExternalIDs     ExternalIDs    `json:"externalIds"`
	FourK           *FourK         `json:"fourK"`
	Keywords        *[]Genre       `json:"keywords"`
	OriginalLang    *string        `json:"originalLanguage"`
	LastAired       *LastAired     `json:"lastAired"`
	// Poster and Backdrop are built here on the `requestimg` protocol; never read from
	// the server.
	Poster   *emby.ImageRef `json:"poster" ur:"skip"`
	Backdrop *emby.ImageRef `json:"backdrop" ur:"skip"`
}

// ------------------------------------------------------------------ requests

type QuotaUsage struct {
	Limit     int64 `json:"limit"`
	Days      int64 `json:"days"`
	Used      int64 `json:"used"`
	Remaining int64 `json:"remaining"`
}

type RequestQuota struct {
	Movies  QuotaUsage `json:"movies"`
	Seasons QuotaUsage `json:"seasons"`
	Exempt  bool       `json:"exempt"`
}

type RequestPerson struct {
	ID        string  `json:"id"`
	Name      string  `json:"name"`
	AvatarURL *string `json:"avatarUrl"`
}

type RequestServer struct {
	ID   int64  `json:"id"`
	Name string `json:"name"`
}

type RequestView struct {
	ID               int64          `json:"id"`
	MediaType        MediaType      `json:"type"`
	TmdbID           int64          `json:"tmdbId"`
	Status           RequestStatus  `json:"status"`
	Seasons          []uint32       `json:"seasons"`
	Requester        RequestPerson  `json:"requester"`
	CreatedBy        *RequestPerson `json:"createdBy"`
	QuotaExempt      bool           `json:"quotaExempt"`
	DecidedBy        *RequestPerson `json:"decidedBy"`
	DeclineReason    *string        `json:"declineReason"`
	QualityProfileID *int64         `json:"qualityProfileId"`
	RootFolder       *string        `json:"rootFolder"`
	SeriesType       *SeriesType    `json:"seriesType"`
	Is4k             bool           `json:"is4k"`
	Server           *RequestServer `json:"server"`
	CreatedAt        string         `json:"createdAt"`
	UpdatedAt        string         `json:"updatedAt"`
	Media            *MediaSummary  `json:"media"`
	FailureReason    *string        `json:"failureReason"`
}

type RequestEvent struct {
	ID        int64          `json:"id"`
	EventType EventType      `json:"type"`
	Actor     *RequestPerson `json:"actor"`
	Detail    *string        `json:"detail"`
	CreatedAt string         `json:"createdAt"`
}

// RequestDetail is every RequestView field (`#[serde(flatten)]`, the embedded
// struct) plus `events`.
type RequestDetail struct {
	RequestView
	Events []RequestEvent `json:"events"`
}

type RequestTotals struct {
	Pending   uint32 `json:"pending"`
	Approved  uint32 `json:"approved"`
	Declined  uint32 `json:"declined"`
	Cancelled uint32 `json:"cancelled"`
	Sent      uint32 `json:"sent"`
	Failed    uint32 `json:"failed"`
	Available uint32 `json:"available"`
}

type RequestPage struct {
	Page       uint32        `json:"page"`
	TotalPages uint32        `json:"totalPages"`
	Results    []RequestView `json:"results"`
	Totals     RequestTotals `json:"totals"`
}

// SeasonsChoice is the `seasons` of a series request: `"all"` (All) or a list of
// season numbers.
type SeasonsChoice struct {
	All  bool
	List []uint32
}

// SeasonsAll is `SeasonsChoice::All`.
var SeasonsAll = SeasonsChoice{All: true}

// SeasonsList is `SeasonsChoice::List`.
func SeasonsList(list ...uint32) SeasonsChoice { return SeasonsChoice{List: list} }

func (s SeasonsChoice) MarshalJSON() ([]byte, error) {
	if s.All {
		return []byte(`"all"`), nil
	}
	list := s.List
	if list == nil {
		list = []uint32{}
	}
	return json.Marshal(list)
}

// UnmarshalJSON is the untagged `Word(String) | List(Vec<u32>)` of models.rs: only
// `"all"` or a non-empty list.
func (s *SeasonsChoice) UnmarshalJSON(b []byte) error {
	var word string
	if err := json.Unmarshal(b, &word); err == nil && !strictjson.IsNull(bytes.TrimSpace(b)) {
		if word == "all" {
			*s = SeasonsAll
			return nil
		}
		return fmt.Errorf("saisons invalides : %s", word)
	}
	var list []uint32
	if err := decodeStrict(b, &list); err != nil {
		return errors.New("data did not match any variant of untagged enum Raw")
	}
	if len(list) == 0 {
		return errors.New("aucune saison choisie")
	}
	*s = SeasonsChoice{List: list}
	return nil
}

// NewRequest is the body of `POST /api/requests` (member fields only: no 4K, no
// advanced options).
type NewRequest struct {
	MediaType MediaType `json:"type"`
	TmdbID    int64     `json:"tmdbId"`
	// Seasons: series only; the server's body schema forbids unknown or null fields.
	Seasons *SeasonsChoice `json:"seasons,omitempty"`
}
