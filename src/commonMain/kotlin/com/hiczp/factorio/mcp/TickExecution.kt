package com.hiczp.factorio.mcp

import kotlinx.coroutines.*
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

internal data class TickResult(val json: String, val ok: Boolean)

/** Shared by the one-shot CLI and the external API. */
internal suspend fun GameProcess.runTicks(
    source: String,
    ticks: Int,
    timeoutMillis: Int,
    untilReady: Boolean = false
): TickResult {
    require(source.encodeToByteArray().size <= 48000) { "Lua source exceeds 48000 bytes" }
    require(ticks in 1..1000000) { "ticks must be 1..1000000" }
    require(timeoutMillis in 1..600000) { "timeout_ms must be 1..600000" }
    check(!Platform.cancelled) { "Interrupted; cancelling session" }
    currentCoroutineContext().ensureActive()
    val token = Uuid.random().toString()
    var pending = true
    try {
        val start = TimeSource.Monotonic.markNow()
        check(
            evaluate(
                installScript(source, ticks, token, untilReady),
                timeoutMillis
            ) == "installed"
        ) { "Invalid install response" }
        while (true) {
            currentCoroutineContext().ensureActive()
            check(!Platform.cancelled) { "Interrupted; cancelling session" }
            val remaining = timeoutMillis - start.elapsedNow().inWholeMilliseconds.toInt()
            check(remaining > 0) { "Timed out waiting for on_tick completion" }
            val response = evaluate(pollScript(token), remaining)
            if (response == "lost") error("World/session changed; execution result is unknown")
            if (response != "pending") {
                check(response.startsWith("done-ok\n") || response.startsWith("done-error\n")) { "Invalid session response" }
                pending = false
                return TickResult(response.substringAfter('\n'), response.startsWith("done-ok\n"))
            }
            delay(50)
        }
    } finally {
        if (pending) {
            try {
                withContext(NonCancellable) { evaluate(cleanupScript(token), minOf(timeoutMillis, 2000)) }
            } catch (e: Exception) {
                val message = "Cleanup pending: ${e.message}. Restore a running world before retrying detach."
                Platform.writeError(message)
                throw IllegalStateException(message, e)
            }
        }
    }
}
