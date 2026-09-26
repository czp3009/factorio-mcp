@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlinx.cinterop.*
import platform.posix.memcpy
import platform.windows.*

/** Maps the selected PDB read-only; only the bounded type and identity streams are copied. */
internal fun readPdbVirtualMethods(
    path: String,
    guid: ByteArray,
    age: Int,
    owner: String,
    signatures: Map<String, UInt>,
    pointerSignatures: Map<String, String> = emptyMap(),
    aggregateSignatures: Map<String, String> = emptyMap(),
    primaryBase: String? = null,
): Map<String, PdbTypeStream.VirtualMethod> = memScoped {
    val file =
        CreateFileW(
            path,
            GENERIC_READ,
            FILE_SHARE_READ.toUInt(),
            null,
            OPEN_EXISTING.toUInt(),
            FILE_ATTRIBUTE_NORMAL.toUInt(),
            null,
        )
    check(file != null && file != INVALID_HANDLE_VALUE) {
        "Cannot open developer PDB: ${GetLastError()}"
    }
    try {
        val length = alloc<LongVar>()
        check(
            GetFileSizeEx(file, length.ptr.reinterpret()) != 0 &&
                    length.value in 56..Int.MAX_VALUE.toLong()
        ) {
            "Unsupported PDB file size"
        }
        val mapping =
            checkNotNull(CreateFileMappingW(file, null, PAGE_READONLY.toUInt(), 0u, 0u, null)) {
                "Cannot map developer PDB"
            }
        try {
            val view =
                checkNotNull(MapViewOfFile(mapping, FILE_MAP_READ.toUInt(), 0u, 0u, 0uL)) {
                    "Cannot view developer PDB"
                }
            try {
                val bytes = view.reinterpret<ByteVar>()
                val streams =
                    PdbStreams(length.value) { offset, count ->
                        ByteArray(count).also { output ->
                            if (count > 0)
                                output.usePinned {
                                    memcpy(it.addressOf(0), bytes + offset.toInt(), count.toULong())
                                }
                        }
                    }
                streams.verifyIdentity(guid, age)
                PdbTypeStream(streams.stream(2, 256 * 1024 * 1024))
                    .methods(owner, signatures, pointerSignatures, aggregateSignatures, primaryBase)
            } finally {
                UnmapViewOfFile(view)
            }
        } finally {
            CloseHandle(mapping)
        }
    } finally {
        CloseHandle(file)
    }
}
