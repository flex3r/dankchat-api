package utils

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import org.slf4j.Logger
import org.slf4j.LoggerFactory

val logger: Logger = LoggerFactory.getLogger("dankchat-api")

suspend inline fun <reified T> HttpClient.getOrNull(url: String): T? {
    return try {
        get<T>(url)
    } catch (t: Throwable) {
        logger.error(t.message)
        null
    }
}