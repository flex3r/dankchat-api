import com.github.benmanes.caffeine.cache.AsyncCacheLoader
import com.github.benmanes.caffeine.cache.AsyncLoadingCache
import com.github.benmanes.caffeine.cache.Caffeine
import dto.EmoteDto
import dto.EmoteSetDto
import dto.IvrBulkEmoteSetDto
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.future.future
import utils.getOrNull
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

fun Route.getEmoteSetRoute(scope: CoroutineScope) {
    val asyncCacheLoader = object : AsyncCacheLoader<String, EmoteSetDto> {
        override fun asyncLoad(key: String, executor: Executor): CompletableFuture<out EmoteSetDto?> {
            return scope.future {
                getAllSets(listOf(key)).values.firstOrNull()
            }
        }

        override fun asyncLoadAll(
            keys: MutableSet<out String>,
            executor: Executor
        ): CompletableFuture<out MutableMap<out String, out EmoteSetDto>> {
            return scope.future {
                getAllSets(keys).also { logger.info("Storing sets ${it.keys}") }
            }
        }
    }

    val bulkEmoteSetCache: AsyncLoadingCache<String, EmoteSetDto?> =
        Caffeine.newBuilder()
            .refreshAfterWrite(30.minutes.toJavaDuration())
            .expireAfterAccess(1.days.toJavaDuration())
            .buildAsync(asyncCacheLoader)

    get("/sets") {
        val ids = call.request.queryParameters
            .getAll("id")
            ?.flatMap { it.split(",") }
            ?.filter { it.isNotBlank() }

        logger.info("GET /sets $ids")
        when {
            ids.isNullOrEmpty() -> {
                logger.warn("Caller: ${call.request.userAgent()} ID query parameter empty")
                call.respond(HttpStatusCode.BadRequest, "Invalid parameters")
            }

            else -> {
                val sets = bulkEmoteSetCache.getAll(ids).await()
                if (sets.isEmpty()) {
                    logger.warn("Cache returned null for sets $ids")
                    call.respond(HttpStatusCode.NotFound)
                } else {
                    logger.info("Serving sets ${sets.keys}")
                    call.respond(sets.values.toList())
                }
            }
        }
    }
}

const val IVR_V2_EMOTE_SET_URL = "https://api.ivr.fi/v2/twitch/emotes/sets"
suspend fun getAllSets(ids: Collection<String>): MutableMap<String, EmoteSetDto> = coroutineScope {
    ids.chunked(50)
        .map { getBulkEmoteSetsAsync(it) }
        .awaitAll()
        .flatten()
        .associate { emoteSetDto ->
            emoteSetDto.id to EmoteSetDto(
                setId = emoteSetDto.id,
                channel = emoteSetDto.channel?.takeIf { it != "qa_TW_Partner" } ?: "Twitch",
                channelId = emoteSetDto.channelId ?: "0",
                tier = emoteSetDto.tier?.toIntOrNull() ?: 1,
                emotes = emoteSetDto.emotes.map { EmoteDto(it.code, it.id, it.type, it.assetType) }
            )
        }.toMutableMap()
}

fun CoroutineScope.getBulkEmoteSetsAsync(ids: List<String>) = async {
    client.getOrNull<List<IvrBulkEmoteSetDto>>(IVR_V2_EMOTE_SET_URL) {
        parameter("set_id", ids.joinToString(separator = ","))
    }.orEmpty()
}