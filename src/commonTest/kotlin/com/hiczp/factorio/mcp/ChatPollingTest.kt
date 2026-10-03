package com.hiczp.factorio.mcp

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class ChatPollingTest {
    private fun JsonObject.offset() = parseChatRead(buildJsonObject {
        put("offset", getValue("next_offset"))
    }).offset
    private class Connection : GameConnection {
        var live = true
        var closed = false
        var console = 1uL
        var records = emptyList<ChatRecord>()
        var reads = 0
        var readSignal = CompletableDeferred<Unit>()

        override suspend fun execute(operation: Int, limit: Int, action: UiAction?): GameSnapshot {
            if (operation == 10) {
                reads++
                readSignal.complete(Unit)
            }
            return GameSnapshot(
                "world", operation != 4, reads.toLong(),
                chat = ChatSnapshot(console, listOf(records.size.toULong(), 0uL), records),
            )
        }

        override suspend fun sendChat(text: String): GameSnapshot {
            records = listOf(ChatRecord(1uL, 7uL, 0, 0, text, text))
            return execute(11, 1, null)
        }

        override suspend fun isAlive() = live

        override suspend fun close() {
            closed = true
        }
    }

    @Test
    fun zeroTimeoutReturnsAnEmptyObservationWithoutPolling() = runBlocking {
        val connection = Connection()
        val session = GameSession { connection }
        try {
            session.attach(12)
            val result = session.readChat(null, 64)
            assertTrue(result.getValue("messages").jsonArray.isEmpty())
            assertEquals(1, connection.reads)
        } finally {
            session.close()
        }
    }

    @Test
    fun waitReturnsNewMessageAndDoesNotBlockOtherCommands() = runBlocking {
        withTimeout(5_000) {
            val connection = Connection()
            val session = GameSession { connection }
            try {
                session.attach(12)
                val before = session.readChat(null, 64)
                connection.readSignal = CompletableDeferred()
                val waiting = async {
                    session.readChat(before.offset(), 64, 30)
                }
                connection.readSignal.await()
                assertFalse(waiting.isCompleted)
                assertTrue(session.status().getValue("attached").jsonPrimitive.boolean)
                session.sendChat("same tick [item=iron-plate]")
                val result = waiting.await()
                val message = result.getValue("messages").jsonArray.single().jsonObject
                assertEquals("same tick [item=iron-plate]", message.getValue("raw").jsonPrimitive.content)
                assertEquals(message.getValue("offset"), result.getValue("next_offset"))
                assertTrue(connection.reads >= 3)
            } finally {
                session.close()
            }
        }
    }

    @Test
    fun positiveTimeoutExpiresWithAnEmptyPageAndStableOffset() = runBlocking {
        withTimeout(5_000) {
            val connection = Connection()
            val session = GameSession { connection }
            try {
                session.attach(12)
                val before = session.readChat(null, 64)
                val start = TimeSource.Monotonic.markNow()
                val result = session.readChat(before.offset(), 64, 1)
                assertTrue(start.elapsedNow() >= 1.seconds)
                assertTrue(result.getValue("messages").jsonArray.isEmpty())
                assertEquals(before.getValue("next_offset"), result.getValue("next_offset"))
                assertTrue(connection.reads > 1)
            } finally {
                session.close()
            }
        }
    }

    @Test
    fun detachAbortsAWaitingCallerAndReleasesItsAttachment() = runBlocking {
        withTimeout(5_000) {
            val connection = Connection()
            val session = GameSession { connection }
            try {
                session.attach(12)
                val waiting = async { runCatching { session.readChat(null, 64, 30) } }
                connection.readSignal.await()
                session.detach()
                val failure = waiting.await().exceptionOrNull()
                assertIs<CancellationException>(failure)
                assertEquals("Tool aborted: detach requested", failure.message)
                assertTrue(connection.closed)
                assertFalse(session.status().getValue("attached").jsonPrimitive.boolean)
            } finally {
                session.close()
            }
        }
    }

    @Test
    fun processExitAbortsAWaitingCallerWithoutAnotherNativeRead() = runBlocking {
        withTimeout(5_000) {
            val connection = Connection()
            val session = GameSession { connection }
            try {
                session.attach(12)
                val waiting = async { runCatching { session.readChat(null, 64, 30) } }
                connection.readSignal.await()
                connection.live = false
                session.status()
                assertIs<CancellationException>(waiting.await().exceptionOrNull())
                assertEquals(1, connection.reads)
                assertTrue(connection.closed)
            } finally {
                session.close()
            }
        }
    }
}
