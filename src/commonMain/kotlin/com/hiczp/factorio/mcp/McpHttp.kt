package com.hiczp.factorio.mcp

import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sse.SSE
import io.ktor.server.sse.sse
import io.ktor.util.collections.ConcurrentMap
import io.modelcontextprotocol.kotlin.sdk.server.DnsRebindingProtection
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.StreamableHttpServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.serialization.json.*

// Matches the SDK stdio reader's framing bound, including the JSON-RPC envelope.
internal const val MCP_REQUEST_BYTES = 16 * 1024 * 1024

/** SDK transports retain session/protocol handling; routing supplies an explicit body budget. */
internal fun Application.mcpHttp(server: Server) {
    install(ContentNegotiation) { json(McpJson) }
    install(SSE)
    val transports = ConcurrentMap<String, StreamableHttpServerTransport>()
    val configuration = StreamableHttpServerTransport.Configuration(
        enableJsonResponse = true,
        maxRequestBodySize = MCP_REQUEST_BYTES.toLong(),
    )

    suspend fun existing(call: ApplicationCall): StreamableHttpServerTransport? {
        val id = call.request.header("Mcp-Session-Id")
        val transport = id?.let { transports[it] }
        if (transport == null) {
            val status = if (id.isNullOrEmpty()) HttpStatusCode.BadRequest else HttpStatusCode.NotFound
            val error = buildJsonObject {
                put("jsonrpc", "2.0")
                putJsonObject("error") {
                    put("code", -32000)
                    put("message", if (id.isNullOrEmpty()) "Missing MCP session ID" else "Session not found")
                }
            }
            call.respondText(error.toString(), ContentType.Application.Json, status)
        }
        return transport
    }

    routing {
        route("/mcp") {
            install(DnsRebindingProtection) {
                allowedHosts = listOf("localhost", "127.0.0.1", "[::1]")
                allowedOrigins = listOf("http://localhost", "http://127.0.0.1", "http://[::1]")
            }
            intercept(ApplicationCallPipeline.Plugins) {
                if (call.request.httpMethod == HttpMethod.Get) {
                    val transport = existing(call)
                    if (transport == null) finish()
                    else call.response.header("Mcp-Session-Id", checkNotNull(transport.sessionId))
                }
            }
            sse {
                existing(call)?.handleRequest(this, call)
            }
            post {
                if (call.request.header("Mcp-Session-Id") != null) {
                    existing(call)?.handleRequest(null, call)
                } else {
                    val transport = StreamableHttpServerTransport(configuration)
                    transport.setOnSessionInitialized { transports[it] = transport }
                    transport.setOnSessionClosed { transports.remove(it) }
                    server.onClose { transport.sessionId?.let { transports.remove(it) } }
                    val session = server.createSession(transport)
                    try {
                        transport.handleRequest(null, call)
                    } finally {
                        if (transport.sessionId == null) session.close()
                    }
                }
            }
            delete {
                existing(call)?.handleRequest(null, call)
            }
        }
    }
}
