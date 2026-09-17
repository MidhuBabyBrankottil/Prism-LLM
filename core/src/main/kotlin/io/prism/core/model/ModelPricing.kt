package io.prism.core.model

import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class ModelPricing(
    val model: String,
    val provider: String,
    val promptCostPerMillion: Double,
    val completionCostPerMillion: Double,
    val cachedPromptCostPerMillion: Double = promptCostPerMillion * 0.5,
    val description: String = ""
) {
    fun calculateCost(promptTokens: Int, completionTokens: Int, cachedTokens: Int = 0): Double {
        val nonCachedPrompt = (promptTokens - cachedTokens).coerceAtLeast(0)
        val promptCost = (nonCachedPrompt.toDouble() / 1_000_000.0) * promptCostPerMillion
        val cachedCost = (cachedTokens.toDouble() / 1_000_000.0) * cachedPromptCostPerMillion
        val completionCost = (completionTokens.toDouble() / 1_000_000.0) * completionCostPerMillion

        val total = promptCost + cachedCost + completionCost
        return BigDecimal(total).setScale(6, RoundingMode.HALF_UP).toDouble()
    }
}

object PricingRegistry {
    private val pricingTable = ConcurrentHashMap<String, ModelPricing>()

    init {
        // Default OpenAI Models (USD per 1M tokens)
        register(ModelPricing("gpt-4o", "OpenAI", 2.50, 10.00, 1.25, "GPT-4o Omnimodal flagship"))
        register(ModelPricing("gpt-4o-mini", "OpenAI", 0.15, 0.60, 0.075, "Affordable lightweight GPT-4o"))
        register(ModelPricing("gpt-4-turbo", "OpenAI", 10.00, 30.00, 5.00, "High-intelligence GPT-4 Turbo"))
        register(ModelPricing("o1-preview", "OpenAI", 15.00, 60.00, 7.50, "Reasoning model with CoT"))
        register(ModelPricing("o1-mini", "OpenAI", 3.00, 12.00, 1.50, "Faster reasoning model"))

        // Default Google Gemini Models
        register(ModelPricing("gemini-2.0-flash", "Google", 0.10, 0.40, 0.025, "Next-gen multimodal workhorse"))
        register(ModelPricing("gemini-2.0-flash-lite", "Google", 0.075, 0.30, 0.018, "Ultra-efficient Gemini"))
        register(ModelPricing("gemini-1.5-pro", "Google", 1.25, 5.00, 0.3125, "Long-context 2M window reasoning"))
        register(ModelPricing("gemini-1.5-flash", "Google", 0.075, 0.30, 0.01875, "Fast and lightweight Gemini"))

        // Default Anthropic Models
        register(ModelPricing("claude-3-5-sonnet", "Anthropic", 3.00, 15.00, 0.30, "State of the art coding & analysis"))
        register(ModelPricing("claude-3-5-haiku", "Anthropic", 0.80, 4.00, 0.08, "High speed daily tasks"))

        // Default Local / Self-Hosted
        register(ModelPricing("llama-3.3-70b", "Meta/Local", 0.0, 0.0, 0.0, "Self-hosted LLaMA 3.3"))
        register(ModelPricing("deepseek-r1", "DeepSeek", 0.55, 2.19, 0.14, "Open-weights reasoning model"))
    }

    fun register(pricing: ModelPricing) {
        pricingTable[pricing.model.lowercase()] = pricing
    }

    fun findPricing(model: String): ModelPricing {
        val normalized = model.lowercase().trim()
        return pricingTable[normalized]
            ?: pricingTable.entries.firstOrNull { normalized.contains(it.key) }?.value
            ?: ModelPricing(model, "Unknown", 1.0, 3.0, 0.5, "Default fallback pricing")
    }

    fun all(): List<ModelPricing> = pricingTable.values.toList().sortedBy { it.model }
}
