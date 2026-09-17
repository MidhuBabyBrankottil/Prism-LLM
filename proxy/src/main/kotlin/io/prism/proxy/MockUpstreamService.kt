package io.prism.proxy

import io.prism.core.model.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.UUID
import kotlin.random.Random

class MockUpstreamService {

    private val sampleResponses = listOf(
        "Modern systems engineering requires resilient caching, non-blocking coroutine streaming, and fine-grained token observability.",
        "To optimize LLM FinOps, implement aggressive prompt template compression and tiered model routing based on query complexity.",
        "Kotlin Coroutines provide structured concurrency with minimal memory overhead compared to traditional OS threads.",
        "By analyzing p99 latency spikes and token distributions, engineering teams can spot runaway recursive agent loops before they blow budgets.",
        "PrismLLM successfully intercepted this payload, extracted metadata, calculated cost attribution, and updated the analytics ledger."
    )

    suspend fun generateMockResponse(request: ChatCompletionRequest): Pair<ChatCompletionResponse, Long> {
        val startTime = System.currentTimeMillis()
        // Simulate realistic network & inference delay (100 - 350ms)
        val latency = Random.nextLong(100, 350)
        delay(latency)

        val promptText = request.messages.joinToString(" ") { it.content }
        val promptTokens = (promptText.length / 4).coerceAtLeast(15)

        val completionText = sampleResponses.random()
        val completionTokens = (completionText.length / 4).coerceAtLeast(20)

        val response = ChatCompletionResponse(
            id = "mockcmpl-${UUID.randomUUID().toString().take(12)}",
            model = request.model,
            choices = listOf(
                ChatChoice(
                    index = 0,
                    message = ChatMessage(role = "assistant", content = completionText),
                    finish_reason = "stop"
                )
            ),
            usage = Usage(
                prompt_tokens = promptTokens,
                completion_tokens = completionTokens,
                total_tokens = promptTokens + completionTokens,
                prompt_tokens_details = PromptTokensDetails(cached_tokens = if (promptTokens > 100) 40 else 0)
            )
        )

        val totalDuration = System.currentTimeMillis() - startTime
        return Pair(response, totalDuration)
    }

    fun generateMockStream(request: ChatCompletionRequest): Flow<Pair<ChatCompletionChunk, Long?>> = flow {
        val startTime = System.currentTimeMillis()
        // Simulate Time to First Token (TTFT)
        val ttftDelay = Random.nextLong(80, 220)
        delay(ttftDelay)
        val ttft = System.currentTimeMillis() - startTime

        val streamId = "mockcmpl-${UUID.randomUUID().toString().take(12)}"
        val completionText = sampleResponses.random()
        val words = completionText.split(" ")

        // First chunk with role
        emit(
            Pair(
                ChatCompletionChunk(
                    id = streamId,
                    model = request.model,
                    choices = listOf(StreamChoice(index = 0, delta = DeltaMessage(role = "assistant")))
                ),
                ttft
            )
        )

        // Stream word tokens with inter-token latency (20 - 50ms)
        for (word in words) {
            delay(Random.nextLong(20, 50))
            emit(
                Pair(
                    ChatCompletionChunk(
                        id = streamId,
                        model = request.model,
                        choices = listOf(StreamChoice(index = 0, delta = DeltaMessage(content = "$word ")))
                    ),
                    null
                )
            )
        }

        // Final chunk with finish_reason
        val promptTokens = (request.messages.joinToString(" ") { it.content }.length / 4).coerceAtLeast(10)
        val completionTokens = words.size * 2
        emit(
            Pair(
                ChatCompletionChunk(
                    id = streamId,
                    model = request.model,
                    choices = listOf(StreamChoice(index = 0, delta = DeltaMessage(), finish_reason = "stop")),
                    usage = Usage(
                        prompt_tokens = promptTokens,
                        completion_tokens = completionTokens,
                        total_tokens = promptTokens + completionTokens
                    )
                ),
                null
            )
        )
    }
}
