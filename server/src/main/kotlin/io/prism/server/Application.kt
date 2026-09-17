package io.prism.server

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.prism.analytics.FinOpsAnalyticsEngine
import io.prism.core.model.ChatMessage
import io.prism.core.model.ChatCompletionRequest
import io.prism.proxy.LlmProxyService
import io.prism.server.routes.analyticsRoutes
import io.prism.server.routes.proxyRoutes
import io.prism.storage.SqliteTraceRepository
import io.prism.storage.TraceRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.seconds

private val logger = LoggerFactory.getLogger("PrismLLM")

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module(customRepository: TraceRepository? = null) {
    val repository: TraceRepository = customRepository ?: SqliteTraceRepository("prism_analytics.db")
    val analyticsEngine = FinOpsAnalyticsEngine()
    val proxyService = LlmProxyService(repository = repository, forceMock = true)

    // Pre-populate demo seed traces if database is empty
    if (repository.count() == 0L) {
        runBlocking {
            seedInitialDemoData(proxyService)
        }
    }

    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        })
    }

    install(CORS) {
        anyHost()
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        allowHeader("X-Feature-Tag")
        allowHeader("X-User-Id")
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Patch)
        allowMethod(HttpMethod.Delete)
    }

    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 30.seconds
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }

    install(CallLogging)

    install(StatusPages) {
        exception<Throwable> { call, cause ->
            logger.error("Unhandled server exception: ${cause.message}", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to (cause.message ?: "Internal Server Error"))
            )
        }
    }

    routing {
        get("/") {
            call.respondRedirect("/dashboard")
        }

        get("/dashboard") {
            val stream = this::class.java.classLoader.getResourceAsStream("static/index.html")
            if (stream != null) {
                val content = stream.bufferedReader().use { it.readText() }
                call.respondText(content, ContentType.Text.Html)
            } else {
                call.respondText("PrismLLM Dashboard loaded. Place static/index.html in resources.", ContentType.Text.Plain)
            }
        }

        staticResources("/static", "static")

        proxyRoutes(proxyService)
        analyticsRoutes(repository, analyticsEngine, proxyService)
    }

    logger.info("⚡ PrismLLM Observability & FinOps Proxy is active at http://localhost:8080/dashboard")
}

private suspend fun seedInitialDemoData(proxyService: LlmProxyService) {
    val demos = listOf(
        Triple("gpt-4o", "copilot-agent", "Write a reactive backpressure stream in Kotlin"),
        Triple("gemini-2.0-flash", "semantic-search", "Find top semantic embeddings for document index"),
        Triple("claude-3-5-sonnet", "code-review", "Audit SQL injection vulnerabilities in prepared statements"),
        Triple("gpt-4o-mini", "tag-generator", "Extract 5 tags from this tech article"),
        Triple("gemini-1.5-pro", "document-analysis", "Summarize multi-cloud FinOps cost optimization patterns")
    )

    demos.forEach { (model, feature, prompt) ->
        proxyService.handleChatCompletion(
            request = ChatCompletionRequest(
                model = model,
                messages = listOf(ChatMessage(role = "user", content = prompt))
            ),
            featureTag = feature,
            userTag = "seed-bot"
        )
    }
}
