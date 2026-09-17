package io.prism.server.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.prism.core.model.ChatCompletionRequest
import io.prism.proxy.LlmProxyService
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.OutputStreamWriter

fun Route.proxyRoutes(proxyService: LlmProxyService) {
    val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    route("/v1/chat/completions") {
        post {
            val request = call.receive<ChatCompletionRequest>()
            val authHeader = call.request.header(HttpHeaders.Authorization)
            val featureTag = call.request.header("X-Feature-Tag")
            val userTag = call.request.header("X-User-Id")
            val clientIp = call.request.origin.remoteHost

            if (request.stream) {
                call.response.cacheControl(CacheControl.NoCache(null))
                call.respondTextWriter(ContentType.Text.EventStream) {
                    val streamFlow = proxyService.handleStreamingChatCompletion(
                        request = request,
                        authHeader = authHeader,
                        featureTag = featureTag,
                        userTag = userTag,
                        clientIp = clientIp
                    )

                    streamFlow.collect { chunk ->
                        val serialized = json.encodeToString(chunk)
                        write("data: $serialized\n\n")
                        flush()
                    }
                    write("data: [DONE]\n\n")
                    flush()
                }
            } else {
                val response = proxyService.handleChatCompletion(
                    request = request,
                    authHeader = authHeader,
                    featureTag = featureTag,
                    userTag = userTag,
                    clientIp = clientIp
                )
                call.respond(HttpStatusCode.OK, response)
            }
        }
    }
}
