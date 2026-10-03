package com.hiczp.factorio.mcp

import kotlinx.coroutines.*
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

class GameSessionTest {
    private class Connection : GameConnection {
        var live = true
        var state = "main_menu"
        var closed = false
        var blocked = false
        var cleaned = false
        var cleanupGate: CompletableDeferred<Unit>? = null
        var reads = 0
        var closeCount = 0
        var failClose = false
        var failOperation: Int? = null
        var failureGate: CompletableDeferred<Unit>? = null
        var exitOnFailure = true
        var notifications = false
        val exit = CompletableDeferred<Unit>()
        val observing = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val calls = mutableListOf<Int>()
        val inputStarted = CompletableDeferred<Unit>()
        val inputs = mutableListOf<InputTask>()

        inner class InputTask : GameInputTask {
            val result = CompletableDeferred<InputSequenceResult>()
            var closed = false
            var failClose = false

            override suspend fun awaitResult() = result.await()

            override suspend fun close(): InputSequenceResult {
                check(!failClose) { "Input cleanup failed" }
                result.complete(
                    InputSequenceResult(false, 1, 3, if (live) "cancelled" else "process exited")
                )
                closed = true
                return if (result.isCancelled) InputSequenceResult(false, 1, 3, "failed")
                else result.await()
            }
        }

        override suspend fun beginInput(request: InputSequenceRequest): GameInputTask {
            val active = inputs.lastOrNull()?.takeUnless { it.result.isCompleted }
            check(active == null || request.stopPrevious) { "Another input is active" }
            active?.close()
            calls += 9
            return InputTask().also {
                inputs += it
                inputStarted.complete(Unit)
            }
        }

        override suspend fun execute(operation: Int, limit: Int, action: UiAction?): GameSnapshot {
            calls += operation
            if (operation == failOperation) {
                started.complete(Unit)
                failureGate?.await()
                if (exitOnFailure) live = false
                error("Native operation failed")
            }
            if (operation in listOf(3, 5, 6, 8)) {
                if (operation == 3) reads++
                started.complete(Unit)
                if (blocked)
                    try {
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) { cleanupGate?.await() }
                        cleaned = true
                    }
            }
            if (operation == 4) check(!blocked || cleaned)
            if (operation == 4) check(inputs.all { it.closed })
            return GameSnapshot(state, operation != 4, 1)
        }

        override suspend fun isAlive() = live

        override suspend fun query(query: WorldQuery) = execute(8, 4096, null)

        override suspend fun awaitExit() {
            observing.complete(Unit)
            if (notifications) exit.await() else awaitCancellation()
        }

        override suspend fun close() {
            check(inputs.all { it.closed })
            closeCount++
            check(!failClose) { "Local cleanup failed" }
            closed = true
            released.complete(Unit)
        }
    }

    private val inputRequest =
        InputSequenceRequest(listOf(InputOperation(listOf(InputControl.Keyboard("W")), 2)), false)

    @Test
    fun statusReportsExitDiscoveredDuringNativeObservation() = runBlocking {
        val connection = Connection()
        val session = GameSession { connection }
        session.attach(12)
        connection.failOperation = 2
        val result = session.status()
        assertEquals("exited", result.getValue("state").jsonPrimitive.content)
        assertFalse(result.getValue("attached").jsonPrimitive.boolean)
        assertTrue(connection.closed)
        assertEquals(1, connection.closeCount)
        assertFalse(4 in connection.calls)
        assertEquals(result, session.status())
        session.close()
    }

    @Test
    fun statusRetainsNativeFailureWhenTheProcessIsAliveOrExitCleanupFails() = runBlocking {
        for (exits in listOf(false, true)) {
            val connection = Connection()
            val session = GameSession { connection }
            session.attach(12)
            connection.failOperation = 2
            connection.exitOnFailure = exits
            connection.failClose = exits
            val failure = assertFailsWith<IllegalStateException> { session.status() }
            assertEquals("Native operation failed", failure.message)
            if (exits) assertEquals("Local cleanup failed", failure.suppressedExceptions.single().message)
            else assertTrue(failure.suppressedExceptions.isEmpty())
            assertFalse(connection.closed)
            connection.failClose = false
            session.detach()
            assertTrue(connection.closed)
            session.close()
        }
    }

    @Test
    fun inputFailureSurvivesTaskCleanupFailure() = runBlocking {
        val connection = Connection()
        val session = GameSession { connection }
        session.attach(12)
        val running = async { runCatching { session.input(inputRequest) } }
        connection.inputStarted.await()
        val task = connection.inputs.single()
        task.failClose = true
        task.result.completeExceptionally(IllegalStateException("Input polling failed"))
        val failure = running.await().exceptionOrNull()!!
        assertEquals("Input polling failed", failure.message)
        assertEquals("Input cleanup failed", failure.suppressedExceptions.single().message)
        task.failClose = false
        task.close()
        session.detach()
        session.close()
    }

    @Test
    fun inputFailureSurvivesExitCleanupFailure() = runBlocking {
        val connection = Connection()
        val session = GameSession { connection }
        session.attach(12)
        val running = async { runCatching { session.input(inputRequest) } }
        connection.inputStarted.await()
        connection.live = false
        connection.failClose = true
        connection.inputs
            .single()
            .result
            .completeExceptionally(IllegalStateException("Input polling failed"))
        val failure = running.await().exceptionOrNull()!!
        assertEquals("Input polling failed", failure.message)
        assertEquals("Local cleanup failed", failure.suppressedExceptions.single().message)
        connection.failClose = false
        session.detach()
        assertTrue(connection.closed)
        session.close()
    }

    @Test
    fun exitCleanupFailurePreservesTheOriginalReadFailureAndCanBeRetried() = runBlocking {
        val connection = Connection()
        val session = GameSession { connection }
        session.attach(12)
        connection.failOperation = 3
        connection.failClose = true
        val failure = assertFailsWith<IllegalStateException> { session.read(10, false) }
        assertEquals("Native operation failed", failure.message)
        assertEquals("Local cleanup failed", failure.suppressedExceptions.single().message)
        assertFalse(connection.closed)
        connection.failClose = false
        session.detach()
        assertTrue(connection.closed)
        session.close()
    }

    @Test
    fun failedLocalCleanupRemainsRetryable() = runBlocking {
        val connection = Connection()
        val session = GameSession { connection }
        session.attach(12)
        connection.failClose = true
        assertFailsWith<IllegalStateException> { session.detach() }
        assertFalse(connection.closed)
        assertFailsWith<IllegalStateException> { session.attach(13) }
        assertFailsWith<IllegalStateException> { session.attach(12) }
        assertFailsWith<IllegalStateException> { session.read(10, false) }
        connection.failClose = false
        session.detach()
        assertTrue(connection.closed)
        assertEquals(2, connection.closeCount)
        assertEquals(1, connection.calls.count { it == 4 })
        assertEquals("detached", session.status()["state"]!!.jsonPrimitive.content)
        session.close()
    }

    @Test
    fun failedAttachRetainsConnectionWhenLocalCleanupFails() = runBlocking {
        val connection =
            Connection().apply {
                failOperation = 1
                exitOnFailure = false
                failClose = true
            }
        val session = GameSession { connection }
        val failure = assertFailsWith<IllegalStateException> { session.attach(12) }
        assertEquals("Native operation failed", failure.message)
        assertEquals("Local cleanup failed", failure.suppressedExceptions.single().message)
        assertFailsWith<IllegalStateException> { session.attach(13) }
        connection.failClose = false
        session.detach()
        assertTrue(connection.closed)
        assertEquals(listOf(1, 4), connection.calls)
        session.close()
    }

    @Test
    fun admittedInputDoesNotBlockReadsAndRejectsConcurrentInput() = runBlocking {
        withTimeout(2_000) {
            val connection = Connection()
            val session = GameSession { connection }
            try {
                session.attach(12)
                val input = async { session.input(inputRequest) }
                connection.inputStarted.await()
                session.read(10, false)
                assertFalse(input.isCompleted)
                assertFailsWith<IllegalStateException> { session.input(inputRequest) }
                assertFalse(connection.inputs.single().closed)
                connection.inputs.single().result.complete(InputSequenceResult(true, 1, 2))
                assertEquals("completed", input.await()["status"]!!.jsonPrimitive.content)
                assertEquals(listOf(1, 9, 3), connection.calls)
            } finally {
                session.close()
            }
        }
    }

    @Test
    fun detachWaitsForInputReleaseAndPreservesTerminalProgress() = runBlocking {
        withTimeout(2_000) {
            val connection = Connection()
            val session = GameSession { connection }
            session.attach(12)
            val input = async { session.input(inputRequest) }
            connection.inputStarted.await()
            session.detach()
            val result = input.await()
            assertEquals("aborted", result["status"]!!.jsonPrimitive.content)
            assertEquals(1, result["completed_operations"]!!.jsonPrimitive.int)
            assertEquals(3, result["evaluated_ticks"]!!.jsonPrimitive.int)
            assertTrue(connection.inputs.single().closed && connection.closed)
            session.close()
        }
    }

    @Test
    fun processExitDrainsInputBeforeReleasingItsConnection() = runBlocking {
        withTimeout(2_000) {
            val connection = Connection().apply { notifications = true }
            val session = GameSession { connection }
            session.attach(12)
            val input = async { session.input(inputRequest) }
            connection.inputStarted.await()
            connection.live = false
            connection.exit.complete(Unit)
            val result = input.await()
            assertEquals("process exited", result["reason"]!!.jsonPrimitive.content)
            assertEquals("exited", session.status()["state"]!!.jsonPrimitive.content)
            assertEquals(1, connection.closeCount)
            session.close()
        }
    }

    @Test
    fun explicitCancellationReleasesInputWithoutDetaching() = runBlocking {
        withTimeout(2_000) {
            val connection = Connection()
            val session = GameSession { connection }
            session.attach(12)
            val input = async { session.input(inputRequest) }
            connection.inputStarted.await()
            input.cancelAndJoin()
            assertTrue(connection.inputs.single().closed)
            assertTrue(session.status()["attached"]!!.jsonPrimitive.boolean)
            session.close()
        }
    }

    @Test
    fun replacementKeepsEachCallersCompletionIndependent() = runBlocking {
        withTimeout(2_000) {
            val connection = Connection()
            val session = GameSession { connection }
            session.attach(12)
            val first = async { session.input(inputRequest) }
            connection.inputStarted.await()
            val second =
                async(start = CoroutineStart.UNDISPATCHED) {
                    session.input(inputRequest.copy(stopPrevious = true))
                }
            assertEquals("aborted", first.await()["status"]!!.jsonPrimitive.content)
            connection.inputs.last().result.complete(InputSequenceResult(true, 1, 2))
            assertEquals("completed", second.await()["status"]!!.jsonPrimitive.content)
            assertTrue(connection.inputs.all { it.closed })
            session.close()
        }
    }

    @Test
    fun stateChangesDoNotRequireNewAttachment() = runBlocking {
        val connection = Connection()
        var opens = 0
        val session = GameSession {
            opens++
            connection
        }
        assertFalse(session.status().getValue("attached").jsonPrimitive.boolean)
        session.attach(12)
        session.attach(12)
        for (state in listOf("main_menu", "loading", "in_game", "main_menu")) {
            connection.state = state
            assertEquals(state, session.status().getValue("state").jsonPrimitive.content)
            session.read(10, false)
        }
        assertEquals(1, opens)
        session.detach()
        session.detach()
        assertTrue(connection.closed)
        assertEquals(1, connection.calls.count { it == 4 })
    }

    @Test
    fun detachAbortsRunningAndWaitingReadsBeforeUnhook() = runBlocking {
        val connection = Connection().apply { blocked = true }
        val session = GameSession { connection }
        session.attach(12)
        supervisorScope {
            val first = async { runCatching { session.read(10, false) } }
            connection.started.await()
            val second =
                async(start = CoroutineStart.UNDISPATCHED) {
                    runCatching { session.read(10, false) }
                }
            session.detach()
            assertIs<CancellationException>(first.await().exceptionOrNull())
            assertIs<CancellationException>(second.await().exceptionOrNull())
        }
        assertEquals(1, connection.reads)
        assertTrue(connection.cleaned)
    }

    @Test
    fun screenshotRejectsConcurrentCaptureAndCancellationWaitsForCleanup() = runBlocking {
        withTimeout(1000) {
            val connection = Connection().apply {
                blocked = true
                cleanupGate = CompletableDeferred()
            }
            val session = GameSession { connection }
            session.attach(12)
            supervisorScope {
                val capture = async { runCatching { session.screenshot() } }
                connection.started.await()
                assertFailsWith<IllegalStateException> { session.screenshot() }
                assertEquals(1, connection.calls.count { it == 6 })
                val cancellation = async(start = CoroutineStart.UNDISPATCHED) {
                    session.cancelScreenshot()
                }
                assertFalse(cancellation.isCompleted)
                connection.cleanupGate!!.complete(Unit)
                assertTrue(cancellation.await().getValue("cancelled").jsonPrimitive.boolean)
                assertIs<CancellationException>(capture.await().exceptionOrNull())
                assertTrue(connection.cleaned)
                assertFalse(session.cancelScreenshot().getValue("cancelled").jsonPrimitive.boolean)
                connection.blocked = false
                session.screenshot()
                assertEquals(2, connection.calls.count { it == 6 })
            }
            session.close()
        }
    }

    @Test
    fun screenshotCanBeCancelledBeforeNativeAdmission() = runBlocking {
        withTimeout(1000) {
            val connection = Connection().apply { blocked = true }
            val session = GameSession { connection }
            session.attach(12)
            supervisorScope {
                val read = async { runCatching { session.read(10, false) } }
                connection.started.await()
                val capture = async(start = CoroutineStart.UNDISPATCHED) {
                    runCatching { session.screenshot() }
                }
                session.cancelScreenshot()
                assertIs<CancellationException>(capture.await().exceptionOrNull())
                assertFalse(6 in connection.calls)
                read.cancelAndJoin()
            }
            session.close()
        }
    }

    @Test
    fun detachAlsoDrainsActionsAndScreenshots() = runBlocking {
        for (operation in listOf(5, 6, 8)) {
            val connection = Connection().apply { blocked = true }
            val session = GameSession { connection }
            session.attach(12)
            supervisorScope {
                val running = async {
                    runCatching {
                        if (operation == 5) session.action(UiAction(emptyList(), 1))
                        else if (operation == 8) session.query(WorldQuery(buildJsonObject {}))
                        else session.screenshot()
                    }
                }
                connection.started.await()
                val waiting =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        runCatching { session.read(10, false) }
                    }
                session.detach()
                assertIs<CancellationException>(running.await().exceptionOrNull())
                assertIs<CancellationException>(waiting.await().exceptionOrNull())
                assertTrue(connection.cleaned)
                assertEquals(listOf(1, operation, 4), connection.calls)
            }
        }
    }

    @Test
    fun processExitClearsAttachmentWithoutCallingDeadProcess() = runBlocking {
        val connection = Connection()
        val session = GameSession { connection }
        session.attach(12)
        connection.live = false
        assertEquals("exited", session.status().getValue("state").jsonPrimitive.content)
        assertTrue(connection.closed)
        session.detach()
        assertEquals(listOf(1), connection.calls)
    }

    @Test
    fun queuedReattachCannotBlockDetachFromCancellingAnObservation() = runBlocking {
        withTimeout(1000) {
            val connection = Connection().apply { blocked = true }
            val session = GameSession { connection }
            session.attach(12)
            supervisorScope {
                val read = async { runCatching { session.read(10, false) } }
                connection.started.await()
                val reattach =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        runCatching { session.attach(12) }
                    }
                session.detach()
                assertIs<CancellationException>(read.await().exceptionOrNull())
                assertIs<CancellationException>(reattach.await().exceptionOrNull())
                assertEquals(listOf(1, 3, 4), connection.calls)
            }
        }
    }

    @Test
    fun detachCancelsInitialAttachAndCleansPartialInjection() = runBlocking {
        withTimeout(1000) {
            val connection =
                Connection().apply {
                    failOperation = 1
                    failureGate = CompletableDeferred()
                }
            val session = GameSession { connection }
            supervisorScope {
                val attaching = async { runCatching { session.attach(12) } }
                connection.started.await()
                session.detach()
                assertIs<CancellationException>(attaching.await().exceptionOrNull())
                assertEquals(listOf(1, 4), connection.calls)
                assertTrue(connection.closed)
                assertFalse(session.status().getValue("attached").jsonPrimitive.boolean)
            }
        }
    }

    @Test
    fun failedAttachClosesItsResourcesAndDoesNotReserveThePid() = runBlocking {
        var failedClosed = false
        val good = Connection()
        val session = GameSession { pid ->
            if (pid == 1)
                object : GameConnection {
                    override suspend fun execute(
                        operation: Int,
                        limit: Int,
                        action: UiAction?,
                    ): GameSnapshot {
                        if (operation == 4) return GameSnapshot("detached", false, 0)
                        error("Not ready")
                    }

                    override suspend fun isAlive() = true

                    override suspend fun close() {
                        failedClosed = true
                    }
                }
            else good
        }
        assertFailsWith<IllegalStateException> { session.attach(1) }
        assertTrue(failedClosed)
        assertFalse(session.status().getValue("attached").jsonPrimitive.boolean)
        assertTrue(session.attach(2).getValue("attached").jsonPrimitive.boolean)
        session.detach()
        assertTrue(good.closed)
    }

    @Test
    fun readPreflightReleasesExitedProcessWithoutRequiringStatusOrDetach() = runBlocking {
        val old = Connection()
        val next = Connection()
        val session = GameSession { if (it == 1) old else next }
        session.attach(1)
        old.live = false
        assertFailsWith<IllegalStateException> { session.read(10, false) }
        assertEquals(listOf(1), old.calls)
        assertEquals(1, old.closeCount)
        assertEquals(2, session.attach(2).getValue("pid").jsonPrimitive.int)
        session.close()
    }

    @Test
    fun exitDuringReadCleansUpAndAbortsWaitingReads() = runBlocking {
        val old =
            Connection().apply {
                failOperation = 3
                failureGate = CompletableDeferred()
            }
        val session = GameSession { old }
        session.attach(1)
        supervisorScope {
            val first = async { runCatching { session.read(10, false) } }
            old.started.await()
            val second =
                async(start = CoroutineStart.UNDISPATCHED) {
                    runCatching { session.read(10, false) }
                }
            old.failureGate!!.complete(Unit)
            assertEquals("Native operation failed", first.await().exceptionOrNull()?.message)
            assertIs<CancellationException>(second.await().exceptionOrNull())
        }
        assertEquals(1, old.calls.count { it == 3 })
        assertEquals(1, old.closeCount)
        assertEquals("exited", session.status().getValue("state").jsonPrimitive.content)
        session.close()
    }

    @Test
    fun attachCanReplaceAnExitedProcessWithoutAnIntermediateTool() = runBlocking {
        val old = Connection()
        val next = Connection()
        val session = GameSession { if (it == 1) old else next }
        session.attach(1)
        old.live = false
        assertEquals(2, session.attach(2).getValue("pid").jsonPrimitive.int)
        assertEquals(1, old.closeCount)
        session.close()
    }

    @Test
    fun exitDuringUnhookStillCompletesDetachCleanup() = runBlocking {
        val old = Connection().apply { failOperation = 4 }
        val session = GameSession { old }
        session.attach(1)
        assertFalse(session.detach().getValue("attached").jsonPrimitive.boolean)
        assertEquals(1, old.closeCount)
        session.detach()
        assertEquals(1, old.closeCount)
        session.close()
    }

    @Test
    fun liveUnhookFailureKeepsTheAttachmentRetryable() = runBlocking {
        val target =
            Connection().apply {
                failOperation = 4
                exitOnFailure = false
            }
        val session = GameSession { target }
        session.attach(1)
        assertFailsWith<IllegalStateException> { session.detach() }
        assertFalse(target.closed)
        assertTrue(session.status().getValue("attached").jsonPrimitive.boolean)
        target.failOperation = null
        session.detach()
        assertTrue(target.closed)
        session.close()
    }

    @Test
    fun notificationReleasesAnIdleAttachmentWithoutAnotherToolCall() = runBlocking {
        withTimeout(5_000) {
            val old = Connection().apply { notifications = true }
            val next = Connection()
            val session = GameSession { if (it == 1) old else next }
            try {
                session.attach(1)
                old.observing.await()
                old.live = false
                old.exit.complete(Unit)
                old.released.await()
                assertEquals(1, old.closeCount)
                assertEquals("exited", session.status().getValue("state").jsonPrimitive.content)
                assertEquals(2, session.attach(2).getValue("pid").jsonPrimitive.int)
            } finally {
                session.close()
            }
        }
    }

    @Test
    fun detachedObserversCannotInvalidateANewerAttachment() = runBlocking {
        val old = Connection().apply { notifications = true }
        val next = Connection()
        val session = GameSession { if (it == 1) old else next }
        session.attach(1)
        old.observing.await()
        session.detach()
        session.attach(2)
        old.live = false
        old.exit.complete(Unit)
        assertEquals(2, session.status().getValue("pid").jsonPrimitive.int)
        assertFalse(next.closed)
        session.close()
    }
}
