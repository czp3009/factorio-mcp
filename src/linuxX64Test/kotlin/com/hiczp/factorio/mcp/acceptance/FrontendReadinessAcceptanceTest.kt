@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlinx.coroutines.DelicateCoroutinesApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package com.hiczp.factorio.mcp.acceptance

import com.hiczp.factorio.mcp.FrontendReadiness
import com.hiczp.factorio.mcp.ProcessHandle
import com.hiczp.factorio.mcp.ThreadTrace
import com.hiczp.factorio.mcp.loadBias
import kotlinx.cinterop.toKString
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import platform.posix._SC_PAGESIZE
import platform.posix.getenv
import platform.posix.sysconf
import kotlin.test.Test
import kotlin.time.TimeSource

/** Explicit initialized-client stack observation. No resident injection or game calls. */
class FrontendReadinessAcceptanceTest {
    @Test
    fun verifiesTheActiveMainLoopAndReleasesTheTrace() = runBlocking {
        val pid = checkNotNull(getenv("FACTORIO_MCP_TEST_PID")).toKString().toInt()
        newSingleThreadContext("factorio-mcp-readiness-acceptance").use { dispatcher ->
            withContext(dispatcher) {
                ProcessHandle(pid).use { process ->
                    val bias =
                        process.withExecutable { it.loadBias(process.executableMappings(), sysconf(_SC_PAGESIZE)) }
                    val metadataStart = TimeSource.Monotonic.markNow()
                    val readiness = FrontendReadiness.resolve(process, bias)
                    println("Frontend readiness metadata: ${metadataStart.elapsedNow()}")
                    val trace = ThreadTrace(pid)
                    try {
                        trace.seize()
                        trace.interrupt()
                        val stackStart = TimeSource.Monotonic.markNow()
                        readiness.requireRunning(trace)
                        println("Interrupted stack verification: ${stackStart.elapsedNow()}")
                        println("Verified active main-loop step through matching ELF unwind and inline metadata")
                    } finally {
                        trace.close()
                    }
                }
            }
        }
    }
}
