import com.codahale.metrics.Slf4jReporter
import com.github.benmanes.caffeine.cache.Caffeine
import db.Database
import db.User
import dto.*
import io.ktor.application.Application
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.application.log
import io.ktor.client.HttpClient
import io.ktor.client.features.UserAgent
import io.ktor.client.features.json.JsonFeature
import io.ktor.client.features.json.serializer.KotlinxSerializer
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.features.CallLogging
import io.ktor.features.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.metrics.dropwizard.DropwizardMetrics
import io.ktor.request.userAgent
import io.ktor.response.respond
import io.ktor.response.respondText
import io.ktor.routing.get
import io.ktor.routing.routing
import io.ktor.serialization.json
import io.ktor.server.engine.commandLineEnvironment
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.*
import kotlinx.coroutines.future.asCompletableFuture
import kotlinx.coroutines.future.await
import kotlinx.serialization.UnstableDefault
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonConfiguration
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import utils.getOrNull
import utils.timer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

const val IVR_EMOTE_SET_URL = "https://api.ivr.fi/twitch/emoteset/"
const val TWITCH_EMOTES_EMOTE_SET_URL = "https://api.twitchemotes.com/api/v4/sets?id="
const val SE_TIPS_URL = "https://api.streamelements.com/kappa/v2/tips/5b144fc91a5cbe3a3a920871"
const val SE_CHANNEL_URL = "https://api.streamelements.com/kappa/v2/channels/"

val logger: Logger = LoggerFactory.getLogger("dankchat-api")
val emoteSetCache = Caffeine.newBuilder()
    .refreshAfterWrite(30, TimeUnit.MINUTES)
    .buildAsync<String, EmoteSetDto> { key, executor ->
        val innerContext = Job() + executor.asCoroutineDispatcher()
        CoroutineScope(innerContext).async {
            getSet(key).also { logger.info("Storing $key") }
        }.asCompletableFuture()
    }

val SE_TOKEN: String? = System.getenv("SE_TOKEN")
val pollDispatcher = Executors.newFixedThreadPool(1).asCoroutineDispatcher()

@OptIn(UnstableDefault::class)
val client = HttpClient {
    install(UserAgent) {
        agent = "dankchat-api/1.2"
    }
    install(JsonFeature) {
        serializer = KotlinxSerializer(
            Json(
                JsonConfiguration(
                    useArrayPolymorphism = true,
                    ignoreUnknownKeys = true
                )
            )
        )
    }
}

suspend fun getSet(id: String): EmoteSetDto? {
    return client.getOrNull<IvrEmoteSetDto>("$IVR_EMOTE_SET_URL$id")?.run {
        EmoteSetDto(
            setId = id,
            channel = channel?.takeIf { it != "qa_TW_Partner" } ?: "Twitch",
            channelId = channelId ?: "0",
            tier = tier?.toIntOrNull() ?: 1
        )
    } ?: client.getOrNull<List<EmoteSetDto>>("$TWITCH_EMOTES_EMOTE_SET_URL$id")?.firstOrNull() ?: EmoteSetDto(id)
}

fun pollDonations() = CoroutineScope(pollDispatcher).launch {
    timer(5 * 60 * 1000) {
        val newDonations = getNewDonations()
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

suspend fun getNewDonations(): List<Triple<String, String, String>> {
    var offset = 0
    val donations = mutableListOf<DonationDocDto>()
    val result = getDonationsWithOffset(offset) ?: return emptyList()
    donations.addAll(result.docs)

    while (offset + 100 <= result.total) {
        offset += 100
        getDonationsWithOffset(offset)?.let { donations.addAll(it.docs) }
    }
    val savedIds = transaction {
        User.all().map { it.seChannelId }
    }
    return donations.distinctBy { it.donation.user.channel }
        .filter { donation -> savedIds.none { donation.donation.user.channel == it } }
        .map { Triple(it.donation.user.channel, it.donation.user.username, getTwitchId(it.donation.user.channel) ?: "") }
}

suspend fun getDonationsWithOffset(offset: Int = 0): DonationsDto? {
    return client.getOrNull<DonationsDto>(SE_TIPS_URL) {
        parameter("limit", 100)
        parameter("offset", offset)
        accept(ContentType.Application.Json)
        header("Authorization", "Bearer $SE_TOKEN")
    }
}

suspend fun getTwitchId(channelId: String): String? {
    return client.getOrNull<ChannelDto>("$SE_CHANNEL_URL$channelId") {
        accept(ContentType.Application.Json)
        header("Authorization", "Bearer $SE_TOKEN")
    }?.takeIf { it.provider == "twitch" }?.providerId
}


fun getDefaultBadges(): List<BadgeDto> = listOf(
    BadgeDto("DankChat Developer", "https://flxrs.com/dankchat/badges/gold.png", listOf("73697410"))
)

fun getSupporters(): BadgeDto {
    val users = User.all().map { it.twitchId }
    return BadgeDto("DankChat Supporter", "https://flxrs.com/dankchat/badges/dank.png", users)
}

fun getBadges(): List<BadgeDto> = getDefaultBadges().plus(getSupporters())

fun Application.main() {
    install(CallLogging) {
        level = Level.INFO
    }
    install(DropwizardMetrics) {
        Slf4jReporter.forRegistry(registry)
            .outputTo(log)
            .convertRatesTo(TimeUnit.MINUTES)
            .convertDurationsTo(TimeUnit.MILLISECONDS)
            .build()
            .start(1, TimeUnit.HOURS)
    }.registry.removeMatching { name, _ ->
        name.startsWith("jvm")
    }
    install(ContentNegotiation) {
        json()
    }

    Database.init()
    pollDonations()

    routing {
        get("/") {
            call.respondText("FeelsDankMan", ContentType.Text.Plain)
        }

        get("/set/{id}") {
            val id = call.parameters["id"]
            when {
                id.isNullOrBlank() -> {
                    logger.warn("Caller: ${call.request.userAgent()} ID parameter null")
                    call.respond(HttpStatusCode.BadRequest, "Invalid set id")
                }
                else -> {
                    val set = emoteSetCache.get(id).await()
                    if (set != null) {
                        logger.info("Serving set $id")
                        call.respond(listOf(set))
                    } else {
                        logger.warn("Cache returned null on set $id")
                        call.respond(HttpStatusCode.NotFound)
                    }
                }
            }
        }

        get("/badges") {
            val badges = transaction { getBadges() }
            call.respond(badges)
        }
    }
}

fun main(args: Array<String>) {
    if (SE_TOKEN == null) {
        logger.error("No Streamelements token present, exiting")
        return
    }

    listOf(
        "com.zaxxer.hikari.pool.HikariPool",
        "com.zaxxer.hikari.pool.PoolBase",
        "com.zaxxer.hikari.HikariConfig",
        "com.zaxxer.hikari.HikariDataSource",
        "com.zaxxer.hikari.util.DriverDataSource",
        "Exposed"
    ).forEach {
        val logger = LoggerFactory.getLogger(it) as ch.qos.logback.classic.Logger
        logger.level = ch.qos.logback.classic.Level.INFO
    }
    embeddedServer(Netty, commandLineEnvironment(args)).start()
}