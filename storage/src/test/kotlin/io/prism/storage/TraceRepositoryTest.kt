package io.prism.storage

import io.prism.core.model.LlmCallTrace
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class TraceRepositoryTest {

    private fun createSampleTrace(id: String, model: String, feature: String) = LlmCallTrace(
        id = id,
        timestamp = System.currentTimeMillis(),
        provider = "OpenAI",
        model = model,
        promptTokens = 100,
        completionTokens = 50,
        durationMs = 250,
        estimatedCostUsd = 0.001,
        featureTag = feature
    )

    @Test
    fun `InMemoryTraceRepository enforces capacity and FIFO eviction`() {
        val repo = InMemoryTraceRepository(maxCapacity = 3)
        repo.save(createSampleTrace("t1", "gpt-4o", "chat"))
        repo.save(createSampleTrace("t2", "gpt-4o", "search"))
        repo.save(createSampleTrace("t3", "gpt-4o", "chat"))
        repo.save(createSampleTrace("t4", "gpt-4o", "billing"))

        assertEquals(3, repo.count())
        val all = repo.findAll()
        assertEquals("t4", all[0].id)
        assertEquals("t3", all[1].id)
        assertEquals("t2", all[2].id)
    }

    @Test
    fun `SqliteTraceRepository persists and queries records in memory`() {
        SqliteTraceRepository(":memory:").use { repo ->
            repo.save(createSampleTrace("s1", "gemini-2.0-flash", "code-gen"))
            repo.save(createSampleTrace("s2", "claude-3-5-sonnet", "doc-summary"))

            assertEquals(2, repo.count())
            val filtered = repo.findByFeature("code-gen")
            assertEquals(1, filtered.size)
            assertEquals("s1", filtered.first().id)
            assertEquals("gemini-2.0-flash", filtered.first().model)
        }
    }
}
