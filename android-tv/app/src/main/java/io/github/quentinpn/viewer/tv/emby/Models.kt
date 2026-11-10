package io.github.quentinpn.viewer.tv.emby

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

// Emby API models, limited to the fields this client uses. Shapes verified on
// Emby Server 4.10 by the Windows client (src-tauri/src/emby/models.rs).

@Serializable
data class PublicSystemInfo(
    @SerialName("ServerName") val serverName: String,
    @SerialName("Version") val version: String,
    @SerialName("Id") val id: String,
)

@Serializable
internal data class AuthenticateUserByName(
    @SerialName("Username") val username: String,
    @SerialName("Pw") val pw: String,
)

@Serializable
class AuthenticationResult(
    @SerialName("User") val user: UserDto,
    @SerialName("AccessToken") val accessToken: String,
    @SerialName("ServerId") val serverId: String,
) {
    override fun toString() = "AuthenticationResult(user=$user, accessToken=<redacted>, serverId=$serverId)"
}

@Serializable
data class UserDto(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String,
)

/**
 * The track fields of `UserDto.Configuration` (`UserConfiguration`, ADR 0065). The
 * other fields, `ProfilePin` among them, are never decoded. `SubtitleMode` stays text:
 * an unknown value then means no preference rather than an unreadable user.
 */
@Serializable
data class UserConfigurationDto(
    @SerialName("AudioLanguagePreference") val audioLanguagePreference: String? = null,
    @SerialName("PlayDefaultAudioTrack") val playDefaultAudioTrack: Boolean = false,
    @SerialName("SubtitleLanguagePreference") val subtitleLanguagePreference: String? = null,
    @SerialName("SubtitleMode") val subtitleMode: String? = null,
)

/** `GET /Users/{Id}` read for its `Configuration` only; its shape is checked by [EmbyClient.userConfiguration]. */
@Serializable
internal data class UserWithConfiguration(
    @SerialName("Configuration") val configuration: JsonElement? = null,
)

@Serializable
data class QueryResult<T>(
    @SerialName("Items") val items: List<T> = emptyList(),
    @SerialName("TotalRecordCount") val totalRecordCount: Int = 0,
)

/** One section of the user's home screen as the server composes it (`/Users/{Id}/HomeSections`). */
@Serializable
data class HomeSectionDto(
    @SerialName("Id") val id: String,
    @SerialName("SectionType") val sectionType: String = "",
    /** The "resume" section also lists the next episode of each series in progress. */
    @SerialName("IncludeNextUpInResume") val includeNextUpInResume: Boolean = false,
)

@Serializable
data class BaseItemDto(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String = "",
    @SerialName("Type") val type: String = "",
    @SerialName("CollectionType") val collectionType: String? = null,
    @SerialName("ProductionYear") val productionYear: Int? = null,
    @SerialName("Overview") val overview: String? = null,
    @SerialName("SeriesName") val seriesName: String? = null,
    @SerialName("SeriesId") val seriesId: String? = null,
    /** An episode's season; a special aired within a season has the specials' one (ADR 0016, gate 2). */
    @SerialName("SeasonId") val seasonId: String? = null,
    @SerialName("ParentIndexNumber") val parentIndexNumber: Int? = null,
    @SerialName("IndexNumber") val indexNumber: Int? = null,
    /** Last number of a double episode (ADR 0016). */
    @SerialName("IndexNumberEnd") val indexNumberEnd: Int? = null,
    /** An episode's air date, ISO 8601 in UTC (`2024-07-04T22:00:00.0000000Z`). */
    @SerialName("PremiereDate") val premiereDate: String? = null,
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("ImageTags") val imageTags: Map<String, String> = emptyMap(),
    @SerialName("BackdropImageTags") val backdropImageTags: List<String> = emptyList(),
    @SerialName("ParentBackdropItemId") val parentBackdropItemId: String? = null,
    @SerialName("ParentBackdropImageTags") val parentBackdropImageTags: List<String> = emptyList(),
    @SerialName("ParentThumbItemId") val parentThumbItemId: String? = null,
    @SerialName("ParentThumbImageTag") val parentThumbImageTag: String? = null,
    /** An episode's series logo, when `EnableImageTypes` holds `Logo` (ADR 0015). */
    @SerialName("ParentLogoItemId") val parentLogoItemId: String? = null,
    @SerialName("ParentLogoImageTag") val parentLogoImageTag: String? = null,
    @SerialName("UserData") val userData: UserItemData? = null,
    // Detail fields: `/Users/{Id}/Items/{Id}` returns them without `Fields`
    @SerialName("Genres") val genres: List<String> = emptyList(),
    @SerialName("Studios") val studios: List<NameDto> = emptyList(),
    @SerialName("People") val people: List<PersonDto> = emptyList(),
    @SerialName("OfficialRating") val officialRating: String? = null,
    @SerialName("CommunityRating") val communityRating: Double? = null,
    /** Out of 100; sent as an integer (`17`) by Emby 4.10, documented as a number (ADR 0074). */
    @SerialName("CriticRating") val criticRating: Double? = null,
    /** Key case varies (`Imdb` on an episode, `IMDB` on its series): sent to the intro services only (ADR 0048). */
    @SerialName("ProviderIds") val providerIds: Map<String, String> = emptyMap(),
    @SerialName("Chapters") val chapters: List<ChapterInfo> = emptyList(),
    /** The versions of a film or episode (ADR 0058); PlaybackInfo decides how each one plays. */
    @SerialName("MediaSources") val mediaSources: List<MediaSourceDto> = emptyList(),
    /**
     * The BoxSets holding the item, documented as `NameLongIdPair[]` (ADR 0071). Kept raw and
     * read by [collectionRefs]: a shape the client does not expect hides the row, not the film.
     */
    @SerialName("Collections") val rawCollections: JsonElement? = null,
    /** Trailers on the web (`MediaUrl[]`, ADR 0083), usually YouTube; opened outside the app once checked by [trailerUrl]. */
    @SerialName("RemoteTrailers") val remoteTrailers: List<MediaUrlDto> = emptyList(),
    /** Trailer files on the server (ADR 0083), listed by [EmbyClient.localTrailers]. */
    @SerialName("LocalTrailerCount") val localTrailerCount: Int? = null,
)

/** `MediaUrl` of `RemoteTrailers`: both fields optional, so an incomplete entry is skipped, not the item. */
@Serializable
data class MediaUrlDto(
    @SerialName("Url") val url: String? = null,
    @SerialName("Name") val name: String? = null,
)

/** One version in an item's detail, as `MediaSourceDto` on Windows (ADR 0036). */
@Serializable
data class MediaSourceDto(
    /** What PlaybackInfo's `MediaSourceId` names. */
    @SerialName("Id") val id: String? = null,
    @SerialName("Name") val name: String? = null,
    @SerialName("Size") val size: Long? = null,
    @SerialName("Bitrate") val bitrate: Long? = null,
    @SerialName("MediaStreams") val mediaStreams: List<SourceStreamDto> = emptyList(),
)

/** The stream fields a version label needs; `Index` is not read, so its absence breaks nothing. */
@Serializable
data class SourceStreamDto(
    @SerialName("Type") val type: String = "",
    @SerialName("Codec") val codec: String? = null,
    @SerialName("Width") val width: Int? = null,
    @SerialName("Height") val height: Int? = null,
    @SerialName("BitDepth") val bitDepth: Int? = null,
    /** `SDR`, `HDR 10`, `DolbyVision`. */
    @SerialName("VideoRange") val videoRange: String? = null,
    /** `HDR 10`, `Profile 8.1 (HDR10 compatible)`, `None`. */
    @SerialName("ExtendedVideoSubTypeDescription") val extendedVideoSubTypeDescription: String? = null,
)

/** `Studios[].Id` is a number on Emby 4.10, unlike other ids: only the name is read. */
@Serializable
data class NameDto(@SerialName("Name") val name: String = "")

/** `BaseItemPerson` of an item's `People` (ADR 0016, 0068). */
@Serializable
data class PersonDto(
    @SerialName("Name") val name: String = "",
    /** `Actor`, `Director`, `Writer`, `Producer`, `GuestStar`… */
    @SerialName("Type") val type: String = "",
    /** The character played; often absent for crew. */
    @SerialName("Role") val role: String? = null,
    /**
     * Documented as a string and sent as one by Emby 4.10, but `Studios[].Id` there is a
     * number: read as any primitive, so a number would not make the whole item unreadable.
     */
    @SerialName("Id") val rawId: JsonPrimitive? = null,
    /** Tag of the person's photo, the `Primary` image of the person item [id]. */
    @SerialName("PrimaryImageTag") val primaryImageTag: String? = null,
) {
    /** The person item's id, what `PersonIds` and the image path take; null when absent. */
    val id: String? get() = rawId?.takeUnless { it is JsonNull }?.content?.trim()?.takeIf { it.isNotEmpty() }
}

/** `MarkerType` is `Chapter`, `IntroStart`, `IntroEnd` or `CreditsStart` (ADR 0030). */
@Serializable
data class ChapterInfo(
    @SerialName("StartPositionTicks") val startPositionTicks: Long = 0,
    @SerialName("Name") val name: String? = null,
    @SerialName("MarkerType") val markerType: String? = null,
)

@Serializable
data class UserItemData(
    @SerialName("Played") val played: Boolean = false,
    @SerialName("PlaybackPositionTicks") val playbackPositionTicks: Long = 0,
    /** Series and seasons only: episodes not yet watched. */
    @SerialName("UnplayedItemCount") val unplayedItemCount: Int? = null,
)

@Serializable
internal data class PlaybackInfoRequest(
    @SerialName("UserId") val userId: String,
    @SerialName("MaxStreamingBitrate") val maxStreamingBitrate: Long,
    @SerialName("DeviceProfile") val deviceProfile: DeviceProfile,
    @SerialName("IsPlayback") val isPlayback: Boolean = true,
    @SerialName("AutoOpenLiveStream") val autoOpenLiveStream: Boolean = false,
    @SerialName("EnableDirectPlay") val enableDirectPlay: Boolean = true,
    @SerialName("EnableDirectStream") val enableDirectStream: Boolean = true,
    /** The version to play (ADR 0036, 0058); left out, the server picks its first. */
    @SerialName("MediaSourceId") val mediaSourceId: String? = null,
    /** Where playback goes on, when the quality changes mid-playback (ADR 0081); left out otherwise. */
    @SerialName("StartTimeTicks") val startTimeTicks: Long? = null,
    /**
     * Emby `Index` of the audio a transcode must carry (ADR 0084); left out otherwise.
     * Only sent with [mediaSourceId]: Emby 4.10.1.0 ignores it without (ADR 0035).
     */
    @SerialName("AudioStreamIndex") val audioStreamIndex: Int? = null,
)

@Serializable
data class DeviceProfile(
    @SerialName("Name") val name: String,
    @SerialName("MaxStreamingBitrate") val maxStreamingBitrate: Long,
    @SerialName("DirectPlayProfiles") val directPlayProfiles: List<DirectPlayProfile>,
    @SerialName("TranscodingProfiles") val transcodingProfiles: List<TranscodingProfile>,
    @SerialName("SubtitleProfiles") val subtitleProfiles: List<SubtitleProfile>,
)

@Serializable
data class DirectPlayProfile(
    @SerialName("Type") val type: String,
    @SerialName("Container") val container: String,
    @SerialName("VideoCodec") val videoCodec: String,
    @SerialName("AudioCodec") val audioCodec: String,
)

@Serializable
data class TranscodingProfile(
    @SerialName("Type") val type: String,
    @SerialName("Container") val container: String,
    @SerialName("Protocol") val protocol: String,
    @SerialName("VideoCodec") val videoCodec: String,
    @SerialName("AudioCodec") val audioCodec: String,
    @SerialName("Context") val context: String,
    @SerialName("MaxAudioChannels") val maxAudioChannels: String,
    @SerialName("BreakOnNonKeyFrames") val breakOnNonKeyFrames: Boolean,
)

@Serializable
data class SubtitleProfile(
    @SerialName("Format") val format: String,
    @SerialName("Method") val method: String,
)

@Serializable
data class PlaybackInfoResponse(
    @SerialName("MediaSources") val mediaSources: List<MediaSourceInfo> = emptyList(),
    @SerialName("PlaySessionId") val playSessionId: String? = null,
    @SerialName("ErrorCode") val errorCode: String? = null,
)

@Serializable
data class MediaSourceInfo(
    @SerialName("Id") val id: String,
    @SerialName("Container") val container: String? = null,
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("SupportsDirectPlay") val supportsDirectPlay: Boolean = false,
    @SerialName("SupportsDirectStream") val supportsDirectStream: Boolean = false,
    @SerialName("SupportsTranscoding") val supportsTranscoding: Boolean = false,
    @SerialName("DirectStreamUrl") val directStreamUrl: String? = null,
    @SerialName("TranscodingUrl") val transcodingUrl: String? = null,
    @SerialName("RequiredHttpHeaders") val requiredHttpHeaders: Map<String, String>? = null,
    /** Audio the server picked from the account's preferences (its `Configuration`, ADR 0035). */
    @SerialName("DefaultAudioStreamIndex") val defaultAudioStreamIndex: Int? = null,
    /** Subtitle the server picked from the account's preferences. */
    @SerialName("DefaultSubtitleStreamIndex") val defaultSubtitleStreamIndex: Int? = null,
    @SerialName("MediaStreams") val mediaStreams: List<MediaStreamDto> = emptyList(),
)

/**
 * Body of `POST /Sessions/Playing`, `/Sessions/Playing/Progress` and
 * `/Sessions/Playing/Stopped` (ADR 0008). Null fields are left out.
 */
@Serializable
data class PlaybackReport(
    @SerialName("ItemId") val itemId: String,
    @SerialName("MediaSourceId") val mediaSourceId: String,
    @SerialName("PlaySessionId") val playSessionId: String? = null,
    @SerialName("PositionTicks") val positionTicks: Long,
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("IsPaused") val isPaused: Boolean? = null,
    @SerialName("CanSeek") val canSeek: Boolean? = null,
    @SerialName("PlayMethod") val playMethod: String? = null,
    @SerialName("EventName") val eventName: String? = null,
    @SerialName("Failed") val failed: Boolean? = null,
)

@Serializable
data class MediaStreamDto(
    @SerialName("Index") val index: Int,
    @SerialName("Type") val type: String = "",
    @SerialName("Title") val title: String? = null,
    @SerialName("Language") val language: String? = null,
    @SerialName("DeliveryMethod") val deliveryMethod: String? = null,
    @SerialName("DeliveryUrl") val deliveryUrl: String? = null,
    /** A file beside the media (`.srt`…), not one of its own streams. */
    @SerialName("IsExternal") val isExternal: Boolean = false,
    /** Named in a transcode's subtitle files, which carry no title of their own (ADR 0084). */
    @SerialName("IsForced") val isForced: Boolean = false,
    @SerialName("IsHearingImpaired") val isHearingImpaired: Boolean = false,
)
