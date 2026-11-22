package emby

// Conversions of Emby DTOs into what the UI shows, the free functions of session.rs.

import (
	"math"
	"strconv"
	"strings"
	"unicode"
	"unicode/utf8"
)

const (
	sessionTicksPerSecond = 10_000_000
	// maxCast: enough names for one line of credits; the full cast is not shown.
	maxCast = 12
)

func userDataOf(u *UserItemData) UserItemData {
	if u == nil {
		return UserItemData{}
	}
	return *u
}

func runeCount(s string) int { return utf8.RuneCountInString(s) }

// videoPlaylists: video playlists, once each, in the server's order; one without
// `MediaType` is kept, audio ones are dropped (ADR 0122).
func videoPlaylists(items []BaseItemDto, server string) List[PlaylistCard] {
	cards := List[PlaylistCard]{}
	for _, dto := range items {
		video := dto.MediaType == nil || *dto.MediaType == "Video"
		if dto.ItemType != "Playlist" || !video || !isID(dto.ID) {
			continue
		}
		dup := false
		for _, c := range cards {
			dup = dup || c.ID == dto.ID
		}
		if dup {
			continue
		}
		cards = append(cards, PlaylistCard{
			ID:          dto.ID,
			Name:        textOr(&dto.Name, "Sans titre"),
			Poster:      ImageRefPtr(server, dto.ID, ImagePrimary, imageTag(dto.ImageTags, "Primary"), PosterWidths),
			AspectRatio: positiveRatio(dto.PrimaryImageAspectRatio),
			Count:       dto.ChildCount,
		})
	}
	return cards
}

// playlistEntries: films and episodes of a playlist in its order; anything else has
// no page to open. An item listed twice shows once, where it comes first (ADR 0122).
func playlistEntries(items []BaseItemDto, server string) List[WatchItem] {
	entries := List[WatchItem]{}
	for _, d := range items {
		item, ok := watchItem(d, server)
		if !ok {
			continue
		}
		dup := false
		for _, e := range entries {
			dup = dup || e.ID == item.ID
		}
		if !dup {
			entries = append(entries, item)
		}
	}
	return entries
}

func positiveRatio(r *float64) *float64 {
	if r == nil || math.IsNaN(*r) || math.IsInf(*r, 0) || *r <= 0 {
		return nil
	}
	v := *r
	return &v
}

// mediaItem is `media_item`: grid card for a film or series; items with an unusable
// id are dropped.
func mediaItem(dto BaseItemDto, server string) (MediaItem, bool) {
	if !isID(dto.ID) {
		return MediaItem{}, false
	}
	u := userDataOf(dto.UserData)
	var runtime *int64
	if dto.ItemType == "Movie" {
		runtime = dto.RunTimeTicks
	}
	return MediaItem{
		ID: dto.ID, Name: dto.Name, ItemType: dto.ItemType, Year: dto.ProductionYear,
		RuntimeMinutes: minutes(runtime),
		Poster:         ImageRefPtr(server, dto.ID, ImagePrimary, imageTag(dto.ImageTags, "Primary"), PosterWidths),
		AspectRatio:    positiveRatio(dto.PrimaryImageAspectRatio),
		Played:         u.Played, UnplayedCount: u.UnplayedItemCount, Favorite: u.IsFavorite,
	}, true
}

// resumedHero is `resumed_hero`: hero title of a resumed film or episode; an episode
// stands for its series, whose backdrop and logo it shows. Items without a backdrop
// are dropped.
func resumedHero(dto BaseItemDto, server string) (HeroItem, bool) {
	if !isID(dto.ID) {
		return HeroItem{}, false
	}
	point := resume(userDataOf(dto.UserData), dto.RunTimeTicks)
	switch dto.ItemType {
	case "Movie":
		item, ok := latestHero(dto, server)
		if !ok {
			return HeroItem{}, false
		}
		item.Reason = HeroResume
		item.Resume = point
		return item, true
	case "Episode":
		if dto.SeriesID == nil || !isID(*dto.SeriesID) || dto.ParentBackdropItemID == nil {
			return HeroItem{}, false
		}
		seriesID := *dto.SeriesID
		backdrop, ok := ImageRefFor(server, *dto.ParentBackdropItemID, ImageBackdrop, first(dto.ParentBackdropImageTags), BackdropWidths)
		if !ok {
			return HeroItem{}, false
		}
		var logo *ImageRef
		if dto.ParentLogoItemID != nil {
			logo = ImageRefPtr(server, *dto.ParentLogoItemID, ImageLogo, dto.ParentLogoImageTag, LogoWidths)
		}
		ep := heroEpisode(&dto)
		id := dto.ID
		return HeroItem{
			Detail: MediaItem{
				ID: seriesID, Name: textOr(dto.SeriesName, ""), ItemType: "Series",
				Poster: ImageRefPtr(server, seriesID, ImagePrimary, dto.SeriesPrimaryImageTag, PosterWidths),
			},
			Reason: HeroResume, Backdrop: backdrop, Logo: logo, Overview: text(dto.Overview),
			Episode: &ep, PlayID: &id, Resume: point,
		}, true
	}
	return HeroItem{}, false
}

// latestHero is `latest_hero`: hero title of a film or series from its own artwork.
// A film plays itself; a series gets its episode later. Items without a backdrop are
// dropped.
func latestHero(dto BaseItemDto, server string) (HeroItem, bool) {
	var isMovie bool
	switch dto.ItemType {
	case "Movie":
		isMovie = true
	case "Series":
	default:
		return HeroItem{}, false
	}
	backdrop, ok := ImageRefFor(server, dto.ID, ImageBackdrop, first(dto.BackdropImageTags), BackdropWidths)
	if !ok {
		return HeroItem{}, false
	}
	logo := ImageRefPtr(server, dto.ID, ImageLogo, imageTag(dto.ImageTags, "Logo"), LogoWidths)
	overview := text(dto.Overview)
	var point *ResumePoint
	if isMovie {
		point = resume(userDataOf(dto.UserData), dto.RunTimeTicks)
	}
	detail, ok := mediaItem(dto, server)
	if !ok {
		return HeroItem{}, false
	}
	var play *string
	if isMovie {
		id := detail.ID
		play = &id
	}
	return HeroItem{Detail: detail, Reason: HeroLatest, Backdrop: backdrop, Logo: logo, Overview: overview, PlayID: play, Resume: point}, true
}

func heroEpisode(dto *BaseItemDto) HeroEpisode {
	return HeroEpisode{Name: strings.TrimSpace(dto.Name), SeasonIndex: dto.ParentIndexNumber, Index: dto.IndexNumber}
}

// watchItem is `watch_item`: card of the "Reprendre" and "À suivre" rows; other item
// types are dropped.
func watchItem(dto BaseItemDto, server string) (WatchItem, bool) {
	if !isID(dto.ID) {
		return WatchItem{}, false
	}
	var kind WatchKind
	switch dto.ItemType {
	case "Movie":
		kind = WatchMovie
	case "Episode":
		kind = WatchEpisode
	default:
		return WatchItem{}, false
	}
	own := func(t ImageType) *ImageRef {
		return ImageRefPtr(server, dto.ID, t, imageTag(dto.ImageTags, t.String()), ThumbWidths)
	}
	var image *ImageRef
	if kind == WatchEpisode {
		image = own(ImagePrimary)
		if image == nil && dto.ParentThumbItemID != nil {
			image = ImageRefPtr(server, *dto.ParentThumbItemID, ImageThumb, dto.ParentThumbImageTag, ThumbWidths)
		}
		if image == nil && dto.ParentBackdropItemID != nil {
			image = ImageRefPtr(server, *dto.ParentBackdropItemID, ImageBackdrop, first(dto.ParentBackdropImageTags), ThumbWidths)
		}
	} else {
		image = own(ImageThumb)
		if image == nil {
			image = ImageRefPtr(server, dto.ID, ImageBackdrop, first(dto.BackdropImageTags), ThumbWidths)
		}
	}
	u := userDataOf(dto.UserData)
	var seriesName *string
	if kind == WatchEpisode {
		seriesName = text(dto.SeriesName)
	}
	return WatchItem{
		ID: dto.ID, Name: strings.TrimSpace(dto.Name), Kind: kind, SeriesName: seriesName,
		SeasonIndex: dto.ParentIndexNumber, Index: dto.IndexNumber, Image: image,
		RuntimeMinutes: minutes(dto.RunTimeTicks), Resume: resume(u, dto.RunTimeTicks),
		Played: u.Played, Favorite: u.IsFavorite,
	}, true
}

// CleanSearch is `clean_search`: search text as sent to the server: no control
// characters, single spaces, at most 100 characters.
func CleanSearch(query string) string {
	var kept strings.Builder
	for _, r := range query {
		if unicode.IsSpace(r) || !unicode.IsControl(r) {
			kept.WriteRune(r)
		}
	}
	joined := strings.Join(strings.Fields(kept.String()), " ")
	n := 0
	for i := range joined {
		if n == searchMaxChars {
			joined = joined[:i]
			break
		}
		n++
	}
	return strings.TrimRightFunc(joined, unicode.IsSpace)
}

// TitleMatches is `title_matches`: whether query starts a word of title, ignoring
// case and accents, as Emby matches titles (ADR 0011). Used on episodes, which the
// server also finds by their series name (ADR 0020).
func TitleMatches(title, query string) bool {
	t, q := fold(title), fold(query)
	if len(q) == 0 {
		return false
	}
	for i := range t {
		if (i == 0 || !isAlphanumeric(t[i-1])) && len(t)-i >= len(q) && equalRunes(t[i:i+len(q)], q) {
			return true
		}
	}
	return false
}

func equalRunes(a, b []rune) bool {
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}

// isAlphanumeric is Rust's `char::is_alphanumeric`: Alphabetic or Numeric.
func isAlphanumeric(r rune) bool {
	return unicode.IsLetter(r) || unicode.IsNumber(r) || unicode.In(r, unicode.Other_Alphabetic, unicode.Nl)
}

// accented is the table of `fold`: Latin letters with accents and their plain letter.
var accented = [...]struct {
	set   string
	plain rune
}{
	{"àáâãäåāăą", 'a'}, {"çćĉċč", 'c'}, {"ďđ", 'd'}, {"èéêëēĕėęě", 'e'}, {"ĝğġģ", 'g'},
	{"ĥħ", 'h'}, {"ìíîïĩīĭįı", 'i'}, {"ĵ", 'j'}, {"ķ", 'k'}, {"ĺļľŀł", 'l'}, {"ñńņňŉ", 'n'},
	{"òóôõöøōŏő", 'o'}, {"ŕŗř", 'r'}, {"śŝşšſ", 's'}, {"ţťŧ", 't'}, {"ùúûüũūŭůűų", 'u'},
	{"ŵ", 'w'}, {"ýÿŷ", 'y'}, {"źżž", 'z'},
}

// fold: lower case without the accents of Latin letters: `É` and `é` become `e`.
func fold(s string) []rune {
	runes := []rune(rustLowercase(s))
	for i, r := range runes {
		for _, a := range accented {
			if strings.ContainsRune(a.set, r) {
				runes[i] = a.plain
				break
			}
		}
	}
	return runes
}

func isID(id string) bool {
	if id == "" {
		return false
	}
	for i := 0; i < len(id); i++ {
		c := id[i]
		if !(isASCIIAlnum(rune(c)) || c == '-') {
			return false
		}
	}
	return true
}

// isSourceID: media source ids seen: `mediasource_30207` for an item's own file, an
// item id for a merged version (ADR 0036).
func isSourceID(id string) bool {
	if id == "" || len(id) > 128 {
		return false
	}
	for i := 0; i < len(id); i++ {
		c := id[i]
		if !(isASCIIAlnum(rune(c)) || c == '-' || c == '_') {
			return false
		}
	}
	return true
}

// minutes is `minutes`: whole minutes, when in u32 and above 0.
func minutes(ticks *int64) *uint32 {
	if ticks == nil {
		return nil
	}
	m := *ticks / ticksPerMinute
	if m <= 0 || m > math.MaxUint32 {
		return nil
	}
	v := uint32(m)
	return &v
}

// resume is `resume`: resume point of a started, unfinished item.
func resume(u UserItemData, runtimeTicks *int64) *ResumePoint {
	position := u.PlaybackPositionTicks
	if u.Played || position <= 0 {
		return nil
	}
	secs := position / sessionTicksPerSecond
	if secs > math.MaxUint32 {
		return nil
	}
	point := &ResumePoint{PositionSeconds: uint32(secs)}
	if runtimeTicks != nil && *runtimeTicks > 0 {
		f := min(max(float64(position)/float64(*runtimeTicks), 0), 1)
		point.Fraction = &f
	}
	return point
}

// playbackTitle is `playback_title`: title of a film or episode for the player (ADR 0017).
func playbackTitle(dto *BaseItemDto) *PlaybackTitle {
	name := text(&dto.Name)
	if name == nil {
		return nil
	}
	title := &PlaybackTitle{Name: *name}
	if dto.ItemType == "Episode" {
		title.SeriesName = text(dto.SeriesName)
		title.SeasonIndex = dto.ParentIndexNumber
		title.Index = dto.IndexNumber
		title.IndexEnd = dto.IndexNumberEnd
	}
	return title
}

// playlistName is `playlist_name`: a playlist name the user typed, trimmed: not
// empty, not too long, no control character.
func playlistName(name string) (string, bool) {
	name = strings.TrimSpace(name)
	if name == "" || runeCount(name) > PlaylistNameMax || strings.ContainsFunc(name, unicode.IsControl) {
		return "", false
	}
	return name, true
}

// text is `text`: trimmed, non-empty text.
func text(v *string) *string {
	if v == nil {
		return nil
	}
	t := strings.TrimSpace(*v)
	if t == "" {
		return nil
	}
	return &t
}

func textOr(v *string, fallback string) string {
	if t := text(v); t != nil {
		return *t
	}
	return fallback
}

// yearOf is `year_of`: `date?.get(..4)?.parse().ok()`.
func yearOf(date *string) *int32 {
	if date == nil || len(*date) < 4 {
		return nil
	}
	d := *date
	if len(d) > 4 && !utf8.RuneStart(d[4]) {
		return nil
	}
	y, err := strconv.ParseInt(d[:4], 10, 32)
	if err != nil {
		return nil
	}
	v := int32(y)
	return &v
}

// videoRange: display range of a video stream: `HDR 10` or `Dolby Vision Profile 8.1 (…)`.
func videoRange(s *MediaStreamDto) *string {
	rng := text(s.VideoRange)
	if rng == nil {
		return nil
	}
	detail := text(s.ExtendedVideoSubTypeDescription)
	if detail != nil && (*detail == "None" || *detail == *rng) {
		detail = nil
	}
	out := *rng
	if out == "DolbyVision" {
		out = "Dolby Vision"
		if detail != nil {
			out += " " + *detail
		}
	}
	return &out
}

func mediaInfo(source MediaSourceDto) MediaInfo {
	stream := func(s *MediaStreamDto) StreamInfo {
		return StreamInfo{
			Language: text(s.Language), Codec: text(s.Codec), Title: text(s.Title), Channels: s.Channels,
			IsDefault: s.IsDefault, IsForced: s.IsForced, IsExternal: s.IsExternal,
		}
	}
	ofType := func(t string) List[StreamInfo] {
		out := List[StreamInfo]{}
		for i := range source.MediaStreams {
			if source.MediaStreams[i].StreamType == t {
				out = append(out, stream(&source.MediaStreams[i]))
			}
		}
		return out
	}
	var id *string
	if source.ID != nil && isSourceID(*source.ID) {
		id = source.ID
	}
	var video *VideoInfo
	for i := range source.MediaStreams {
		if s := &source.MediaStreams[i]; s.StreamType == "Video" {
			video = &VideoInfo{Codec: text(s.Codec), Width: s.Width, Height: s.Height, BitDepth: s.BitDepth, Range: videoRange(s)}
			break
		}
	}
	return MediaInfo{
		ID: id, Name: text(source.Name), Container: text(source.Container), SizeBytes: source.Size,
		Bitrate: source.Bitrate, Video: video, Audio: ofType("Audio"), Subtitles: ofType("Subtitle"),
	}
}

// neighbours: episodes just before and after episodeID in the server's order, from
// the same series. None when the episode is missing from the answer.
func neighbours(items []BaseItemDto, seriesID, episodeID string) (*EpisodeLink, *EpisodeLink) {
	var kept []BaseItemDto
	for _, e := range items {
		if isID(e.ID) && e.SeriesID != nil && *e.SeriesID == seriesID {
			kept = append(kept, e)
		}
	}
	at := -1
	for i, e := range kept {
		if e.ID == episodeID {
			at = i
			break
		}
	}
	if at < 0 {
		return nil, nil
	}
	link := func(e *BaseItemDto) *EpisodeLink {
		return &EpisodeLink{ID: e.ID, Name: strings.TrimSpace(e.Name), SeasonIndex: e.ParentIndexNumber, Index: e.IndexNumber, IndexEnd: e.IndexNumberEnd}
	}
	var prev, next *EpisodeLink
	if at > 0 {
		prev = link(&kept[at-1])
	}
	if at+1 < len(kept) {
		next = link(&kept[at+1])
	}
	return prev, next
}

// episodeImages: artwork of an episode page: the series poster, its own image (else
// the series backdrop) and the series logo.
func episodeImages(dto *BaseItemDto, seriesID, server string) (poster, backdrop, logo *ImageRef) {
	backdrop = ImageRefPtr(server, dto.ID, ImagePrimary, imageTag(dto.ImageTags, "Primary"), BackdropWidths)
	if backdrop == nil && dto.ParentBackdropItemID != nil {
		backdrop = ImageRefPtr(server, *dto.ParentBackdropItemID, ImageBackdrop, first(dto.ParentBackdropImageTags), BackdropWidths)
	}
	poster = ImageRefPtr(server, seriesID, ImagePrimary, dto.SeriesPrimaryImageTag, PosterWidths)
	if dto.ParentLogoItemID != nil {
		logo = ImageRefPtr(server, *dto.ParentLogoItemID, ImageLogo, dto.ParentLogoImageTag, LogoWidths)
	}
	return poster, backdrop, logo
}

func itemDetail(dto BaseItemDto, server string) (ItemDetail, error) {
	var kind DetailKind
	switch dto.ItemType {
	case "Movie":
		kind = DetailMovie
	case "Series":
		kind = DetailSeries
	case "Episode":
		kind = DetailEpisode
	default:
		return ItemDetail{}, NewInvalidResponse("type d'élément non pris en charge : " + dto.ItemType)
	}
	if !isID(dto.ID) {
		return ItemDetail{}, errInvalidID
	}
	image := func(t ImageType, widths []uint32) *ImageRef {
		var tag *string
		if t == ImageBackdrop {
			tag = first(dto.BackdropImageTags)
		} else {
			tag = imageTag(dto.ImageTags, t.String())
		}
		return ImageRefPtr(server, dto.ID, t, tag, widths)
	}
	var ep *EpisodeContext
	if kind == DetailEpisode {
		if dto.SeriesID == nil || !isID(*dto.SeriesID) {
			return ItemDetail{}, NewInvalidResponse("épisode sans série")
		}
		var seasonID *string
		if dto.SeasonID != nil && isID(*dto.SeasonID) {
			seasonID = dto.SeasonID
		}
		ep = &EpisodeContext{
			SeriesID: *dto.SeriesID, SeriesName: textOr(dto.SeriesName, ""), SeasonID: seasonID,
			SeasonIndex: dto.ParentIndexNumber, Index: dto.IndexNumber, IndexEnd: dto.IndexNumberEnd,
		}
	}
	var poster, backdrop, logo *ImageRef
	if ep != nil {
		poster, backdrop, logo = episodeImages(&dto, ep.SeriesID, server)
	} else {
		poster, backdrop, logo = image(ImagePrimary, PosterWidths), image(ImageBackdrop, BackdropWidths), image(ImageLogo, LogoWidths)
	}
	u := userDataOf(dto.UserData)
	isSeries := kind == DetailSeries
	peopleOf := func(t string) []PersonDto {
		var out []PersonDto
		for _, p := range dto.People {
			if p.PersonType == t && strings.TrimSpace(p.Name) != "" {
				out = append(out, p)
			}
		}
		return out
	}
	names := func(people []PersonDto) List[string] {
		out := List[string]{}
		for _, p := range people {
			out = append(out, p.Name)
		}
		return out
	}
	// An episode lists its guests: the regular cast belongs to the series.
	castType := "Actor"
	if ep != nil {
		castType = "GuestStar"
	}
	cast := List[CastMember]{}
	for _, p := range peopleOf(castType) {
		if len(cast) == maxCast {
			break
		}
		id := PersonID(p)
		var photo *ImageRef
		if id != nil {
			photo = ImageRefPtr(server, *id, ImagePrimary, p.PrimaryImageTag, PhotoWidths)
		}
		cast = append(cast, CastMember{Name: p.Name, Role: text(p.Role), ID: id, Photo: photo})
	}
	name := strings.TrimSpace(dto.Name)
	original := text(dto.OriginalTitle)
	if original != nil && *original == name {
		original = nil
	}
	var endYear *int32
	var continuing *bool
	if isSeries {
		endYear = yearOf(dto.EndDate)
		if dto.Status != nil {
			switch *dto.Status {
			case "Continuing":
				continuing = ptrTo(true)
			case "Ended":
				continuing = ptrTo(false)
			}
		}
	}
	var tagline *string
	for _, t := range dto.Taglines {
		if tagline = text(&t); tagline != nil {
			break
		}
	}
	studios := List[string]{}
	if ep == nil {
		for _, st := range dto.Studios {
			if n := text(&st.Name); n != nil {
				studios = append(studios, *n)
			}
		}
	}
	var community *float64
	if r := dto.CommunityRating; r != nil && !math.IsNaN(*r) && !math.IsInf(*r, 0) && *r > 0 {
		community = ptrTo(math.Round(*r*10) / 10)
	}
	var critic *uint32
	if r := dto.CriticRating; r != nil && !math.IsNaN(*r) && *r >= 0 && *r <= 100 {
		critic = ptrTo(uint32(math.Round(*r)))
	}
	var runtime *uint32
	var point *ResumePoint
	var unplayed *uint32
	media := List[MediaInfo]{}
	if isSeries {
		unplayed = u.UnplayedItemCount
	} else {
		runtime = minutes(dto.RunTimeTicks)
		point = resume(u, dto.RunTimeTicks)
		for _, m := range dto.MediaSources {
			media = append(media, mediaInfo(m))
		}
	}
	collections := List[CollectionRef]{}
	if ep == nil {
		collections = CollectionRefs(dto.Collections)
	}
	genres := List[string](dto.Genres)
	if genres == nil {
		genres = List[string]{}
	}
	return ItemDetail{
		ID: dto.ID, Kind: kind, Name: name, OriginalTitle: original, Year: dto.ProductionYear,
		EndYear: endYear, Continuing: continuing, Overview: text(dto.Overview), Tagline: tagline,
		Genres: genres, Studios: studios, Directors: names(peopleOf("Director")), Writers: names(peopleOf("Writer")),
		Cast: cast, OfficialRating: text(dto.OfficialRating), CommunityRating: community, CriticRating: critic,
		RuntimeMinutes: runtime, PremiereDate: text(dto.PremiereDate), Poster: poster, Backdrop: backdrop,
		Logo: logo, Played: u.Played, Favorite: u.IsFavorite, Resume: point, UnplayedCount: unplayed,
		Media: media, Episode: ep, Collections: collections,
		HasLocalTrailer: ep == nil && dto.LocalTrailerCount != nil && *dto.LocalTrailerCount > 0,
	}, nil
}

func episode(dto BaseItemDto, server string) (Episode, bool) {
	if !isID(dto.ID) {
		return Episode{}, false
	}
	u := userDataOf(dto.UserData)
	return Episode{
		ID: dto.ID, Name: strings.TrimSpace(dto.Name), Index: dto.IndexNumber, IndexEnd: dto.IndexNumberEnd,
		SeasonIndex: dto.ParentIndexNumber, Overview: text(dto.Overview), RuntimeMinutes: minutes(dto.RunTimeTicks),
		PremiereDate: text(dto.PremiereDate),
		Thumb:        ImageRefPtr(server, dto.ID, ImagePrimary, imageTag(dto.ImageTags, "Primary"), ThumbWidths),
		Played:       u.Played, Resume: resume(u, dto.RunTimeTicks),
	}, true
}
