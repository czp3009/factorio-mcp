package com.hiczp.factorio.mcp

import io.ktor.network.sockets.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.io.IOException

class CioTestConnectionTest {
    private class PosixErrnoException(message: String) : Exception(message)

    private val address = InetSocketAddress("127.0.0.1", 3000)

    private fun unopened(message: String = "POSIX error 0: No error (0)") =
        IOException("Failed to connect to $address.", PosixErrnoException(message))

    @Test
    fun retriesOnlyBeforeSubmission() = runBlocking {
        var attempts = 0
        var submissions = 0
        val result =
            withCioTestConnection(address) {
                attempts++
                if (attempts < 4) throw unopened()
                submissions++
                "submitted"
            }
        assertEquals("submitted", result)
        assertEquals(4, attempts)
        assertEquals(1, submissions)
    }

    @Test
    fun stopsAfterFourFailedConnections() = runBlocking {
        var attempts = 0
        val failure = unopened()
        assertSame(
            failure,
            assertFailsWith<IOException> {
                withCioTestConnection(address) {
                    attempts++
                    throw failure
                }
            },
        )
        assertEquals(4, attempts)
    }

    @Test
    fun neverReplaysAnUncertainSubmittedRequest() = runBlocking {
        var submissions = 0
        val failure = IOException("Connection reset after request submission", unopened().cause)
        assertSame(
            failure,
            assertFailsWith<IOException> {
                withCioTestConnection(address) {
                    submissions++
                    throw failure
                }
            },
        )
        assertEquals(1, submissions)
    }

    @Test
    fun otherConnectFailuresAndAddressesAreTerminal() = runBlocking {
        for (failure in
            listOf(
                unopened("POSIX error 111: Connection refused (111)"),
                IOException("Failed to connect to another address.", unopened().cause),
                IOException(
                    "Failed to connect to $address.",
                    Exception("POSIX error 0: No error (0)"),
                ),
            )) {
            var attempts = 0
            assertSame(
                failure,
                assertFailsWith<IOException> {
                    withCioTestConnection(address) {
                        attempts++
                        throw failure
                    }
                },
            )
            assertEquals(1, attempts)
        }
    }

    @Test
    fun cancellationIsTerminal() = runBlocking {
        var attempts = 0
        assertFailsWith<CancellationException> {
            withCioTestConnection(address) {
                attempts++
                throw CancellationException("cancelled")
            }
        }
        assertEquals(1, attempts)
    }
}
