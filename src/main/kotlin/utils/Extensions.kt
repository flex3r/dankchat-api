package utils

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import kotlinx.coroutines.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory

val logger: Logger = LoggerFactory.getLogger("dankchat-api")

suspend inline fun <reified T> HttpClient.getOrNull(url: String, block: HttpRequestBuilder.() -> Unit = {}): T? {
    return try {
        get(url) { block() }.body<T>()
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        logger.error("Request $url failed $t", t)
        null
    }
}

fun CoroutineScope.timer(interval: Long, action: suspend () -> Unit): Job {
    return launch {
        while (isActive) {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                logger.error("Timer action failed", t)
            }

            delay(interval)
        }
    }
}
