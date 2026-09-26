package com.hiczp.factorio.mcp

import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.util.logging.*
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import kotlinx.coroutines.*

internal data class McpOptions(
    val stdio: Boolean = true,
    val http: Boolean = true,
    val httpPort: Int = 3000,
) {
    companion object {
        fun parse(args: Array<String>): McpOptions {
            var options = McpOptions()
            val seen = mutableSetOf<String>()
            var index = 0
            while (index < args.size) {
                val option = args[index++]
                require(seen.add(option)) { "Duplicate option: $option" }
                options =
                    when (option) {
                        "--no-stdio" -> options.copy(stdio = false)
                        "--no-http" -> options.copy(http = false)
                        "--http-port" -> {
                            val port = args.getOrNull(index++)?.toIntOrNull()
                            require(port != null && port in 0..65535) {
                                "--http-port requires a port in 0..65535"
                            }
                            options.copy(httpPort = port)
                        }

                        else -> error("Unknown MCP option: $option")
                    }
            }
            require(options.stdio || options.http) { "At least one MCP transport must be enabled" }
            require(options.http || "--http-port" !in seen) { "--http-port requires HTTP" }
            return options
        }
    }
}

/** SDK sessions own protocol state; the shared server owns game state across reconnects. */
internal suspend fun serveTransports(server: Server, options: McpOptions) = coroutineScope {
    val stopping = CompletableDeferred<Unit>()
    val parent =
        currentCoroutineContext() +
                CoroutineExceptionHandler { _, failure ->
                    Platform.writeError("factorio-mcp: HTTP: ${failure.message}")
                }
    val http =
        if (options.http)
            embeddedServer(
                CIO,
                rootConfig =
                    serverConfig(applicationEnvironment { log = HttpLogger }) {
                        parentCoroutineContext = parent
                        module { mcpStreamableHttp { server } }
                    },
                configure = {
                    reuseAddress = true
                    connector {
                        host = "127.0.0.1"
                        port = options.httpPort
                    }
                },
            )
        else null
    val signals = launch {
        while (!Platform.cancelled) delay(100)
        stopping.complete(Unit)
    }
    try {
        if (http != null) {
            http.startSuspend(wait = false)
            // Ktor Native installs its own signal handlers during startup. Restore the
            // nonblocking cancellation handler; coroutine cleanup owns both transports.
            Platform.initializeCancellation()
            val port = http.engine.resolvedConnectors().single().port
            Platform.writeError("factorio-mcp: HTTP listening at http://127.0.0.1:$port/mcp")
        }
        if (options.stdio) {
            val transport =
                StdioServerTransport(Platform.standardInput(), Platform.standardOutput()) {}
            transport.onClose { if (!options.http) stopping.complete(Unit) }
            server.createSession(transport)
        }
        stopping.await()
    } finally {
        signals.cancel()
        withContext(NonCancellable) { http?.stopSuspend(0, 1000) }
    }
}

/** Ktor's Native default logger writes to stdout, which is reserved for MCP frames. */
private object HttpLogger : Logger by KtorSimpleLogger("factorio-mcp") {
    override fun error(message: String) = Platform.writeError(message)

    override fun error(message: String, cause: Throwable) =
        Platform.writeError("$message: ${cause.message}")

    override fun warn(message: String) = Platform.writeError(message)

    override fun warn(message: String, cause: Throwable) =
        Platform.writeError("$message: ${cause.message}")

    override fun info(message: String) = Unit

    override fun info(message: String, cause: Throwable) = Unit

    override fun debug(message: String) = Unit

    override fun debug(message: String, cause: Throwable) = Unit

    override fun trace(message: String) = Unit

    override fun trace(message: String, cause: Throwable) = Unit
}
