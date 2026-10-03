package com.hiczp.factorio.mcp

import kotlinx.coroutines.delay

internal data class InputTaskProgress(
    val state: Int,
    val completedOperations: Int,
    val evaluatedTicks: Long,
    val reason: String? = null,
)

/** Cooperative task policy. Platform adapters own atomic reads, cancellation publication and local resources. */
internal class InputTaskLifecycle(
    private val progress: () -> InputTaskProgress,
    private val alive: () -> Boolean,
    private val cancel: () -> Unit,
    private val release: () -> Unit,
) : GameInputTask {
    private var result: InputSequenceResult? = null

    private fun observe(): InputTaskProgress = progress().also {
        check(it.state in 0..3) { "Invalid resident input task state" }
    }

    override suspend fun awaitResult(): InputSequenceResult {
        result?.let { return it }
        while (true) {
            val current = observe()
            val completion = if (current.state >= 2) {
                InputSequenceResult(current.state == 2, current.completedOperations, current.evaluatedTicks, current.reason)
            } else if (!alive()) {
                InputSequenceResult(false, current.completedOperations, current.evaluatedTicks,
                    "Factorio process exited; progress is the last published observation")
            } else null
            if (completion != null) {
                result = completion
                return completion
            }
            delay(10)
        }
    }

    override suspend fun close(): InputSequenceResult {
        if (result == null) {
            // Admission has settled before the owner calls close. Zero therefore means rejection.
            if (observe().state == 0) result = InputSequenceResult(false, 0, 0, "Input was not admitted")
            else {
                cancel()
                awaitResult()
            }
        }
        val completion = checkNotNull(result)
        // Keep the terminal result when releasing local resources fails, so the owner can retry cleanup.
        release()
        return completion
    }
}
