@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.linuxbridge.*
import kotlinx.cinterop.*
import platform.linux.flock
import platform.posix.*

/** Fixed-size shared storage. An attachment lease never proves an outstanding command is idle. */
internal class SharedMapping private constructor(
    private var descriptor: Int,
    val size: Long,
    private var view: COpaquePointer?,
) : AutoCloseable {
    private var leased = false

    val memory: COpaquePointer
        get() = checkNotNull(view) { "Linux IPC mapping is closed" }

    val descriptorNumber: Int
        get() = descriptor.also { check(it >= 0) { "Linux IPC descriptor is closed" } }

    /** No blocking wait: admission and cancellation remain with the Kotlin caller. */
    fun tryAcquireLease(): Boolean {
        check(view != null) { "Linux IPC mapping is closed" }
        if (leased) return true
        if (flock(descriptorNumber, LOCK_EX or LOCK_NB) == 0) {
            leased = true
            return true
        }
        if (errno == EWOULDBLOCK || errno == EAGAIN || errno == EINTR) return false
        error("Cannot acquire Linux attachment lease (errno $errno)")
    }

    fun releaseLease() {
        if (!leased) return
        check(flock(descriptorNumber, LOCK_UN) == 0) { "Cannot release Linux attachment lease (errno $errno)" }
        leased = false
    }

    override fun close() {
        // Keep the lease and descriptor owned if unmapping fails, so cleanup can be retried.
        view?.let {
            check(munmap(it, size.toULong()) == 0) { "Cannot unmap Linux IPC (errno $errno)" }
            view = null
        }
        releaseLease()
        if (descriptor >= 0) {
            val file = descriptor
            descriptor = -1
            // On Linux the descriptor is released even when close reports EINTR.
            check(close(file) == 0 || errno == EINTR) { "Cannot close Linux IPC (errno $errno)" }
        }
    }

    companion object {
        private fun validateSize(size: Long) {
            require(size in 1..(64L * 1024 * 1024)) { "Linux IPC size exceeds supported bounds" }
        }

        fun create(size: Long): SharedMapping {
            validateSize(size)
            val descriptor = memScoped {
                syscall(FM_MEMFD_SYSCALL.toLong(), "factorio-mcp".cstr.getPointer(this), FM_MEMFD_FLAGS).toInt()
            }
            check(descriptor >= 0) { "Cannot create Linux IPC memfd (errno $errno)" }
            try {
                check(ftruncate(descriptor, size) == 0) { "Cannot size Linux IPC (errno $errno)" }
                check(fcntl(descriptor, FM_ADD_SEALS.toInt(), FM_SIZE_SEALS.toInt()) == 0) {
                    "Cannot seal Linux IPC size (errno $errno)"
                }
                return map(descriptor, size)
            } catch (failure: Throwable) {
                close(descriptor)
                throw failure
            }
        }

        /** Reopening a proc fd creates an independent flock owner; dup would share ownership. */
        fun open(path: String, size: Long): SharedMapping {
            validateSize(size)
            require('\u0000' !in path) { "Invalid Linux IPC path" }
            val descriptor = open(path, O_RDWR or O_CLOEXEC)
            check(descriptor >= 0) { "Cannot open resident IPC (errno $errno)" }
            try {
                memScoped {
                    val metadata = alloc<stat>()
                    check(fstat(descriptor, metadata.ptr) == 0) { "Cannot inspect resident IPC (errno $errno)" }
                    require(metadata.st_mode and S_IFMT.toUInt() == S_IFREG.toUInt() && metadata.st_size == size) {
                        "Resident IPC storage size or type is invalid"
                    }
                    val seals = fcntl(descriptor, FM_GET_SEALS.toInt())
                    require(seals >= 0 && seals and FM_SIZE_SEALS.toInt() == FM_SIZE_SEALS.toInt()) {
                        "Resident IPC storage must have a sealed size"
                    }
                }
                return map(descriptor, size)
            } catch (failure: Throwable) {
                close(descriptor)
                throw failure
            }
        }

        private fun map(descriptor: Int, size: Long): SharedMapping {
            val memory = mmap(null, size.toULong(), PROT_READ or PROT_WRITE, MAP_SHARED, descriptor, 0)
            check(memory != null && memory != MAP_FAILED) { "Cannot map Linux IPC (errno $errno)" }
            return SharedMapping(descriptor, size, memory)
        }
    }
}
