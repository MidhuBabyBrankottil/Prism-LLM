package io.prism.analytics

import io.prism.core.model.LlmCallTrace
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FinOpsAnalyticsEngineTest {

    private val engine = FinOpsAnalyticsEngine()

    @Test
    fun `computeSummary calculates correct aggregate metrics`() {
        val traces = listOf(
            LlmCallTrace(
                provider = "OpenAI",
                model = "gpt-4o",
                promptTokens = 1000,
                completionTokens = 500,
                durationMs = 1200,
                timeToFirstTokenMs = 300,
                estimatedCostUsd = 0.0075,
                featureTag = "search"
            ),
            LlmCallTrace(
                provider = "Google",
                model = "gemini-2.0-flash",
                promptTokens = 2000,
                completionTokens = 800,
                durationMs = 600,
                timeToFirstTokenMs = 150,
                estimatedCostUsd = 0.0005,
                featureTag = "recommendations"
            )
        )

        val summary = engine.computeSummary(traces)

        assertEquals(2, summary.totalRequests)
        assertEquals(3000, summary.totalPromptTokens)
        assertEquals(1300, summary.totalCompletionTokens)
        assertEquals(4300, summary.totalTokens)
        assertEquals(0.008, summary.totalSpendUsd)
        assertEquals(900.0, summary.averageDurationMs)
        assertEquals(225.0, summary.avgTimeToFirstTokenMs)
        assertEquals(0.0, summary.errorRatePercent)
    }

    @Test
    fun `computeLatencyDistribution calculates accurate percentiles`() {
        val latencies = (1..100).map { it * 10L } // 10ms, 20ms, ... 1000ms
        val traces = latencies.map { dur ->
            LlmCallTrace(
                provider = "OpenAI",
                model = "gpt-4o-mini",
                promptTokens = 100,
                completionTokens = 50,
                durationMs = dur,
                estimatedCostUsd = 0.0001
            )
        }

        val dist = engine.computeLatencyDistribution(traces)

        assertEquals(10.0, dist.minMs)
        assertEquals(1000.0, dist.maxMs)
        assertTrue(dist.p50Ms in 490.0..510.0)
        assertTrue(dist.p90Ms in 890.0..910.0)
        assertTrue(dist.p99Ms in 980.0..1000.0)
    }

    @Test
    fun `computeCostByModel ranks highest spending models first`() {
        val traces = listOf(
            LlmCallTrace(provider = "OpenAI", model = "gpt-4o", promptTokens = 1000, completionTokens = 500, durationMs = 1000, estimatedCostUsd = 0.05),
            LlmCallTrace(provider = "Google", model = "gemini-2.0-flash", promptTokens = 1000, completionTokens = 500, durationMs = 300, estimatedCostUsd = 0.001),
            LlmCallTrace(provider = "OpenAI", model = "gpt-4o", promptTokens = 2000, completionTokens = 1000, durationMs = 1500, estimatedCostUsd = 0.10)
        )

        val breakdown = engine.computeCostByModel(traces)

        assertEquals(2, breakdown.size)
        assertEquals("gpt-4o", breakdown[0].category)
        assertEquals(0.15, breakdown[0].spendUsd)
        assertEquals(2, breakdown[0].requestCount)

        assertEquals("gemini-2.0-flash", breakdown[1].category)
        assertEquals(0.001, breakdown[1].spendUsd)
    }

    @Test
    fun `detectAnomalies flags abnormal latency spikes`() {
        val normalTraces = (1..20).map {
            LlmCallTrace(
                provider = "Google",
                model = "gemini-2.0-flash",
                promptTokens = 200,
                completionTokens = 100,
                durationMs = 250L + (it % 10),
                estimatedCostUsd = 0.0001
            )
        }
        val spikeTrace = LlmCallTrace(
            provider = "Google",
            model = "gemini-2.0-flash",
            promptTokens = 200,
            completionTokens = 100,
            durationMs = 5500L, // Massive 5.5s spike
            estimatedCostUsd = 0.0001
        )

        val alerts = engine.detectAnomalies(normalTraces + spikeTrace)

        assertTrue(alerts.isNotEmpty())
        assertEquals("LATENCY_SPIKE", alerts.first().type)
        assertEquals(5500.0, alerts.first().actualValue)
    }
}
