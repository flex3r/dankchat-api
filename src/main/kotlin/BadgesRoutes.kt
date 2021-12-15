import db.User
import dto.*
import io.ktor.application.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.response.*
import io.ktor.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.transactions.transaction
import utils.asWatchFlow
import utils.getOrNull
import utils.timer
import java.io.File

fun Route.getBadgesRoute() {
    get("/badges") {
        val badges = transaction { allBadges }
        call.respond(badges)
    }
}

const val SE_TIPS_URL = "https://api.streamelements.com/kappa/v2/tips/5b144fc91a5cbe3a3a920871"
const val SE_CHANNEL_URL = "https://api.streamelements.com/kappa/v2/channels/"
const val IVR_V2_TWITCH_USER_URL = "https://api.ivr.fi/v2/twitch/user/"

val contributors = mutableListOf<String>()
val top = mutableListOf<String>()
val danks = mutableListOf<BadgeDto>()

val allBadges: List<BadgeDto>
    get() = danks + defaultBadges + supporters

val defaultBadges: List<BadgeDto>
    get() = listOf(
        BadgeDto("DankChat Developer", "https://flxrs.com/dankchat/badges/gold.png", listOf("73697410")),
        BadgeDto("DankChat Top Supporter", "https://flxrs.com/dankchat/badges/top.png", top),
        BadgeDto("DankChat Contributor", "https://flxrs.com/dankchat/badges/contributor.png", contributors)
    )

val supporters: BadgeDto
    get() {
        val users = User.all()
            .map { it.twitchId }
            .filter { it.isNotBlank() }

        return BadgeDto("DankChat Supporter", "https://flxrs.com/dankchat/badges/dank.png", users)
    }

suspend fun pollDonations(seToken: String) = withContext(Dispatchers.IO) {
    timer(5 * 60 * 1000L) {
        val newDonations = getNewDonations(seToken)
        if (newDonations.isNotEmpty()) {
            logger.info("New donations detected: $newDonations")
        }

        transaction {
            newDonations.forEach { (seId, name, twitchId) ->
                User.new {
                    this.seChannelId = seId
                    this.name = name
                    this.twitchId = twitchId
                }
            }
        }
    }
}

suspend fun getContributors() = withContext(Dispatchers.IO) {
    val contributorsFile = File("/opt/dankchat-api/contributors.txt")
    if (!contributorsFile.exists()) return@withContext

    contributorsFile.asWatchFlow()
        .catch { logger.error("FileWatcher returned an error: ", it) }
        .collectLatest { file ->
            val lines = file.readLines()
            contributors.clear()
            contributors.addAll(lines)
            logger.info("Detected contributor list change: $contributors")
        }
}

suspend fun getTop() = withContext(Dispatchers.IO) {
    val topFile = File("/opt/dankchat-api/top.txt")
    if (!topFile.exists()) return@withContext

    topFile.asWatchFlow()
        .catch { logger.error("FileWatcher returned an error: ", it) }
        .collectLatest { file ->
            val lines = file.readLines()
            top.clear()
            top.addAll(lines)
            logger.info("Detected top list change: $top")
        }
}

suspend fun getDanks() = withContext(Dispatchers.IO) {
    val danksFile = File("/opt/dankchat-api/danks.json")
    if (!danksFile.exists()) return@withContext

    danksFile.asWatchFlow()
        .catch { logger.error("FileWatcher returned an error: ", it) }
        .collectLatest { file ->
            runCatching {
                val text = file.readText()
                val parsedDanks = Json.decodeFromString<List<BadgeDto>>(text)
                danks.clear()
                danks.addAll(parsedDanks)
                logger.info("Detected danks list change: $danks")
            }
        }
}

suspend fun getNewDonations(seToken: String): List<Triple<String, String, String>> {
    var offset = 0
    val donations = mutableListOf<DonationDocDto>()
    val result = getDonationsWithOffset(offset, seToken) ?: return emptyList()
    donations.addAll(result.docs)

    while (offset + 100 <= result.total) {
        offset += 100
        getDonationsWithOffset(offset, seToken)?.let { donations.addAll(it.docs) }
    }

    val savedIds = transaction {
        User.all().map { it.seChannelId }
    }
    return donations.distinctBy { it.donation.user.channel }
        .filter { donation -> savedIds.none { donation.donation.user.channel == it } }
        .mapNotNull {
            val twitchId = getTwitchId(it.donation.user.channel, seToken) ?: getTwitchIdFallback(it.donation.user.username)
            if (twitchId == null) {
                logger.error("Failed to get twitch id of channel ${it.donation.user.channel}, user ${it.donation.user.username}")
                return@mapNotNull null
            }

            Triple(
                it.donation.user.channel,
                it.donation.user.username,
                twitchId
            )
        }
}

suspend fun getDonationsWithOffset(offset: Int = 0, seToken: String): DonationsDto? {
    return client.getOrNull<DonationsDto>(SE_TIPS_URL) {
        parameter("limit", 100)
        parameter("offset", offset)
        accept(ContentType.Application.Json)
        header("Authorization", "Bearer $seToken")
    }
}

suspend fun getTwitchId(channelId: String, seToken: String): String? {
    return client.getOrNull<ChannelDto>("$SE_CHANNEL_URL$channelId") {
        accept(ContentType.Application.Json)
        header("Authorization", "Bearer $seToken")
    }?.takeIf { it.provider == "twitch" }?.providerId
}

suspend fun getTwitchIdFallback(channel: String): String? {
    return client.getOrNull<IvrTwitchUserDto>("$IVR_V2_TWITCH_USER_URL$channel") {
        accept(ContentType.Application.Json)
    }?.id
}