package com.hiczp.factorio.mcp

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Test requests use the SDK's standard Streamable HTTP endpoint. */
internal class McpHttpClient(private val url: String) {
    init {
        require(Url(url).protocol == URLProtocol.HTTP) { "Acceptance tests require an HTTP endpoint" }
    }

    private val client = HttpClient(CIO) {
        // The test's coroutine watchdog owns its deadline, including long attachment metadata reads.
        engine { requestTimeout = 0 }
    }
    private var session: String? = null
    private var sequence = 0

    suspend fun initialize() {
        request(
            "initialize",
            buildJsonObject {
                put("protocolVersion", "2025-11-25")
                putJsonObject("capabilities") {}
                putJsonObject("clientInfo") {
                    put("name", "factorio-mcp-tests")
                    put("version", "1")
                }
            },
        )
        request("notifications/initialized", null, true)
    }

    suspend fun request(
        method: String,
        parameters: JsonObject? = null,
        notification: Boolean = false,
        requestId: Int? = null,
    ): JsonObject =
        try {
            exchange(method, parameters, notification, requestId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val causes =
                generateSequence<Throwable>(failure) { it.cause }
                    .take(8)
                    .joinToString(" -> ") { "${it::class.simpleName}: ${it.message}" }
            throw IllegalStateException("MCP $method request to $url failed: $causes", failure)
        }

    private suspend fun exchange(
        method: String,
        parameters: JsonObject?,
        notification: Boolean,
        requestId: Int?,
    ): JsonObject {
        val response =
            client.post(url) {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Accept, "application/json, text/event-stream")
                header("MCP-Protocol-Version", "2025-11-25")
                session?.let { header("Mcp-Session-Id", it) }
                setBody(
                    buildJsonObject {
                        put("jsonrpc", "2.0")
                        if (!notification) {
                            sequence =
                                requestId?.also { require(it > sequence) } ?: sequence + 1
                            put("id", sequence)
                        }
                        put("method", method)
                        parameters?.let { put("params", it) }
                    }
                        .toString()
                )
            }
        check(response.status.value in 200..299) {
            "HTTP ${response.status}: ${response.bodyAsText()}"
        }
        response.headers["Mcp-Session-Id"]?.let { session = it }
        val body = response.bodyAsText()
        if (notification) return buildJsonObject {}
        val json =
            if (body.startsWith("event:") || body.startsWith("data:"))
                body
                    .lineSequence()
                    .filter { it.startsWith("data:") }
                    .joinToString("\n") { it.removePrefix("data:").trimStart() }
            else body
        return Json.parseToJsonElement(json).jsonObject
    }

    suspend fun tool(
        name: String,
        arguments: JsonObject = buildJsonObject {},
        requestId: Int? = null,
    ): JsonObject =
        request(
            "tools/call",
            buildJsonObject {
                put("name", name)
                put("arguments", arguments)
            },
            requestId = requestId,
        )
            .getValue("result")
            .jsonObject

    suspend fun close() {
        try {
            session?.let {
                client.delete(url) {
                    header("Mcp-Session-Id", it)
                    header("MCP-Protocol-Version", "2025-11-25")
                }
            }
        } finally {
            withContext(NonCancellable) {
                client.close()
                client.coroutineContext.job.join()
                client.engine.coroutineContext.job.join()
            }
        }
    }
}

internal fun JsonObject.toolValue(): JsonObject {
    check(this["isError"]?.jsonPrimitive?.boolean != true) { toString() }
    return Json.parseToJsonElement(
        getValue("content").jsonArray.first().jsonObject.getValue("text").jsonPrimitive.content
    )
        .jsonObject
}
