@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import platform.posix.*

/** Owns bootstrap scratch resources and uncertain calls until they finish; loaded libraries stay resident. */
internal class LibraryLoader(private val trace: ThreadTrace, private val process: ProcessHandle) {
    data class Library(val path: String, val handle: ULong)

    private enum class Operation { ERRNO, ALLOCATE, LOAD, RELEASE }

    private val functions = ProcessModules(process).functions(
        setOf("__errno_location", "mmap", "munmap", "dlopen"), sharedObjects = setOf("libc.so.6", "libdl.so.2"),
    )
    private val mutex = Mutex()
    private var pending: Operation? = null
    private var path: String? = null
    private var errnoAddress: Long? = null
    private var originalErrno: ByteArray? = null
    private var scratch: ULong? = null
    private var scratchSize = 0
    var library: Library? = null
        private set

    val hasScratchResources: Boolean
        get() = pending != null || scratch != null || originalErrno != null

    suspend fun load(file: String): Library {
        currentCoroutineContext().ensureActive()
        return withContext(NonCancellable) { mutex.withLock { loadOwned(file) } }
    }

    private suspend fun loadOwned(file: String): Library {
        check(path == null && !hasScratchResources && library == null) { "Library bootstrap has already been admitted" }
        require(file.startsWith('/') && '\u0000' !in file) { "Resident library path must be absolute" }
        val encoded = file.encodeToByteArray() + byteArrayOf(0)
        require(encoded.size <= 65536) { "Resident path exceeds bootstrap bound" }
        require(process.mappings().none { it.path == file }) {
            "Resident is already loaded; validate its initialized IPC before reuse"
        }
        MappedBinary(file).use { require(ElfImage(it.view).positionIndependent) { "Expected a position-independent resident ELF" } }
        path = file
        scratchSize = encoded.size
        try {
            invoke(Operation.ERRNO, "__errno_location")
            originalErrno = process.readMemory(checkNotNull(errnoAddress), 4)
            invoke(
                Operation.ALLOCATE, "mmap", listOf(
                    0u, scratchSize.toULong(), (PROT_READ or PROT_WRITE).toULong(),
                    (MAP_PRIVATE or MAP_ANONYMOUS).toULong(), ULong.MAX_VALUE, 0u
                )
            )
            process.writeData(checkNotNull(scratch).toLong(), encoded)
            invoke(Operation.LOAD, "dlopen", listOf(checkNotNull(scratch), RTLD_NOW.toULong()))
            cleanupOwned()
            val result = checkNotNull(library)
            require(process.mappings().any { it.path == result.path && it.executable }) {
                "Loaded resident path does not match the requested library"
            }
            return result
        } catch (failure: Throwable) {
            try {
                cleanupOwned()
            } catch (cleanupFailure: Throwable) {
                failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
    }

    private suspend fun invoke(operation: Operation, name: String, arguments: List<ULong> = emptyList()) {
        check(pending == null)
        pending = operation
        try {
            val result = trace.call(functions.getValue(name).address, arguments)
            pending = null
            accept(operation, result)
        } catch (failure: Throwable) {
            if (!trace.hasPendingCall) pending = null
            throw failure
        }
    }

    private fun accept(operation: Operation, result: ULong) {
        when (operation) {
            Operation.ERRNO -> {
                require(result in 1uL..Long.MAX_VALUE.toULong()) { "Cannot resolve the target thread's errno" }
                errnoAddress = result.toLong()
            }

            Operation.ALLOCATE -> {
                require(result in 1uL..Long.MAX_VALUE.toULong()) { "Cannot allocate resident bootstrap storage" }
                scratch = result
            }

            Operation.LOAD -> {
                require(result != 0uL) { "The native loader rejected the resident library" }
                library = Library(checkNotNull(path), result)
            }

            Operation.RELEASE -> {
                check(result == 0uL) { "Cannot release resident bootstrap storage" }
                scratch = null
            }
        }
    }

    /** Invoke before detaching the borrowed trace. Never replay an uncertain allocation or dlopen. */
    suspend fun cleanup() = withContext(NonCancellable) {
        mutex.withLock { cleanupOwned() }
    }

    private suspend fun cleanupOwned() {
        if (trace.hasExited || !process.alive()) {
            pending = null
            scratch = null
            originalErrno = null
            library = null
            return
        }
        pending?.let { operation ->
            val result = trace.completePendingCall()
            pending = null
            if (result != null) accept(operation, result)
        }
        scratch?.let { invoke(Operation.RELEASE, "munmap", listOf(it, scratchSize.toULong())) }
        originalErrno?.let {
            process.writeData(checkNotNull(errnoAddress), it)
            originalErrno = null
        }
    }
}
