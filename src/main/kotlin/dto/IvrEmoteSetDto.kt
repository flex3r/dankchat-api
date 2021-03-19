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
data class IvrEmoteDto(
    val token: String,
    val id: String
)