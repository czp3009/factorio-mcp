@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlinx.cinterop.*
import platform.posix.*

/** The mapping owns the bytes; views must not outlive it. No executable mappings are created here. */
internal class MappedBinary(path: String) : AutoCloseable {
    private var mapping: COpaquePointer? = null
    private var length = 0L
    val view: BinaryView

    init {
        val descriptor = open(path, O_RDONLY or O_CLOEXEC)
        check(descriptor >= 0) { "Cannot open ELF image: $path (errno $errno)" }
        try {
            memScoped {
                val info = alloc<stat>()
                check(fstat(descriptor, info.ptr) == 0) { "Cannot stat ELF image (errno $errno)" }
                require(info.st_mode and S_IFMT.toUInt() == S_IFREG.toUInt()) { "ELF image is not a regular file" }
                length = info.st_size
                require(length in 64..Int.MAX_VALUE.toLong()) { "ELF image size exceeds supported bounds" }
                val address = mmap(null, length.toULong(), PROT_READ, MAP_PRIVATE, descriptor, 0)
                check(address != MAP_FAILED && address != null) { "Cannot map ELF image (errno $errno)" }
                mapping = address
            }
        } finally {
            close(descriptor)
        }
        view = BinaryView(length, { offset ->
            checkNotNull(mapping) { "ELF mapping is closed" }.reinterpret<ByteVar>()[offset]
        }, { offset, count ->
            val address = checkNotNull(mapping) { "ELF mapping is closed" }.reinterpret<ByteVar>()
            if (count == 0) ByteArray(0) else checkNotNull(address + offset).readBytes(count)
        })
    }

    override fun close() {
        val address = mapping ?: return
        check(munmap(address, length.toULong()) == 0) { "Cannot release ELF mapping (errno $errno)" }
        mapping = null
    }
}
