package io.prism.core.model

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class LlmCallTrace(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val provider: String,
    val model: String,
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int = promptTokens + completionTokens,
    val cachedTokens: Int = 0,
    val durationMs: Long,
    val timeToFirstTokenMs: Long? = null,
    val estimatedCostUsd: Double,
    val statusCode: Int = 200,
    val clientIp: String? = null,
    val userTag: String? = null,
    val featureTag: String? = null,
    val streaming: Boolean = false,
    val promptPreview: String? = null,
    val completionPreview: String? = null,
    val error: String? = null
) {
    val tokensPerSecond: Double
        get() = if (durationMs > 0 && completionTokens > 0) {
            (completionTokens.toDouble() / durationMs.toDouble()) * 1000.0
        } else {
            0.0
        }
}

@Serializable
data class ChatMessage(
    val role: String,
    val content: String,
    val name: String? = null
)

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage> = emptyList(),
    val stream: Boolean = false,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val user: String? = null
)

@Serializable
data class Usage(
    val prompt_tokens: Int,
    val completion_tokens: Int,
    val total_tokens: Int,
    val prompt_tokens_details: PromptTokensDetails? = null
)

@Serializable
data class PromptTokensDetails(
    val cached_tokens: Int = 0
)

@Serializable
data class ChatChoice(
    val index: Int = 0,
    val message: ChatMessage,
    val finish_reason: String? = "stop"
)

@Serializable
data class ChatCompletionResponse(
    val id: String = "chatcmpl-" + UUID.randomUUID().toString().take(12),
    val `object`: String = "chat.completion",
    val created: Long = System.currentTimeMillis() / 1000,
    val model: String,
    val choices: List<ChatChoice>,
    val usage: Usage? = null
)

@Serializable
data class DeltaMessage(
    val role: String? = null,
    val content: String? = null
)

@Serializable
data class StreamChoice(
    val index: Int = 0,
    val delta: DeltaMessage,
    val finish_reason: String? = null
)

@Serializable
data class ChatCompletionChunk(
    val id: String = "chatcmpl-" + UUID.randomUUID().toString().take(12),
    val `object`: String = "chat.completion.chunk",
    val created: Long = System.currentTimeMillis() / 1000,
    val model: String,
    val choices: List<StreamChoice>,
    val usage: Usage? = null
)
