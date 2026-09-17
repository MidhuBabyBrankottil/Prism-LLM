package io.prism.analytics

import io.prism.core.model.LlmCallTrace
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.pow
import kotlin.math.sqrt

@Serializable
data class CategorySpend(
    val category: String,
    val spendUsd: Double,
    val requestCount: Long,
    val totalTokens: Long,
    val percentageOfTotal: Double = 0.0
)

@Serializable
data class LatencyDistribution(
    val minMs: Double,
    val p50Ms: Double,
    val p90Ms: Double,
    val p95Ms: Double,
    val p99Ms: Double,
    val maxMs: Double
)

@Serializable
data class FinOpsSummary(
    val totalRequests: Long,
    val totalSpendUsd: Double,
    val totalPromptTokens: Long,
    val totalCompletionTokens: Long,
    val totalTokens: Long,
    val cachedTokens: Long,
    val cacheSavingsUsd: Double,
    val averageDurationMs: Double,
    val avgTimeToFirstTokenMs: Double?,
    val errorCount: Long,
    val errorRatePercent: Double,
    val tokensPerSecondAvg: Double
)

@Serializable
data class TimeSeriesPoint(
    val bucketTimestamp: Long,
    val requestCount: Long,
    val spendUsd: Double,
    val totalTokens: Long,
    val avgDurationMs: Double
)

@Serializable
data class AnomalyAlert(
    val traceId: String,
    val type: String, // "LATENCY_SPIKE" or "TOKEN_SPIKE"
    val actualValue: Double,
    val thresholdValue: Double,
    val message: String
)

@Serializable
data class CacheSavingsOpportunity(
    val potentialSavingsUsd: Double,
    val cacheableRequestsCount: Long,
    val estimatedCacheHitRate: Double,
    val recommendation: String
)

class FinOpsAnalyticsEngine {

    fun computeSummary(traces: List<LlmCallTrace>): FinOpsSummary {
        if (traces.isEmpty()) {
            return FinOpsSummary(
                totalRequests = 0,
                totalSpendUsd = 0.0,
                totalPromptTokens = 0,
                totalCompletionTokens = 0,
                totalTokens = 0,
                cachedTokens = 0,
                cacheSavingsUsd = 0.0,
                averageDurationMs = 0.0,
                avgTimeToFirstTokenMs = null,
                errorCount = 0,
                errorRatePercent = 0.0,
                tokensPerSecondAvg = 0.0
            )
        }

        val totalRequests = traces.size.toLong()
        val totalSpendUsd = round(traces.sumOf { it.estimatedCostUsd })
        val totalPromptTokens = traces.sumOf { it.promptTokens.toLong() }
        val totalCompletionTokens = traces.sumOf { it.completionTokens.toLong() }
        val totalTokens = traces.sumOf { it.totalTokens.toLong() }
        val cachedTokens = traces.sumOf { it.cachedTokens.toLong() }

        // Theoretical cache savings estimate
        val cacheSavingsUsd = round(traces.sumOf { trace ->
            if (trace.cachedTokens > 0) {
                (trace.cachedTokens.toDouble() / 1_000_000.0) * 1.5 // estimated baseline differential
            } else 0.0
        })

        val averageDurationMs = round(traces.map { it.durationMs }.average())
        val ttftTraces = traces.mapNotNull { it.timeToFirstTokenMs }
        val avgTtftMs = if (ttftTraces.isNotEmpty()) round(ttftTraces.average()) else null

        val errorCount = traces.count { it.statusCode >= 400 || it.error != null }.toLong()
        val errorRatePercent = round((errorCount.toDouble() / totalRequests.toDouble()) * 100.0)

        val validTpsTraces = traces.filter { it.tokensPerSecond > 0 }
        val avgTps = if (validTpsTraces.isNotEmpty()) round(validTpsTraces.map { it.tokensPerSecond }.average()) else 0.0

        return FinOpsSummary(
            totalRequests = totalRequests,
            totalSpendUsd = totalSpendUsd,
            totalPromptTokens = totalPromptTokens,
            totalCompletionTokens = totalCompletionTokens,
            totalTokens = totalTokens,
            cachedTokens = cachedTokens,
            cacheSavingsUsd = cacheSavingsUsd,
            averageDurationMs = averageDurationMs,
            avgTimeToFirstTokenMs = avgTtftMs,
            errorCount = errorCount,
            errorRatePercent = errorRatePercent,
            tokensPerSecondAvg = avgTps
        )
    }

    fun computeLatencyDistribution(traces: List<LlmCallTrace>): LatencyDistribution {
        if (traces.isEmpty()) {
            return LatencyDistribution(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        }

        val sorted = traces.map { it.durationMs.toDouble() }.sorted()
        return LatencyDistribution(
            minMs = sorted.first(),
            p50Ms = calculatePercentile(sorted, 50.0),
            p90Ms = calculatePercentile(sorted, 90.0),
            p95Ms = calculatePercentile(sorted, 95.0),
            p99Ms = calculatePercentile(sorted, 99.0),
            maxMs = sorted.last()
        )
    }

    fun computeCostByModel(traces: List<LlmCallTrace>): List<CategorySpend> {
        return computeCategoryBreakdown(traces, { it.model }, "unknown-model")
    }

    fun computeCostByFeature(traces: List<LlmCallTrace>): List<CategorySpend> {
        return computeCategoryBreakdown(traces, { it.featureTag ?: "default-feature" }, "unassigned")
    }

    fun computeCostByUser(traces: List<LlmCallTrace>): List<CategorySpend> {
        return computeCategoryBreakdown(traces, { it.userTag ?: "anonymous" }, "anonymous")
    }

    fun computeCostByProvider(traces: List<LlmCallTrace>): List<CategorySpend> {
        return computeCategoryBreakdown(traces, { it.provider }, "unknown-provider")
    }

    fun computeTimeSeries(traces: List<LlmCallTrace>, bucketIntervalMs: Long = 60_000L): List<TimeSeriesPoint> {
        if (traces.isEmpty()) return emptyList()

        return traces.groupBy { it.timestamp / bucketIntervalMs }
            .map { (bucketIndex, bucketTraces) ->
                TimeSeriesPoint(
                    bucketTimestamp = bucketIndex * bucketIntervalMs,
                    requestCount = bucketTraces.size.toLong(),
                    spendUsd = round(bucketTraces.sumOf { it.estimatedCostUsd }),
                    totalTokens = bucketTraces.sumOf { it.totalTokens.toLong() },
                    avgDurationMs = round(bucketTraces.map { it.durationMs }.average())
                )
            }
            .sortedBy { it.bucketTimestamp }
    }

    fun detectAnomalies(traces: List<LlmCallTrace>): List<AnomalyAlert> {
        if (traces.size < 5) return emptyList()

        val durations = traces.map { it.durationMs.toDouble() }
        val durationMean = durations.average()
        val durationStdDev = sqrt(durations.map { (it - durationMean).pow(2) }.average())
        val latencyThreshold = durationMean + (2.5 * durationStdDev)

        val tokens = traces.map { it.totalTokens.toDouble() }
        val tokenMean = tokens.average()
        val tokenStdDev = sqrt(tokens.map { (it - tokenMean).pow(2) }.average())
        val tokenThreshold = tokenMean + (3.0 * tokenStdDev)

        val alerts = mutableListOf<AnomalyAlert>()

        traces.takeLast(20).forEach { trace ->
            if (durationStdDev > 50 && trace.durationMs > latencyThreshold) {
                alerts.add(
                    AnomalyAlert(
                        traceId = trace.id,
                        type = "LATENCY_SPIKE",
                        actualValue = trace.durationMs.toDouble(),
                        thresholdValue = round(latencyThreshold),
                        message = "Latency ${trace.durationMs}ms exceeds baseline threshold (${round(latencyThreshold)}ms)"
                    )
                )
            }
            if (tokenStdDev > 100 && trace.totalTokens > tokenThreshold) {
                alerts.add(
                    AnomalyAlert(
                        traceId = trace.id,
                        type = "TOKEN_SPIKE",
                        actualValue = trace.totalTokens.toDouble(),
                        thresholdValue = round(tokenThreshold),
                        message = "Tokens ${trace.totalTokens} exceeds abnormal consumption threshold (${round(tokenThreshold)})"
                    )
                )
            }
        }

        return alerts
    }

    fun calculateCacheOpportunity(traces: List<LlmCallTrace>): CacheSavingsOpportunity {
        val largePromptTraces = traces.filter { it.promptTokens >= 800 }
        val eligibleTokens = largePromptTraces.sumOf { (it.promptTokens - 400).coerceAtLeast(0) }
        // 50% discount on cacheable prefix
        val potentialSavings = (eligibleTokens.toDouble() / 1_000_000.0) * 1.25

        val estimatedRate = if (traces.isNotEmpty()) {
            (largePromptTraces.size.toDouble() / traces.size.toDouble()) * 0.75
        } else 0.0

        return CacheSavingsOpportunity(
            potentialSavingsUsd = round(potentialSavings),
            cacheableRequestsCount = largePromptTraces.size.toLong(),
            estimatedCacheHitRate = round(estimatedRate * 100.0),
            recommendation = if (potentialSavings > 0.01) {
                "Enabling prompt caching for features with repeated prompts could save ~\$${round(potentialSavings)}/mo."
            } else {
                "Current prompt lengths have low cache leverage."
            }
        )
    }

    private fun computeCategoryBreakdown(
        traces: List<LlmCallTrace>,
        categorySelector: (LlmCallTrace) -> String,
        defaultCategory: String
    ): List<CategorySpend> {
        if (traces.isEmpty()) return emptyList()

        val totalSpend = traces.sumOf { it.estimatedCostUsd }
        return traces.groupBy { categorySelector(it).ifBlank { defaultCategory } }
            .map { (cat, group) ->
                val spend = round(group.sumOf { it.estimatedCostUsd })
                val tokens = group.sumOf { it.totalTokens.toLong() }
                val percentage = if (totalSpend > 0) round((spend / totalSpend) * 100.0) else 0.0
                CategorySpend(
                    category = cat,
                    spendUsd = spend,
                    requestCount = group.size.toLong(),
                    totalTokens = tokens,
                    percentageOfTotal = percentage
                )
            }
            .sortedByDescending { it.spendUsd }
    }

    private fun calculatePercentile(sortedValues: List<Double>, percentile: Double): Double {
        if (sortedValues.isEmpty()) return 0.0
        val index = ((percentile / 100.0) * (sortedValues.size - 1)).toInt()
        return round(sortedValues[index.coerceIn(0, sortedValues.size - 1)])
    }

    private fun round(value: Double): Double {
        return if (value.isNaN() || value.isInfinite()) 0.0
        else BigDecimal(value).setScale(4, RoundingMode.HALF_UP).toDouble()
    }
}
