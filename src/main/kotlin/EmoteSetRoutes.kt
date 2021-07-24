import com.github.benmanes.caffeine.cache.AsyncCacheLoader
import com.github.benmanes.caffeine.cache.AsyncLoadingCache
import com.github.benmanes.caffeine.cache.Caffeine
import dto.EmoteDto
import dto.EmoteSetDto
import dto.IvrBulkEmoteSetDto
import dto.IvrEmoteSetDto
import io.ktor.application.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import kotlinx.coroutines.*
import kotlinx.coroutines.future.await
import kotlinx.coroutines.future.future
import utils.getOrNull
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
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
    get("/sets") {
        val ids = call.request.queryParameters["id"]?.split(",")
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

const val IVR_EMOTE_SET_URL = "https://api.ivr.fi/twitch/emoteset/"
const val IVR_V2_EMOTE_SET_URL = "https://api.ivr.fi/v2/twitch/emotes/sets"
const val TWITCH_EMOTES_EMOTE_SET_URL = "https://api.twitchemotes.com/api/v4/sets?id="

val asyncCacheLoader = object : AsyncCacheLoader<String, EmoteSetDto> {
    override fun asyncLoad(key: String, executor: Executor): CompletableFuture<out EmoteSetDto?> {
        return CoroutineScope(executor.asCoroutineDispatcher()).future {
            getAllSets(listOf(key)).values.firstOrNull()
        }
    }
    override fun asyncLoadAll(
        keys: MutableSet<out String>,
        executor: Executor
    ): CompletableFuture<out MutableMap<out String, out EmoteSetDto>> {
        return CoroutineScope(executor.asCoroutineDispatcher()).future {
            getAllSets(keys).also { logger.info("Storing sets ${it.keys}") }
        }
    }
}

val emoteSetCache: AsyncLoadingCache<String, EmoteSetDto> =
    Caffeine.newBuilder()
        .refreshAfterWrite(30, TimeUnit.MINUTES)
        .buildAsync { key, executor ->
            CoroutineScope(executor.asCoroutineDispatcher()).future {
                getSet(key).also { logger.info("Storing set $key") }
            }
        }

val bulkEmoteSetCache: AsyncLoadingCache<String, EmoteSetDto?> =
    Caffeine.newBuilder()
        .refreshAfterWrite(30, TimeUnit.MINUTES)
        .buildAsync(asyncCacheLoader)

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

suspend fun getSet(id: String): EmoteSetDto =
    client.getOrNull<IvrEmoteSetDto>("$IVR_EMOTE_SET_URL$id")?.run {
        EmoteSetDto(
            setId = id,
            channel = channel?.takeIf { it != "qa_TW_Partner" } ?: "Twitch",
            channelId = channelId ?: "0",
            tier = tier?.toIntOrNull() ?: 1,
            emotes = emotes.map { EmoteDto(it.token, it.id, type = null, assetType = null) }
        )
    } ?: client.getOrNull<List<EmoteSetDto>>("$TWITCH_EMOTES_EMOTE_SET_URL$id")?.firstOrNull() ?: EmoteSetDto(id)