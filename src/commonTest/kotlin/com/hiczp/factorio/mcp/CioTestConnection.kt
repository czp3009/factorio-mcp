package com.hiczp.factorio.mcp

import io.ktor.network.sockets.InetSocketAddress
import kotlinx.io.IOException

/**
 * Ktor's native TCP connect can report socket error zero on Windows. Its ConnectUtilsNative
 * IOException is thrown before Endpoint writes any HTTP bytes. Match only that failure; response,
 * reset, timeout and cancellation failures remain terminal.
 */
internal suspend fun <T> withCioTestConnection(
    address: InetSocketAddress,
    connectAndSend: suspend () -> T,
): T {
    repeat(4) { attempt ->
        try {
            return connectAndSend()
        } catch (failure: IOException) {
            val socketError = failure.cause
            // PosixException is native-only; commonTest deliberately matches its narrow,
            // pinned-library diagnostic instead of adding platform engine adapters.
            val unopened =
                failure.message == "Failed to connect to $address." &&
                    socketError?.let {
                        it::class.simpleName == "PosixErrnoException" &&
                            it.message?.startsWith("POSIX error 0:") == true
                    } == true
            if (!unopened || attempt == 3) throw failure
            println(
                "factorio-mcp acceptance: CIO TCP connect reported error zero; retrying before HTTP submission"
            )
        }
    }
    error("Unreachable connection attempt")
}
