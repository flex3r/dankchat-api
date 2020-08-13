package dto

import kotlinx.serialization.Serializable

@Serializable
data class BadgeDto (val type: String, val url: String, val users: List<String>)