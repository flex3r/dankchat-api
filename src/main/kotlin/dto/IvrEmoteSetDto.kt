package dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class IvrEmoteSetDto(
    val channel: String?,
    @SerialName("channelid") val channelId: String?,
    val tier: String?,
    val emotes: List<IvrEmoteDto>
)

@Serializable
data class IvrBulkEmoteSetDto(
    @SerialName("setID") val id: String,
    @SerialName("channelLogin") val channel: String?,
    @SerialName("channelID") val channelId: String?,
    @SerialName("tier") val tier: String?,
    @SerialName("emoteList") val emotes: List<IvrBulkEmoteDto>
)

@Serializable
data class IvrBulkEmoteDto(
    @SerialName("code") val code: String,
    @SerialName("id") val id: String,
    @SerialName("type") val type: String,
    @SerialName("assetType") val assetType: String,
)

@Serializable
data class IvrEmoteDto(
    val token: String,
    val id: String
)