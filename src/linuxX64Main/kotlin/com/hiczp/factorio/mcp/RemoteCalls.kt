@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlinx.cinterop.copy
import kotlinx.cinterop.useContents
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Calls only verified C bootstrap entry points, borrowing a stopped frontend thread's unused stack. */
internal class RemoteCalls(private val trace: ThreadTrace, private val process: ProcessHandle) {
    private data class Call(
        val original: ThreadTrace.Registers,
        val returnAddress: Long,
        val stackAddress: Long,
        val stackWord: ByteArray,
        var stackDirty: Boolean = false,
        var registersDirty: Boolean = false,
        var breakpointOwned: Boolean = false,
        var started: Boolean = false,
        var result: ULong? = null,
    )

    private var active: Call? = null
    private val cleanupMutex = Mutex()
    val pending: Boolean
        get() = active != null

    suspend fun call(function: Long, arguments: List<ULong>): ULong {
        currentCoroutineContext().ensureActive()
        check(active == null && !trace.hasBreakpoint) { "A bootstrap call or breakpoint is already owned" }
        require(trace.stop?.kind == ThreadTrace.StopKind.BREAKPOINT) { "Bootstrap requires a verified userspace execution stop" }
        require(arguments.size <= 6) { "Bootstrap supports at most six integer/pointer arguments" }
        require(
            function > 0 && process.mappings()
                .any { it.readable && it.executable && function in it.start until it.end }) {
            "Bootstrap entry must be in readable executable memory"
        }
        require(!process.shadowStackEnabled()) { "Linux shadow-stack bootstrap is unsupported" }
        val original = trace.registers()
        val oldStack = original.general.useContents {
            require(orig_rax == ULong.MAX_VALUE && eflags and 0x100uL == 0uL) { "Cannot borrow a syscall or single-step context" }
            require(rsp in 4096uL..Long.MAX_VALUE.toULong() && rip in 1uL..Long.MAX_VALUE.toULong())
            rsp.toLong()
        }
        // System V reserves 128 bytes below RSP. The callee enters with RSP % 16 == 8.
        val stackAddress = ((oldStack - 128) and -16L) - 8
        val returnAddress = original.general.useContents { rip.toLong() }
        val record = Call(original, returnAddress, stackAddress, process.readMemory(stackAddress, 8))
        active = record
        val result = withContext(NonCancellable) {
            try {
                record.stackDirty = true
                process.writeData(stackAddress, ByteArray(8) { (returnAddress ushr (8 * it)).toByte() })
                // Record ownership before installation, including a partially installed hardware breakpoint.
                record.breakpointOwned = true
                trace.breakAt(returnAddress)
                val prepared = original.copy(general = original.general.copy {
                    rip = function.toULong()
                    rsp = stackAddress.toULong()
                    rax = 0u
                    orig_rax = ULong.MAX_VALUE
                    rdi = arguments.getOrElse(0) { 0u }
                    rsi = arguments.getOrElse(1) { 0u }
                    rdx = arguments.getOrElse(2) { 0u }
                    rcx = arguments.getOrElse(3) { 0u }
                    r8 = arguments.getOrElse(4) { 0u }
                    r9 = arguments.getOrElse(5) { 0u }
                    // Do not inherit the previous hardware trap's resume flag into the borrowed call.
                    eflags = eflags and (1uL shl 16).inv()
                })
                record.registersDirty = true
                trace.restore(prepared)
                trace.resume()
                record.started = true
                cleanup()
                checkNotNull(record.result)
            } catch (failure: Throwable) {
                try {
                    cleanup()
                } catch (cleanupFailure: Throwable) {
                    failure.addSuppressed(cleanupFailure)
                }
                // A transient inspection/cleanup failure must not discard an already completed native result.
                if (record.result != null && active == null) return@withContext checkNotNull(record.result)
                throw failure
            }
        }
        return result
    }

    /** Idempotent, and deliberately not cancellable once the target has begun executing. */
    suspend fun cleanup() = withContext(NonCancellable) {
        cleanupMutex.withLock { cleanupOwned() }
    }

    suspend fun completePendingCall(): ULong? = withContext(NonCancellable) {
        cleanupMutex.withLock {
            val record = active ?: return@withLock null
            cleanupOwned()
            record.result
        }
    }

    private suspend fun cleanupOwned() {
        val record = active ?: return
        if (record.started && record.result == null) {
            while (record.result == null) {
                val stop = trace.pollStop()
                check(!trace.hasExited) { "Process exited during bootstrap call; it must not be replayed" }
                if (stop == null) {
                    delay(1)
                    continue
                }
                if (stop.kind == ThreadTrace.StopKind.BREAKPOINT) {
                    record.result = trace.registers().general.useContents {
                        check(rip.toLong() == record.returnAddress && rsp.toLong() == record.stackAddress + 8) {
                            "Bootstrap return does not match the owned frame"
                        }
                        rax
                    }
                } else if (stop.kind == ThreadTrace.StopKind.GROUP) {
                    trace.listen()
                } else {
                    trace.resume()
                }
            }
        }
        if (trace.hasExited) {
            active = null
            return
        }
        if (record.stackDirty) {
            process.writeData(record.stackAddress, record.stackWord)
            record.stackDirty = false
        }
        if (record.registersDirty) {
            trace.restore(record.original)
            record.registersDirty = false
        }
        if (record.breakpointOwned) {
            trace.removeBreakpoint()
            record.breakpointOwned = false
        }
        active = null
    }

    fun abandonOnExit() {
        active = null
    }
}
