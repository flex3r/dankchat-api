import com.codahale.metrics.Slf4jReporter
import com.github.benmanes.caffeine.cache.Caffeine
import dto.EmoteSetDto
import dto.IvrEmoteSetDto
import io.ktor.application.Application
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.application.log
import io.ktor.client.HttpClient
import io.ktor.client.features.UserAgent
import io.ktor.client.features.json.JsonFeature
import io.ktor.client.features.json.serializer.KotlinxSerializer
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.future.asCompletableFuture
import kotlinx.coroutines.future.await
import kotlinx.serialization.UnstableDefault
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonConfiguration
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import utils.getOrNull
import java.util.concurrent.TimeUnit

const val IVR_EMOTE_SET_URL = "https://api.ivr.fi/twitch/emoteset/"
const val TWITCH_EMOTES_EMOTE_SET_URL = "https://api.twitchemotes.com/api/v4/sets?id="

val logger: Logger = LoggerFactory.getLogger("dankchat-api")
val cache = Caffeine.newBuilder()
    .refreshAfterWrite(30, TimeUnit.MINUTES)
    .buildAsync<String, EmoteSetDto> { key, executor ->
        val innerContext = Job() + executor.asCoroutineDispatcher()
        CoroutineScope(innerContext).async {
            val set = getSet(key)
            logger.info("Storing $key")
            set
        }.asCompletableFuture()
    }

@OptIn(UnstableDefault::class)
val client = HttpClient {
    install(UserAgent) {
        agent = "dankchat-api/1.1"
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

    routing {
        get("/") {
            call.respondText("FeelsDankMan", ContentType.Text.Plain)
        }

        get("/set/{id}") {
            val id = call.parameters["id"]
            if (id == null || id.isBlank()) {
                logger.warn("Caller: ${call.request.userAgent()} ID parameter null")
                call.respond(HttpStatusCode.BadRequest, "Invalid set id")
            } else {
                val set = cache.get(id).await()
                if (set != null) {
                    call.respond(listOf(set))
                    logger.info("Serving set $id")
                } else {
                    logger.error("Cache returned null on set $id")
                    call.respond(HttpStatusCode.NotFound)
                }
            }
        }
    }
}

fun main(args: Array<String>) {
    embeddedServer(Netty, commandLineEnvironment(args)).start()
}