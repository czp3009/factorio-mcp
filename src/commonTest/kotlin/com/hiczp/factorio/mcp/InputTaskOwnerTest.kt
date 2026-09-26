package com.hiczp.factorio.mcp

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class InputTaskOwnerTest {
    private class Task(var fail: Boolean = false) : GameInputTask {
        var closes = 0
        val result = InputSequenceResult(false, 2, 7, "Cancelled")

        override suspend fun awaitResult() = result

        override suspend fun close(): InputSequenceResult {
            closes++
            check(!fail) { "Release failed" }
            return result
        }
    }

    @Test
    fun failedCallerCleanupRemainsOwnedUntilRetry() = runBlocking {
        val owner = InputTaskOwner()
        val task = Task(true)
        val caller = owner.retain(task)
        assertFailsWith<IllegalStateException> { caller.close() }
        task.fail = false
        owner.close()
        owner.close()
        assertEquals(2, task.closes)
    }

    @Test
    fun oneFailureDoesNotDiscardOtherTasksOrMaskTheFirstFailure() = runBlocking {
        val owner = InputTaskOwner()
        val first = Task(true)
        val second = Task(true)
        val third = Task()
        listOf(first, second, third).forEach(owner::retain)
        val failure = assertFailsWith<IllegalStateException> { owner.close() }
        assertEquals(1, failure.suppressedExceptions.size)
        assertEquals(1, third.closes)
        first.fail = false
        second.fail = false
        owner.close()
        assertEquals(2, first.closes)
        assertEquals(2, second.closes)
        assertEquals(1, third.closes)
    }

    @Test
    fun successfulCallerCleanupReleasesOwnershipAndPreservesProgress() = runBlocking {
        val owner = InputTaskOwner()
        val task = Task()
        val caller = owner.retain(task)
        assertEquals(task.result, caller.awaitResult())
        assertEquals(task.result, caller.close())
        owner.close()
        assertEquals(1, task.closes)
    }
}
