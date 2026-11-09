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
    @SerialName("SeriesName") val seriesName: String? = null,
    @SerialName("SeriesId") val seriesId: String? = null,
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
)

@Serializable
data class UserItemData(
    @SerialName("Played") val played: Boolean = false,
    @SerialName("PlaybackPositionTicks") val playbackPositionTicks: Long = 0,
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

@Serializable
data class MediaStreamDto(
    @SerialName("Index") val index: Int,
    @SerialName("Type") val type: String = "",
    @SerialName("Title") val title: String? = null,
    @SerialName("Language") val language: String? = null,
    @SerialName("DeliveryMethod") val deliveryMethod: String? = null,
    @SerialName("DeliveryUrl") val deliveryUrl: String? = null,
)
