package utils

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.util.error
import kotlinx.coroutines.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory

val logger: Logger = LoggerFactory.getLogger("dankchat-api")

suspend inline fun <reified T> HttpClient.getOrNull(url: String, block: HttpRequestBuilder.() -> Unit = {}): T? {
    return try {
        get<T>(url, block)
    } catch (t: Throwable) {
        logger.error(t.message)
        null
    }
}

fun CoroutineScope.timer(interval: Long, action: suspend TimerScope.() -> Unit): Job {
    return launch {
        val scope = TimerScope()

        while (true) {
            try {
                action(scope)
            } catch (ex: Exception) {
                logger.error(ex)
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