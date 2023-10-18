package utils

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.util.logging.*
import kotlinx.coroutines.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory

val logger: Logger = LoggerFactory.getLogger("dankchat-api")

suspend inline fun <reified T> HttpClient.getOrNull(url: String, block: HttpRequestBuilder.() -> Unit = {}): T? {
    return try {
        get(url) { block() }.body<T>()
    } catch (t: Throwable) {
        logger.error("Request $url failed $t", t)
        null
    }
}

fun CoroutineScope.timer(interval: Long, action: suspend TimerScope.() -> Unit): Job {
    return launch {
        val scope = TimerScope()

        while (true) {
            try {
                action(scope)
            } catch (t: Throwable) {
                logger.error(t)
            }

            if (scope.isCanceled) {
                break
            }

            delay(interval)
            yield()
        }
    }
}

class TimerScope {
    var isCanceled: Boolean = false
        private set

    fun cancel() {
        isCanceled = true
    }
}