import com.codahale.metrics.Slf4jReporter
import db.Database
import io.ktor.application.*
import io.ktor.client.*
import io.ktor.client.features.*
import io.ktor.client.features.json.JsonFeature
import io.ktor.client.features.json.serializer.*
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.metrics.dropwizard.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.serialization.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.util.concurrent.TimeUnit

val logger: Logger = LoggerFactory.getLogger("dankchat-api")
val client = HttpClient {
    install(UserAgent) { agent = "dankchat-api/1.5" }
    install(JsonFeature) {
        serializer = KotlinxSerializer(json = Json { ignoreUnknownKeys = true })
    }
}

fun Application.main() {
    val seToken: String = environment.config.property("ktor.deployment.seToken").getString()

    install(CallLogging) { level = Level.INFO }
    install(ContentNegotiation) { json() }
    install(DefaultHeaders) { header("User-Agent", "dankchat-api/1.5") }
    install(DropwizardMetrics) {
        Slf4jReporter.forRegistry(registry)
            .outputTo(log)
            .convertRatesTo(TimeUnit.MINUTES)
            .convertDurationsTo(TimeUnit.MILLISECONDS)
            .build()
            .start(1, TimeUnit.HOURS)
    }

    Database.init()
    launch { pollDonations(seToken) }
    launch { getContributors() }
    launch { getTop() }
    launch { getDanks() }

    routing {
        get(path = "/") { call.respondText("FeelsDankMan", ContentType.Text.Plain) }
        getEmoteSetRoute()
        getBadgesRoute()
    }
}

fun main(args: Array<String>): Unit =
    io.ktor.server.netty.EngineMain.main(args)
