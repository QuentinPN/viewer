package io.github.quentinpn.viewer.tv.emby

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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

@Serializable
data class QueryResult<T>(
    @SerialName("Items") val items: List<T> = emptyList(),
    @SerialName("TotalRecordCount") val totalRecordCount: Int = 0,
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
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("ImageTags") val imageTags: Map<String, String> = emptyMap(),
    @SerialName("BackdropImageTags") val backdropImageTags: List<String> = emptyList(),
    @SerialName("ParentBackdropItemId") val parentBackdropItemId: String? = null,
    @SerialName("ParentBackdropImageTags") val parentBackdropImageTags: List<String> = emptyList(),
    @SerialName("ParentThumbItemId") val parentThumbItemId: String? = null,
    @SerialName("ParentThumbImageTag") val parentThumbImageTag: String? = null,
    @SerialName("UserData") val userData: UserItemData? = null,
    // Detail fields: `/Users/{Id}/Items/{Id}` returns them without `Fields`
    @SerialName("Genres") val genres: List<String> = emptyList(),
    @SerialName("Studios") val studios: List<NameDto> = emptyList(),
    @SerialName("People") val people: List<PersonDto> = emptyList(),
    @SerialName("OfficialRating") val officialRating: String? = null,
    @SerialName("CommunityRating") val communityRating: Double? = null,
    /** Key case varies (`Imdb` on an episode, `IMDB` on its series): sent to the intro services only (ADR 0048). */
    @SerialName("ProviderIds") val providerIds: Map<String, String> = emptyMap(),
    @SerialName("Chapters") val chapters: List<ChapterInfo> = emptyList(),
)

/** `Studios[].Id` is a number on Emby 4.10, unlike other ids: only the name is read. */
@Serializable
data class NameDto(@SerialName("Name") val name: String = "")

@Serializable
data class PersonDto(
    @SerialName("Name") val name: String = "",
    /** `Actor`, `Director`, `Writer`, `Producer`, `GuestStar`… */
    @SerialName("Type") val type: String = "",
)

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
)
