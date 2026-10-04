package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class InputTaskLifecycleTest {
    @Test
    fun closePublishesCancellationAndWaitsForTerminalCleanup() = runBlocking {
        withTimeout(5_000) {
            var progress = InputTaskProgress(1, 2, 7)
            val canceled = CompletableDeferred<Unit>()
            var released = false
            val lifecycle =
                InputTaskLifecycle(
                    { progress },
                    { true },
                    { canceled.complete(Unit) },
                    { released = true },
                )
            val closing = async { lifecycle.close() }
            canceled.await()
            assertFalse(closing.isCompleted)
            assertFalse(released)
            progress = InputTaskProgress(3, 2, 7, "Input canceled after release")
            assertEquals(InputSequenceResult(false, 2, 7, progress.reason), closing.await())
            assertTrue(released)
        }
    }

    @Test
    fun localCleanupFailureRetainsTheOriginalResultForRetry() = runBlocking {
        var reads = 0
        var releases = 0
        val lifecycle =
            InputTaskLifecycle(
                {
                    reads++
                    InputTaskProgress(3, 2, 9, "Original native failure")
                },
                { error("Terminal task must not need a liveness check") },
                {},
                { if (++releases == 1) error("Local resource cleanup failed") },
            )
        val completion = lifecycle.awaitResult()
        assertFailsWith<IllegalStateException> { lifecycle.close() }
        assertEquals(completion, lifecycle.close())
        assertEquals(1, reads)
        assertEquals(2, releases)
    }

    @Test
    fun processExitUsesOnlyLastPublishedProgressAndRejectionDoesNotCancel() = runBlocking {
        var canceled = false
        val exited =
            InputTaskLifecycle({ InputTaskProgress(1, 3, 11) }, { false }, { canceled = true }, {})
        val result = exited.awaitResult()
        assertFalse(result.completed)
        assertEquals(3, result.completedEntries)
        assertEquals(11, result.evaluatedTicks)
        assertEquals(result, exited.close())
        assertFalse(canceled)
        val rejected =
            InputTaskLifecycle(
                { InputTaskProgress(0, 0, 0) },
                { true },
                { error("Not admitted") },
                {},
            )
        assertEquals(InputSequenceResult(false, 0, 0, "Input was not admitted"), rejected.close())
    }
}
