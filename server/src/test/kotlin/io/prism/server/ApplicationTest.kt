package io.prism.server

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.prism.core.model.ChatMessage
import io.prism.core.model.ChatCompletionRequest
import io.prism.storage.InMemoryTraceRepository
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ApplicationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `dashboard returns 200 OK with HTML content`() = testApplication {
        application {
            module(customRepository = InMemoryTraceRepository())
        }

        val response = client.get("/dashboard")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("PrismLLM"))
    }

    @Test
    fun `proxy chat completion intercepts request and records trace`() = testApplication {
        val testRepo = InMemoryTraceRepository()
        application {
            module(customRepository = testRepo)
        }

        val requestPayload = ChatCompletionRequest(
            model = "gpt-4o",
            messages = listOf(
                ChatMessage(role = "user", content = "Test prompt for proxy verification")
            ),
            stream = false
        )

        val response = client.post("/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            header("X-Feature-Tag", "automated-test")
            header("X-User-Id", "ci-runner")
            setBody(json.encodeToString(requestPayload))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("choices"))
        assertTrue(body.contains("usage"))

        // Verify that the trace was saved to the repository
        assertTrue(testRepo.count() > 0)
        val trace = testRepo.findAll().first { it.featureTag == "automated-test" }
        assertEquals("gpt-4o", trace.model)
        assertEquals("ci-runner", trace.userTag)
        assertTrue(trace.estimatedCostUsd > 0.0)
    }

    @Test
    fun `analytics kpis endpoint returns computed metrics`() = testApplication {
        val testRepo = InMemoryTraceRepository()
        application {
            module(customRepository = testRepo)
        }

        val response = client.get("/api/analytics/kpis")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("totalRequests"))
        assertTrue(body.contains("totalSpendUsd"))
    }
}
