import com.codahale.metrics.Slf4jReporter
import db.Database
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.logging.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.metrics.dropwizard.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.defaultheaders.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import utils.logger
import java.io.File
import java.util.concurrent.TimeUnit
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation

const val VERSION = "1.11"

val client = HttpClient(OkHttp) {
    install(HttpTimeout) {
        requestTimeoutMillis = 30_000
        connectTimeoutMillis = 15_000
    }
    install(UserAgent) { agent = "dankchat-api/$VERSION" }
    install(Logging) {
        level = LogLevel.INFO
    }
    install(ClientContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
}

@Suppress("unused")
fun Application.main() {
    val seToken = environment.config.propertyOrNull("ktor.deployment.seToken")?.getString()
    val dataDir = File(environment.config.property("ktor.deployment.dataDir").getString())
    val application = this

    install(CallLogging)
    install(ContentNegotiation) { json() }
    install(DefaultHeaders) { header(HttpHeaders.Server, "dankchat-api/$VERSION") }
    install(DropwizardMetrics) {
        Slf4jReporter.forRegistry(registry)
            .outputTo(application.log)
            .convertRatesTo(TimeUnit.MINUTES)
            .convertDurationsTo(TimeUnit.MILLISECONDS)
            .build()
            .start(1, TimeUnit.HOURS)
    }

    Database.init(dataDir)
    when (seToken) {
        null -> logger.warn("SE_TOKEN not set, donation polling disabled")
        else -> launch { pollDonations(seToken) }
    }
    launch { watchContributors(dataDir) }
    launch { watchTop(dataDir) }
    launch { watchDanks(dataDir) }
    launch { watchOptouts(dataDir) }

    routing {
        get(path = "/") { call.respondText("FeelsDankMan", ContentType.Text.Plain) }
        getEmoteSetRoute(application)
        getBadgesRoute()
    }
}

fun main(args: Array<String>): Unit = io.ktor.server.netty.EngineMain.main(args)
