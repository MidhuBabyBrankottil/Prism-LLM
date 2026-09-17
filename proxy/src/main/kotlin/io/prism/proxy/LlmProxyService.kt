package io.prism.proxy

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.prism.core.model.*
import io.prism.storage.TraceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

class LlmProxyService(
    private val repository: TraceRepository,
    private val mockService: MockUpstreamService = MockUpstreamService(),
    private val forceMock: Boolean = true,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(json)
        }
    }

    private val _liveTraceFlow = MutableSharedFlow<LlmCallTrace>(extraBufferCapacity = 100)
    val liveTraceFlow: SharedFlow<LlmCallTrace> = _liveTraceFlow.asSharedFlow()

    suspend fun handleChatCompletion(
        request: ChatCompletionRequest,
        authHeader: String? = null,
        featureTag: String? = null,
        userTag: String? = null,
        clientIp: String? = null
    ): ChatCompletionResponse {
        val startTime = System.currentTimeMillis()
        val pricing = PricingRegistry.findPricing(request.model)

        val (response, duration) = if (!forceMock && authHeader != null) {
            forwardUpstream(request, authHeader, startTime)
        } else {
            mockService.generateMockResponse(request)
        }

        val usage = response.usage ?: Usage(20, 30, 50)
        val promptTokens = usage.prompt_tokens
        val completionTokens = usage.completion_tokens
        val cachedTokens = usage.prompt_tokens_details?.cached_tokens ?: 0
        val cost = pricing.calculateCost(promptTokens, completionTokens, cachedTokens)

        val promptPreview = request.messages.lastOrNull()?.content?.take(150)
        val completionPreview = response.choices.firstOrNull()?.message?.content?.take(150)

        val trace = LlmCallTrace(
            provider = pricing.provider,
            model = request.model,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            cachedTokens = cachedTokens,
            durationMs = duration,
            estimatedCostUsd = cost,
            statusCode = 200,
            clientIp = clientIp,
            userTag = userTag ?: request.user,
            featureTag = featureTag,
            streaming = false,
            promptPreview = promptPreview,
            completionPreview = completionPreview
        )

        recordTrace(trace)
        return response
    }

    fun handleStreamingChatCompletion(
        request: ChatCompletionRequest,
        authHeader: String? = null,
        featureTag: String? = null,
        userTag: String? = null,
        clientIp: String? = null
    ): Flow<ChatCompletionChunk> = flow {
        val startTime = System.currentTimeMillis()
        val pricing = PricingRegistry.findPricing(request.model)
        var capturedTtft: Long? = null
        val fullContent = StringBuilder()
        var finalUsage: Usage? = null

        mockService.generateMockStream(request).collect { (chunk, ttft) ->
            if (ttft != null && capturedTtft == null) {
                capturedTtft = ttft
            }
            chunk.choices.firstOrNull()?.delta?.content?.let { fullContent.append(it) }
            if (chunk.usage != null) {
                finalUsage = chunk.usage
            }
            emit(chunk)
        }

        val totalDuration = System.currentTimeMillis() - startTime
        val promptTokens = finalUsage?.prompt_tokens ?: (request.messages.joinToString(" ") { it.content }.length / 4).coerceAtLeast(10)
        val completionTokens = finalUsage?.completion_tokens ?: (fullContent.length / 4).coerceAtLeast(15)
        val cachedTokens = finalUsage?.prompt_tokens_details?.cached_tokens ?: 0
        val cost = pricing.calculateCost(promptTokens, completionTokens, cachedTokens)

        val trace = LlmCallTrace(
            provider = pricing.provider,
            model = request.model,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            cachedTokens = cachedTokens,
            durationMs = totalDuration,
            timeToFirstTokenMs = capturedTtft,
            estimatedCostUsd = cost,
            statusCode = 200,
            clientIp = clientIp,
            userTag = userTag ?: request.user,
            featureTag = featureTag,
            streaming = true,
            promptPreview = request.messages.lastOrNull()?.content?.take(150),
            completionPreview = fullContent.toString().take(150)
        )

        recordTrace(trace)
    }

    private fun recordTrace(trace: LlmCallTrace) {
        coroutineScope.launch {
            repository.save(trace)
            _liveTraceFlow.tryEmit(trace)
        }
    }

    private suspend fun forwardUpstream(
        request: ChatCompletionRequest,
        authHeader: String,
        startTime: Long
    ): Pair<ChatCompletionResponse, Long> {
        val httpResponse = httpClient.post("https://api.openai.com/v1/chat/completions") {
            header(HttpHeaders.Authorization, authHeader)
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        val duration = System.currentTimeMillis() - startTime
        val bodyText = httpResponse.bodyAsText()
        val parsed = json.decodeFromString<ChatCompletionResponse>(bodyText)
        return Pair(parsed, duration)
    }
}
