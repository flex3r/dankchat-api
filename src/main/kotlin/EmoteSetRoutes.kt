import com.github.benmanes.caffeine.cache.AsyncLoadingCache
import com.github.benmanes.caffeine.cache.Caffeine
import dto.EmoteDto
import dto.EmoteSetDto
import dto.IvrEmoteSetDto
import io.ktor.application.*
import io.ktor.http.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.future.await
import kotlinx.coroutines.future.future
import utils.getOrNull
import java.util.concurrent.TimeUnit

fun Route.getEmoteSetRoute() {
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
}

const val IVR_EMOTE_SET_URL = "https://api.ivr.fi/twitch/emoteset/"
const val TWITCH_EMOTES_EMOTE_SET_URL = "https://api.twitchemotes.com/api/v4/sets?id="

val emoteSetCache: AsyncLoadingCache<String, EmoteSetDto> =
    Caffeine.newBuilder()
        .refreshAfterWrite(30, TimeUnit.MINUTES)
        .buildAsync { key, executor ->
            CoroutineScope(executor.asCoroutineDispatcher()).future {
                getSet(key).also { logger.info("Storing set $key") }
            }
        }

suspend fun getSet(id: String): EmoteSetDto =
    client.getOrNull<IvrEmoteSetDto>("$IVR_EMOTE_SET_URL$id")?.run {
        EmoteSetDto(
            setId = id,
            channel = channel?.takeIf { it != "qa_TW_Partner" } ?: "Twitch",
            channelId = channelId ?: "0",
            tier = tier?.toIntOrNull() ?: 1,
            emotes = emotes.map { EmoteDto(it.token, it.id) }
        )
    } ?: client.getOrNull<List<EmoteSetDto>>("$TWITCH_EMOTES_EMOTE_SET_URL$id")?.firstOrNull() ?: EmoteSetDto(id)