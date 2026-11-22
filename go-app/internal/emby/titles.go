package emby

// Lists of titles opened from a detail page, port of
// src-tauri/src/emby/titles.rs: a person's films and series, the titles of a genre
// or a studio. Same queries and parameters as the Android TV,
// the verified reference.

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"sort"
	"strconv"
	"strings"
	"unicode"
	"unicode/utf8"
)

const (
	// TitlesLimit: titles read for one list, one request, enough for a home library.
	TitlesLimit = 100
	// SimilarLimit: similar titles shown under a film or series, as on the TV.
	SimilarLimit = 12
	// MaxCollections: collections of a title shown on its page, as on the TV.
	MaxCollections = 3
	// NamedTitlesLimit: titles read for a genre or a studio, sorted by name: a genre may hold many.
	NamedTitlesLimit = 200
)

// TitlesKind is the `kind` tag of TitlesSource.
type TitlesKind string

const (
	// TitlesPerson: films and series of a person, newest first.
	TitlesPerson TitlesKind = "person"
	// TitlesGenre: films or series of one genre, by name.
	TitlesGenre TitlesKind = "genre"
	// TitlesStudio: films or series of one studio or network, by name.
	TitlesStudio TitlesKind = "studio"
	// TitlesCollection: titles of a collection (BoxSet), by premiere date.
	TitlesCollection TitlesKind = "collection"
	// TitlesSimilar: films and series like a title (`/Items/{Id}/Similar`), the title left out.
	TitlesSimilar TitlesKind = "similar"
)

// TitlesSource is what a list of titles shows, as the UI asks for it. ID is set for
// person, collection and similar; Name and ItemType for genre and studio.
type TitlesSource struct {
	Kind     TitlesKind
	ID       string
	Name     string
	ItemType string
}

// UnmarshalJSON decodes serde's internally tagged enum: exact key names, an unknown
// kind or a missing, null or non-string field is refused, unknown keys are ignored.
func (s *TitlesSource) UnmarshalJSON(b []byte) error {
	var raw map[string]json.RawMessage
	if bytes.Equal(bytes.TrimSpace(b), []byte("null")) {
		return errors.New("TitlesSource : null refusé")
	}
	if err := json.Unmarshal(b, &raw); err != nil {
		return fmt.Errorf("TitlesSource : %w", err)
	}
	field := func(name string) (string, error) {
		v, ok := raw[name]
		if !ok {
			return "", fmt.Errorf("TitlesSource : champ %q manquant", name)
		}
		var out string
		if bytes.Equal(bytes.TrimSpace(v), []byte("null")) {
			return "", fmt.Errorf("TitlesSource : champ %q null", name)
		}
		if err := json.Unmarshal(v, &out); err != nil {
			return "", fmt.Errorf("TitlesSource.%s : %w", name, err)
		}
		return out, nil
	}
	kind, err := field("kind")
	if err != nil {
		return err
	}
	out := TitlesSource{Kind: TitlesKind(kind)}
	switch out.Kind {
	case TitlesPerson, TitlesCollection, TitlesSimilar:
		if out.ID, err = field("id"); err != nil {
			return err
		}
	case TitlesGenre, TitlesStudio:
		if out.Name, err = field("name"); err != nil {
			return err
		}
		if out.ItemType, err = field("itemType"); err != nil {
			return err
		}
	default:
		return fmt.Errorf("TitlesSource : variante inconnue %q", kind)
	}
	*s = out
	return nil
}

// CollectionRef is a collection a title belongs to.
type CollectionRef struct {
	ID   string `json:"id"`
	Name string `json:"name"`
}

// CollectionRefs reads the item's `Collections` (`[{Name, Id}]`, the id a number or a
// string): named ones with a usable id, once each, at most MaxCollections. Never nil.
func CollectionRefs(raw json.RawMessage) List[CollectionRef] {
	out := List[CollectionRef]{}
	var list []json.RawMessage
	if len(raw) == 0 || json.Unmarshal(raw, &list) != nil {
		return out // absent, null, or not an array
	}
	for _, entry := range list {
		var obj map[string]json.RawMessage
		if json.Unmarshal(entry, &obj) != nil {
			continue // not an object: no `Id`
		}
		id, ok := stringOrNumber(obj["Id"])
		if !ok {
			continue
		}
		var name string
		if v, has := obj["Name"]; has && json.Unmarshal(v, &name) != nil {
			name = ""
		}
		name = strings.TrimSpace(name)
		if !IsItemID(id) || name == "" || containsCollection(out, id) {
			continue
		}
		out = append(out, CollectionRef{ID: id, Name: name})
		if len(out) == MaxCollections {
			break
		}
	}
	return out
}

func containsCollection(list []CollectionRef, id string) bool {
	for _, c := range list {
		if c.ID == id {
			return true
		}
	}
	return false
}

// stringOrNumber reads a JSON string (trimmed) or number (as serde_json prints it).
func stringOrNumber(v json.RawMessage) (string, bool) {
	v = bytes.TrimSpace(v)
	if len(v) == 0 {
		return "", false
	}
	switch {
	case v[0] == '"':
		var s string
		if json.Unmarshal(v, &s) != nil {
			return "", false
		}
		return strings.TrimSpace(s), true
	case v[0] == '-' || (v[0] >= '0' && v[0] <= '9'):
		var n json.Number
		if json.Unmarshal(v, &n) != nil {
			return "", false
		}
		text := serdeNumberText(n)
		return text, text != ""
	default:
		return "", false
	}
}

// serdeNumberText is how serde_json prints a parsed number: an integer that fits
// u64 or i64 as is, anything else as an f64 in ryu's format (`1000.0`, `1e20`,
// `2.5e-7`, `1e+20`). Empty when the number is out of the f64 range (serde refuses it).
func serdeNumberText(n json.Number) string {
	s := string(n)
	if s == "-0" {
		return "-0.0" // serde_json reads -0 as a float
	}
	if !strings.ContainsAny(s, ".eE") {
		if strings.HasPrefix(s, "-") {
			if i, err := strconv.ParseInt(s, 10, 64); err == nil {
				return strconv.FormatInt(i, 10)
			}
		} else if u, err := strconv.ParseUint(s, 10, 64); err == nil {
			return strconv.FormatUint(u, 10)
		}
	}
	f, err := strconv.ParseFloat(s, 64)
	if err != nil || math.IsInf(f, 0) {
		return ""
	}
	return ryuFormat(f)
}

// ryuFormat lays out the shortest round-trip digits as serde_json 1.0.154 prints an
// f64 (checked against the crate): `12340000000.0`, `12.34`, `0.001234`, `1e+30`,
// `1.234e+33`, `2.5e-7`.
func ryuFormat(f float64) string {
	sign := ""
	if math.Signbit(f) {
		sign = "-"
		f = -f
	}
	if f == 0 {
		return sign + "0.0"
	}
	// Shortest digits and exponent: d.ddde±x.
	e := strconv.FormatFloat(f, 'e', -1, 64)
	mant, expText, _ := strings.Cut(e, "e")
	exp, _ := strconv.Atoi(expText)
	digits := strings.Replace(mant, ".", "", 1)
	length := len(digits)
	k := exp - (length - 1) // value = digits × 10^k
	kk := length + k        // 10^(kk-1) <= value < 10^kk
	var out string
	switch {
	case k >= 0 && kk <= 16:
		out = digits + strings.Repeat("0", k) + ".0"
	case kk > 0 && kk <= 16:
		out = digits[:kk] + "." + digits[kk:]
	case kk > -5 && kk <= 0:
		out = "0." + strings.Repeat("0", -kk) + digits
	case length == 1:
		out = digits + "e" + exponentText(kk-1)
	default:
		out = digits[:1] + "." + digits[1:] + "e" + exponentText(kk-1)
	}
	return sign + out
}

func exponentText(e int) string {
	if e >= 0 {
		return "+" + strconv.Itoa(e)
	}
	return strconv.Itoa(e)
}

// Param is one query parameter, in the order sent.
type Param struct {
	Key   string
	Value string
}

var commonTitlesParams = []Param{
	{"Fields", "PrimaryImageAspectRatio,ProductionYear"},
	{"EnableUserData", "true"},
	{"ImageTypeLimit", "1"},
	{"EnableImageTypes", "Primary"},
}

// SimilarParams is the query of `GET /Items/{Id}/Similar` (TV `EmbyClient.similar`);
// false for a bad id.
func SimilarParams(id, userID string) ([]Param, bool) {
	if !IsItemID(id) {
		return nil, false
	}
	return []Param{
		{"UserId", userID},
		{"Limit", strconv.Itoa(SimilarLimit)},
		{"IncludeItemTypes", "Movie,Series"},
		{"Fields", "PrimaryImageAspectRatio,ProductionYear"},
		{"EnableUserData", "true"},
		{"ImageTypeLimit", "1"},
		{"EnableImageTypes", "Primary"},
	}, true
}

// IsItemID is the rule of the path segments: ASCII letters, digits and `-` only.
func IsItemID(id string) bool {
	return id != "" && len(id) <= 128 && !strings.ContainsFunc(id, func(r rune) bool { return !isASCIIAlnum(r) && r != '-' })
}

// TitlesParams is the query of `GET /Users/{UserId}/Items` for source; a bad id or
// name is refused with an InvalidResponse EmbyError.
func TitlesParams(source TitlesSource) ([]Param, error) {
	invalid := NewInvalidResponse("identifiant invalide")
	var params []Param
	switch source.Kind {
	case TitlesPerson:
		if !IsItemID(source.ID) {
			return nil, invalid
		}
		params = []Param{
			{"PersonIds", source.ID},
			{"Recursive", "true"},
			{"IncludeItemTypes", "Movie,Series"},
			{"SortBy", "ProductionYear,SortName"},
			{"SortOrder", "Descending"},
			{"Limit", strconv.Itoa(TitlesLimit)},
		}
	case TitlesGenre, TitlesStudio:
		filter := "Studios"
		if source.Kind == TitlesGenre {
			filter = "Genres"
		}
		// `|` separates names in these filters and has no escape.
		if !IsUsableGenre(source.Name) || (source.ItemType != "Movie" && source.ItemType != "Series") {
			return nil, NewInvalidResponse("filtre invalide")
		}
		params = []Param{
			{filter, strings.TrimSpace(source.Name)},
			{"Recursive", "true"},
			{"IncludeItemTypes", source.ItemType},
			{"SortBy", "SortName"},
			{"SortOrder", "Ascending"},
			{"Limit", strconv.Itoa(NamedTitlesLimit)},
		}
	case TitlesCollection:
		if !IsItemID(source.ID) {
			return nil, invalid
		}
		params = []Param{
			{"ParentId", source.ID},
			{"SortBy", "PremiereDate,ProductionYear,SortName"},
			{"SortOrder", "Ascending"},
			{"Limit", strconv.Itoa(TitlesLimit)},
		}
	default:
		// Similar is read from `/Items/{Id}/Similar`, with SimilarParams.
		return nil, invalid
	}
	return append(params, commonTitlesParams...), nil
}

// OrderTitles keeps films and series only, once each, in the order of source: a
// person's newest year first, titles without a year last, A → Z within a year (one
// `SortOrder` applies to every `SortBy` key, so the server puts names Z → A).
func OrderTitles(source TitlesSource, items []BaseItemDto) []BaseItemDto {
	seen := map[string]bool{}
	kept := make([]BaseItemDto, 0, len(items))
	for _, i := range items {
		if i.ItemType != "Movie" && i.ItemType != "Series" {
			continue
		}
		if seen[i.ID] {
			continue
		}
		seen[i.ID] = true
		kept = append(kept, i)
	}
	switch source.Kind {
	case TitlesPerson:
		sort.SliceStable(kept, func(x, y int) bool {
			a, b := kept[x], kept[y]
			if c := cmpBool(a.ProductionYear == nil, b.ProductionYear == nil); c != 0 {
				return c < 0
			}
			if c := cmpOptInt32(b.ProductionYear, a.ProductionYear); c != 0 {
				return c < 0
			}
			return rustLowercase(a.Name) < rustLowercase(b.Name)
		})
	case TitlesCollection:
		// Release order: dated titles first, by day, else by year; then A → Z.
		sort.SliceStable(kept, func(x, y int) bool {
			a, b := kept[x], kept[y]
			ka, kb := releaseKey(a), releaseKey(b)
			if c := cmpBool(ka == nil, kb == nil); c != 0 {
				return c < 0
			}
			if ka != nil && kb != nil && *ka != *kb {
				return *ka < *kb
			}
			return rustLowercase(a.Name) < rustLowercase(b.Name)
		})
	case TitlesSimilar:
		// The server's order of likeness, the title itself left out.
		out := kept[:0]
		for _, i := range kept {
			if i.ID != source.ID {
				out = append(out, i)
			}
		}
		kept = out
		if len(kept) > SimilarLimit {
			kept = kept[:SimilarLimit]
		}
	}
	// Genre and studio: sorted by the server on the sort name, which the client does not read.
	return kept
}

// releaseKey is the first ten bytes of PremiereDate (the day), else the year on four
// digits. A date shorter than ten bytes, or cut inside a character, falls back to the year.
func releaseKey(i BaseItemDto) *string {
	if d := i.PremiereDate; d != nil && len(*d) >= 10 && (len(*d) == 10 || utf8.RuneStart((*d)[10])) {
		k := (*d)[:10]
		return &k
	}
	if i.ProductionYear != nil {
		k := fmt.Sprintf("%04d", *i.ProductionYear)
		return &k
	}
	return nil
}

// cmpBool orders false before true, as Rust's bool Ord.
func cmpBool(a, b bool) int {
	switch {
	case a == b:
		return 0
	case !a:
		return -1
	default:
		return 1
	}
}

// cmpOptInt32 is Rust's Option<i32> Ord: None first, then by value.
func cmpOptInt32(a, b *int32) int {
	switch {
	case a == nil && b == nil:
		return 0
	case a == nil:
		return -1
	case b == nil:
		return 1
	case *a < *b:
		return -1
	case *a > *b:
		return 1
	default:
		return 0
	}
}

// rustLowercase is Rust's `str::to_lowercase` for the sort: Unicode lowercase,
// with İ (U+0130) mapped to "i̇" as Rust does. Rust's final-sigma rule (Σ → ς at
// the end of a word) is not reproduced: σ is used everywhere (MAP.md).
func rustLowercase(s string) string {
	var b strings.Builder
	b.Grow(len(s))
	for _, r := range s {
		if r == 'İ' {
			b.WriteString("i̇")
			continue
		}
		b.WriteRune(unicode.ToLower(r))
	}
	return b.String()
}

// PersonID is the person item's id, a string or a number, when it can go into a path.
func PersonID(p PersonDto) *string {
	if len(p.ID) == 0 {
		return nil
	}
	id, ok := stringOrNumber(p.ID)
	if !ok || !IsItemID(id) {
		return nil
	}
	return &id
}
