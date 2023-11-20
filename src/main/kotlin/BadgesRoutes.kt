import db.User
import dto.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.transactions.transaction
import utils.asWatchFlow
import utils.getOrNull
import utils.timer
import utils.watch
import java.io.File

fun Route.getBadgesRoute() {
    get("/badges") {
        val badges = transaction { allBadges }
        call.respond(badges)
    }
}

const val SE_TIPS_URL = "https://api.streamelements.com/kappa/v2/tips/5b144fc91a5cbe3a3a920871"
const val SE_CHANNEL_URL = "https://api.streamelements.com/kappa/v2/channels/"
const val IVR_V2_TWITCH_USER_URL = "https://api.ivr.fi/v2/twitch/user"

val gold = listOf("73697410")
val contributors = mutableListOf<String>()
val top = mutableListOf<String>()
val optouts = mutableListOf<String>()
val danks = mutableListOf<BadgeDto>()

val allBadges: List<BadgeDto>
    get() = danks + defaultBadges + supporters

val defaultBadges: List<BadgeDto>
    get() = listOf(
        BadgeDto("DankChat Developer", "https://flxrs.com/dankchat/badges/gold.png", gold),
        BadgeDto("DankChat Top Supporter", "https://flxrs.com/dankchat/badges/top.png", top),
        BadgeDto("DankChat Contributor", "https://flxrs.com/dankchat/badges/contributor.png", contributors)
    )

val supporters: BadgeDto
    get() {
        val users = User.all()
            .map { it.twitchId }
            .filter { it.isNotBlank() }
            .filter { it !in optouts }

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
    File("/opt/dankchat-api/contributors.txt").watch {
        val lines = it.readLines()
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
    File("/opt/dankchat-api/danks.json").watch {
        val text = it.readText()
        val parsedDanks = Json.decodeFromString<List<BadgeDto>>(text)
        danks.clear()
        danks.addAll(parsedDanks)
        logger.info("Detected danks list change: $danks")
    }
}

suspend fun getOptouts() = withContext(Dispatchers.IO) {
    File("/opt/dankchat-api/optouts.txt").watch {
        val lines = it.readLines()
        optouts.clear()
        optouts.addAll(lines)
        logger.info("Detected opt-outs list change")
    }
}

suspend fun getNewDonations(seToken: String): List<Triple<String, String, String>> {
    var offset = 0
    val donations = mutableListOf<DonationDocDto>()
    val (docs, total) = getDonationsWithOffset(offset, seToken) ?: return emptyList()
    donations.addAll(docs)

    while (offset + 100 <= total) {
        offset += 100
        getDonationsWithOffset(offset, seToken)?.let { (docs, _) -> donations.addAll(docs) }
    }

    val savedIds = transaction {
        User.all().map { it.seChannelId }
    }
    return donations.distinctBy { it.donation.user.channel }
        .filter { donation -> savedIds.none { donation.donation.user.channel == it } }
        .mapNotNull {
            val channel = it.donation.user.channel
            val user = it.donation.user.username
            val twitchId = getTwitchId(channel, seToken) ?: getTwitchIdFallback(user)
            if (twitchId == null) {
                logger.error("Failed to get twitch id of channel ${channel}, user $user")
                return@mapNotNull null
            }

            if (twitchId in optouts) {
                return@mapNotNull null
            }

            Triple(
                channel,
                user,
                twitchId
            )
        }
}

suspend fun getDonationsWithOffset(offset: Int = 0, seToken: String): Pair<List<DonationDocDto>, Int>? {
    val config: HttpRequestBuilder.() -> Unit = {
        parameter("limit", 100)
        parameter("offset", offset)
        accept(ContentType.Application.Json)
        header("Authorization", "Bearer $seToken")
    }

    return client.getOrNull<DonationsV2Dto>(SE_TIPS_URL, config)?.let {
        it.docs to it.totalDocs
    } ?: client.getOrNull<DonationsDto>(SE_TIPS_URL, config)?.let {
        it.docs to it.total
    }
}

suspend fun getTwitchId(channelId: String, seToken: String): String? {
    return client.getOrNull<ChannelDto>("$SE_CHANNEL_URL$channelId") {
        accept(ContentType.Application.Json)
        header("Authorization", "Bearer $seToken")
    }?.takeIf { it.provider == "twitch" }?.providerId
}

suspend fun getTwitchIdFallback(channel: String): String? {
    return client.getOrNull<List<IvrTwitchUserDto>>(IVR_V2_TWITCH_USER_URL) {
        parameter("login", channel)
        accept(ContentType.Application.Json)
    }?.firstOrNull()?.id
}