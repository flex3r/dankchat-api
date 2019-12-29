import com.github.benmanes.caffeine.cache.Caffeine
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.client.HttpClient
import io.ktor.client.features.UserAgent
import io.ktor.client.request.get
import io.ktor.features.CallLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.request.userAgent
import io.ktor.response.respond
import io.ktor.response.respondText
import io.ktor.routing.get
import io.ktor.routing.routing
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.future.asCompletableFuture
import kotlinx.coroutines.future.await
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.util.concurrent.TimeUnit

val logger: Logger = LoggerFactory.getLogger("dankchat-api")
val cache = Caffeine.newBuilder()
    .refreshAfterWrite(30, TimeUnit.MINUTES)
    .buildAsync<String, String> { key, executor ->
        val innerContext = Job() + executor.asCoroutineDispatcher()
        CoroutineScope(innerContext).async {
            val set = getSet(key)
            logger.info("Storing $key")
            set
        }.asCompletableFuture()
    }

val client = HttpClient {
    install(UserAgent) {
        agent = "dankchat-api/1.0"
    }
}

suspend fun getSet(id: String): String? {
    return try {
        client.get("https://api.twitchemotes.com/api/v4/sets?id=$id")
    } catch (t: Throwable) {
        logger.error("Twitchemotes request failed", t)
        null
    }
}

fun main() {
    val server = embeddedServer(Netty, port = 8080) {
        install(CallLogging) {
            level = Level.INFO
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
                        call.respondText(set, ContentType.Application.Json)
                        logger.info("Serving set $id")
                    } else {
                        logger.error("Cache returned null on set $id")
                        call.respond(HttpStatusCode.InternalServerError)
                    }
                }
            }
        }
    }

    server.start(wait = true)
}