package io.prism.server.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import io.prism.analytics.FinOpsAnalyticsEngine
import io.prism.core.model.ChatMessage
import io.prism.core.model.ChatCompletionRequest
import io.prism.core.model.LlmCallTrace
import io.prism.core.model.PricingRegistry
import io.prism.proxy.LlmProxyService
import io.prism.storage.TraceRepository
import kotlinx.coroutines.flow.collectLatest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import kotlin.random.Random

@Serializable
data class SimulationRequest(
    val model: String = "gpt-4o",
    val featureTag: String = "copilot",
    val prompt: String = "Summarize quarterly architecture review."
)

@Serializable
data class SeedResponse(
    val seeded: Int,
    val status: String = "success"
)

fun Route.analyticsRoutes(
    repository: TraceRepository,
    engine: FinOpsAnalyticsEngine,
    proxyService: LlmProxyService
) {
    val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    route("/api/analytics") {
        get("/kpis") {
            val traces = repository.findAll(5000)
            call.respond(HttpStatusCode.OK, engine.computeSummary(traces))
        }

        get("/cost-by-model") {
            val traces = repository.findAll(5000)
            call.respond(HttpStatusCode.OK, engine.computeCostByModel(traces))
        }

        get("/cost-by-feature") {
            val traces = repository.findAll(5000)
            call.respond(HttpStatusCode.OK, engine.computeCostByFeature(traces))
        }

        get("/cost-by-user") {
            val traces = repository.findAll(5000)
            call.respond(HttpStatusCode.OK, engine.computeCostByUser(traces))
        }

        get("/latency-distribution") {
            val traces = repository.findAll(5000)
            call.respond(HttpStatusCode.OK, engine.computeLatencyDistribution(traces))
        }

        get("/time-series") {
            val traces = repository.findAll(5000)
            call.respond(HttpStatusCode.OK, engine.computeTimeSeries(traces))
        }

        get("/anomalies") {
            val traces = repository.findAll(5000)
            call.respond(HttpStatusCode.OK, engine.detectAnomalies(traces))
        }

        get("/cache-opportunity") {
            val traces = repository.findAll(5000)
            call.respond(HttpStatusCode.OK, engine.calculateCacheOpportunity(traces))
        }

        get("/traces") {
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
            val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0
            call.respond(HttpStatusCode.OK, repository.findAll(limit, offset))
        }

        get("/models") {
            call.respond(HttpStatusCode.OK, PricingRegistry.all())
        }

        webSocket("/live") {
            proxyService.liveTraceFlow.collectLatest { trace ->
                val text = json.encodeToString(trace)
                send(Frame.Text(text))
            }
        }
    }

    route("/api/simulator") {
        post("/send") {
            val req = try { call.receive<SimulationRequest>() } catch (_: Exception) { SimulationRequest() }
            val completionReq = ChatCompletionRequest(
                model = req.model,
                messages = listOf(
                    ChatMessage(role = "system", content = "You are a cloud financial architecture expert."),
                    ChatMessage(role = "user", content = req.prompt)
                ),
                stream = false
            )
            val response = proxyService.handleChatCompletion(
                request = completionReq,
                featureTag = req.featureTag,
                userTag = "dev-console"
            )
            call.respond(HttpStatusCode.OK, response)
        }

        post("/seed") {
            val models = listOf("gpt-4o", "gpt-4o-mini", "gemini-2.0-flash", "gemini-1.5-pro", "claude-3-5-sonnet")
            val features = listOf("chat-assistant", "code-generator", "semantic-search", "rag-indexer", "doc-summary")
            val users = listOf("usr_alice", "usr_bob", "usr_carol", "usr_system_batch", "usr_dave")

            val now = System.currentTimeMillis()
            var count = 0
            for (i in 0 until 40) {
                val model = models.random()
                val feature = features.random()
                val user = users.random()
                val pricing = PricingRegistry.findPricing(model)

                val promptTokens = Random.nextInt(150, 2400)
                val completionTokens = Random.nextInt(50, 1200)
                val cachedTokens = if (promptTokens > 1000 && Random.nextBoolean()) promptTokens / 2 else 0
                val cost = pricing.calculateCost(promptTokens, completionTokens, cachedTokens)
                val duration = (Random.nextLong(120, 850) + if (model == "gpt-4o" || model == "claude-3-5-sonnet") 300 else 0)
                val isStreaming = Random.nextBoolean()
                val ttft = if (isStreaming) Random.nextLong(80, 250) else null

                // Add past timestamps spread across last 2 hours
                val timestamp = now - Random.nextLong(30_000, 7_200_000)

                val trace = LlmCallTrace(
                    id = UUID.randomUUID().toString(),
                    timestamp = timestamp,
                    provider = pricing.provider,
                    model = model,
                    promptTokens = promptTokens,
                    completionTokens = completionTokens,
                    cachedTokens = cachedTokens,
                    durationMs = duration,
                    timeToFirstTokenMs = ttft,
                    estimatedCostUsd = cost,
                    statusCode = 200,
                    clientIp = "127.0.0.1",
                    userTag = user,
                    featureTag = feature,
                    streaming = isStreaming,
                    promptPreview = "Sample prompt for $feature: How to build high throughput pipelines in Kotlin?",
                    completionPreview = "Optimized with Coroutines and Channel buffers for backpressure handling."
                )
                repository.save(trace)
                count++
            }

            // Also add 1 outlier for anomaly detection demo
            val anomalyTrace = LlmCallTrace(
                id = UUID.randomUUID().toString(),
                timestamp = now - 5000,
                provider = "OpenAI",
                model = "gpt-4o",
                promptTokens = 12000,
                completionTokens = 4000,
                durationMs = 6200,
                estimatedCostUsd = 0.07,
                statusCode = 200,
                clientIp = "192.168.1.50",
                userTag = "usr_batch_crawler",
                featureTag = "rag-indexer",
                streaming = false,
                promptPreview = "Large document dump 50 pages analyzed...",
                completionPreview = "Full extract analysis complete."
            )
            repository.save(anomalyTrace)
            count++

            call.respond(HttpStatusCode.OK, SeedResponse(seeded = count))
        }
    }
}
