package dto

import kotlinx.serialization.Serializable

@Serializable
data class ChannelDto(val provider: String, val providerId: String)