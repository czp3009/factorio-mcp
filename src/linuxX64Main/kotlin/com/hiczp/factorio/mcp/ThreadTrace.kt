@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.linuxbridge.*
import kotlinx.cinterop.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import platform.linux.NT_X86_XSTATE
import platform.linux.user_regs_struct
import platform.posix.*

// Linux UAPI TRAP_HWBKPT is absent from the bundled glibc headers.
private const val HARDWARE_TRAP_CODE = 4

/** A thread-affine ptrace attachment. The owner retains this object until close succeeds. */
internal class ThreadTrace(private val tid: Int) {
    enum class StopKind { INTERRUPT, SIGNAL, GROUP, BREAKPOINT }

    data class Stop(val kind: StopKind, val signal: Int, val code: Int = 0)

    data class Registers(val general: CValue<user_regs_struct>, val extended: ByteArray)

    private val owner = pthread_self()
    private val process = ProcessHandle(tid)
    private var attached = false
    private var interruptRequested = false
    private var closed = false
    private var exited = false
    private var breakpoint: Breakpoint? = null
    private val remoteCalls = RemoteCalls(this, process)
    var stop: Stop? = null
        private set

    val isAttached: Boolean
        get() = attached

    val hasExited: Boolean
        get() = exited

    val hasBreakpoint: Boolean
        get() = breakpoint != null

    val hasPendingCall: Boolean
        get() = remoteCalls.pending

    suspend fun call(function: Long, arguments: List<ULong> = emptyList()): ULong {
        checkStopped()
        return remoteCalls.call(function, arguments)
    }

    suspend fun completePendingCall(): ULong? {
        checkOwner()
        return remoteCalls.completePendingCall()
    }

    private data class Breakpoint(
        val slot: Int,
        val address: Long,
        val oldAddress: Long,
        val oldControl: Long,
        val oldStatus: Long,
        val control: Long,
        var addressWritten: Boolean = false,
        var statusWritten: Boolean = false,
        var controlWritten: Boolean = false,
    )

    private fun checkOwner() {
        check(pthread_self() == owner) { "Linux tracing must remain on its owning thread" }
        check(!closed) { "Linux trace is closed" }
    }

    fun seize() {
        checkOwner()
        check(!attached && !exited) { "Linux thread is already traced or exited" }
        check(process.alive()) { "Linux target thread has exited" }
        check(ptrace(PTRACE_SEIZE.toUInt(), tid, null, null) == 0L) {
            "Cannot trace process $tid; Linux ptrace permission is required (errno $errno)"
        }
        attached = true
        // A pinned proc handle must still identify the same live thread before any register access.
        check(process.alive()) { "Linux target thread exited during trace attachment" }
    }

    /** Poll only an admitted trace operation, never background process liveness. */
    fun pollStop(): Stop? {
        checkOwner()
        if (exited) return null
        check(attached) { "Linux thread has not been seized" }
        return memScoped {
            val status = alloc<IntVar>()
            val result = waitpid(tid, status.ptr, WNOHANG or __WALL)
            if (result < 0) {
                if (errno == EINTR) return@memScoped null
                if (errno == ECHILD && !process.alive()) {
                    markExited()
                    return@memScoped null
                }
                error("Cannot wait for traced thread $tid (errno $errno)")
            }
            if (result == 0) return@memScoped stop
            check(result == tid) { "Unexpected traced thread wait result" }
            if (fm_wait_exited(status.value) != 0 || fm_wait_signaled(status.value) != 0) {
                markExited()
                return@memScoped null
            }
            check(fm_wait_stopped(status.value) != 0) { "Unexpected traced thread state" }
            val signal = fm_wait_stop_signal(status.value)
            val event = status.value ushr 16
            val reason = if (event == FM_TRACE_EVENT_STOP.toInt()) {
                if (signal == SIGTRAP) Stop(StopKind.INTERRUPT, 0)
                else {
                    check(signal in setOf(SIGSTOP, SIGTSTP, SIGTTIN, SIGTTOU)) { "Unexpected Linux group stop" }
                    Stop(StopKind.GROUP, signal)
                }
            } else {
                check(event == 0) { "Unsupported Linux trace event: $event" }
                val information = alloc<siginfo_t>()
                check(ptrace(PTRACE_GETSIGINFO, tid, null, information.ptr) == 0L) {
                    "Cannot inspect traced signal (errno $errno)"
                }
                check(information.si_signo == signal) { "Traced signal metadata mismatch" }
                Stop(StopKind.SIGNAL, signal, information.si_code)
            }
            stop = reason
            val breakpoint = breakpoint
            if (reason.kind == StopKind.SIGNAL && reason.signal == SIGTRAP && reason.code == HARDWARE_TRAP_CODE &&
                breakpoint?.controlWritten == true && debugRegister(6) and 15L == (1L shl breakpoint.slot)
            ) {
                val registers = alloc<user_regs_struct>()
                check(ptrace(PTRACE_GETREGS, tid, null, registers.ptr) == 0L) {
                    "Cannot inspect hardware breakpoint registers (errno $errno)"
                }
                if (registers.rip == breakpoint.address.toULong())
                    stop = Stop(StopKind.BREAKPOINT, 0, reason.code)
            }
            interruptRequested = false
            stop
        }
    }

    suspend fun interrupt(): Stop? {
        checkOwner()
        if (exited) return null
        check(attached) { "Linux thread has not been seized" }
        pollStop()?.let { return it }
        if (!exited && !interruptRequested) {
            if (ptrace(PTRACE_INTERRUPT.toUInt(), tid, null, null) != 0L) {
                if (errno == ESRCH && !process.alive()) {
                    awaitTerminalExit()
                    return null
                }
                error("Cannot interrupt traced thread $tid (errno $errno)")
            }
            interruptRequested = true
        }
        while (!exited) {
            pollStop()?.let { return it }
            if (!exited) delay(1)
        }
        return null
    }

    fun registers(): Registers {
        checkStopped()
        return memScoped {
            val general = alloc<user_regs_struct>()
            check(ptrace(PTRACE_GETREGS, tid, null, general.ptr) == 0L) { "Cannot read Linux registers (errno $errno)" }
            val extended = ByteArray(65536)
            val vector = alloc<iovec>()
            extended.usePinned {
                vector.iov_base = it.addressOf(0)
                vector.iov_len = extended.size.toULong()
                check(
                    ptrace(
                        PTRACE_GETREGSET.toUInt(),
                        tid,
                        NT_X86_XSTATE.toLong().toCPointer<ByteVar>(),
                        vector.ptr
                    ) == 0L
                ) {
                    "Cannot preserve Linux extended registers (errno $errno)"
                }
            }
            require(vector.iov_len in 512uL..extended.size.toULong()) { "Unsupported Linux extended register size" }
            Registers(general.readValue(), extended.copyOf(vector.iov_len.toInt()))
        }
    }

    /** Exact snapshots include AVX/XSTATE as well as integer registers. */
    fun restore(registers: Registers) {
        checkStopped()
        require(registers.extended.size in 512..65536)
        memScoped {
            val vector = alloc<iovec>()
            registers.extended.usePinned {
                vector.iov_base = it.addressOf(0)
                vector.iov_len = registers.extended.size.toULong()
                check(
                    ptrace(
                        PTRACE_SETREGSET.toUInt(),
                        tid,
                        NT_X86_XSTATE.toLong().toCPointer<ByteVar>(),
                        vector.ptr
                    ) == 0L
                ) {
                    "Cannot restore Linux extended registers (errno $errno)"
                }
            }
            val general = registers.general.getPointer(this)
            check(ptrace(PTRACE_SETREGS, tid, null, general) == 0L) { "Cannot restore Linux registers (errno $errno)" }
        }
    }

    fun resume() {
        checkStopped()
        val reason = checkNotNull(stop)
        check(reason.kind != StopKind.GROUP) { "Job-control stops must be preserved until detach" }
        val signal = if (reason.kind == StopKind.SIGNAL) reason.signal else 0
        check(ptrace(PTRACE_CONT, tid, null, signal.toLong().toCPointer<ByteVar>()) == 0L) {
            "Cannot resume traced thread $tid (errno $errno)"
        }
        stop = null
    }

    /** Preserve an external group stop while an admitted bootstrap call awaits SIGCONT. */
    fun listen() {
        checkStopped()
        check(stop?.kind == StopKind.GROUP) { "PTRACE_LISTEN requires a group stop" }
        check(
            ptrace(
                PTRACE_LISTEN.toUInt(),
                tid,
                null,
                null
            ) == 0L
        ) { "Cannot listen for Linux thread continuation (errno $errno)" }
        stop = null
    }

    /** Installs one temporary execution breakpoint without changing target instructions. */
    fun breakAt(address: Long) {
        checkStopped()
        check(breakpoint == null) { "A Linux hardware breakpoint is already owned" }
        require(address > 0 && process.mappings().any {
            it.readable && it.executable && address >= it.start && address < it.end
        }) { "Hardware breakpoint must be inside a readable executable mapping" }
        val oldControl = debugRegister(7)
        val slot = (0..3).firstOrNull { oldControl and (3L shl (it * 2)) == 0L }
            ?: error("No unused Linux hardware breakpoint slot")
        val conditionMask = 15L shl (16 + slot * 4)
        val control = (oldControl and conditionMask.inv()) or (1L shl (slot * 2))
        val record = Breakpoint(slot, address, debugRegister(slot), oldControl, debugRegister(6), control)
        // Keep ownership before the first write, including partially installed breakpoints.
        breakpoint = record
        writeDebugRegister(slot, address)
        record.addressWritten = true
        // DR6 breakpoint flags are sticky; stale flags must not masquerade as a simultaneous trap.
        writeDebugRegister(6, record.oldStatus and 15L.inv())
        record.statusWritten = true
        writeDebugRegister(7, control)
        record.controlWritten = true
    }

    fun removeBreakpoint() {
        checkStopped()
        val record = breakpoint ?: return
        if (record.controlWritten) {
            check(debugRegister(7) == record.control) { "Hardware breakpoint control is no longer owned" }
            writeDebugRegister(7, record.oldControl)
            record.controlWritten = false
        }
        if (record.addressWritten) {
            check(debugRegister(record.slot) == record.address) { "Hardware breakpoint address is no longer owned" }
            writeDebugRegister(record.slot, record.oldAddress)
            record.addressWritten = false
        }
        if (record.statusWritten) writeDebugRegister(6, record.oldStatus)
        breakpoint = null
    }

    private fun debugOffset(index: Int): COpaquePointer? {
        require(index in 0..3 || index == 6 || index == 7)
        return (FM_DEBUG_REGISTERS_OFFSET.toLong() + index * FM_DEBUG_REGISTER_WIDTH.toLong()).toCPointer<ByteVar>()
    }

    private fun debugRegister(index: Int): Long {
        checkStopped()
        set_posix_errno(0)
        val result = ptrace(PTRACE_PEEKUSER, tid, debugOffset(index), null)
        check(result != -1L || errno == 0) { "Cannot read Linux hardware breakpoint register (errno $errno)" }
        return result
    }

    private fun writeDebugRegister(index: Int, value: Long) {
        checkStopped()
        check(ptrace(PTRACE_POKEUSER, tid, debugOffset(index), value.toCPointer<ByteVar>()) == 0L) {
            "Cannot write Linux hardware breakpoint register (errno $errno)"
        }
    }

    private fun checkStopped() {
        checkOwner()
        check(attached && !exited && stop != null) { "Linux register access requires a stopped trace" }
    }

    private fun markExited() {
        exited = true
        attached = false
        stop = null
        interruptRequested = false
        breakpoint = null
        remoteCalls.abandonOnExit()
    }

    private suspend fun awaitTerminalExit() {
        while (!exited) {
            pollStop()
            if (!exited) delay(1)
        }
    }

    suspend fun close() = withContext(NonCancellable) {
        if (closed) return@withContext
        checkOwner()
        if (attached) {
            try {
                // A borrowed call must return before restoring the original frame or detaching.
                remoteCalls.cleanup()
                interrupt()
                if (attached) {
                    removeBreakpoint()
                    val reason = checkNotNull(stop)
                    val signal =
                        if (reason.kind == StopKind.SIGNAL || reason.kind == StopKind.GROUP) reason.signal else 0
                    check(ptrace(PTRACE_DETACH, tid, null, signal.toLong().toCPointer<ByteVar>()) == 0L) {
                        "Cannot detach Linux trace $tid (errno $errno)"
                    }
                    attached = false
                    stop = null
                }
            } catch (failure: Throwable) {
                // SIGKILL can win after a stop was collected, including during debug-register cleanup.
                // Drain the kernel's terminal notification; there is no resident work left to await.
                val dead = try {
                    !process.alive()
                } catch (livenessFailure: Throwable) {
                    failure.addSuppressed(livenessFailure)
                    false
                }
                if (!dead) throw failure
                awaitTerminalExit()
            }
        }
        process.close()
        closed = true
    }
}
