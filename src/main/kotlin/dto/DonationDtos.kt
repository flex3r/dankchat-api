package dto

import kotlinx.serialization.Serializable

@Serializable
data class DonationsDto(val docs: List<DonationDocDto>, val total: Int)

@Serializable
data class DonationsV2Dto(val docs: List<DonationDocDto>, val totalDocs: Int)

@Serializable
data class DonationDocDto(val donation: DonationDto)

@Serializable
data class DonationDto(val user: DonationUserDto, val amount: Double)

@Serializable
data class DonationUserDto(val username: String, val channel: String)