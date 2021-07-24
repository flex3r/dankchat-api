package dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class EmoteSetDto(
    @SerialName("set_id") val setId: String,
    @SerialName("channel_name") val channel: String = "",
    @SerialName("channel_id") val channelId: String = "",
    val tier: Int = 1,
    val emotes: List<EmoteDto> = listOf()
)

@Serializable
data class EmoteDto(
    val code: String,
    val id: String,
    val type: String?,
    val assetType: String?
)