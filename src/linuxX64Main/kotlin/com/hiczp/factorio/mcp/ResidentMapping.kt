@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.linuxbridge.FmLinuxResidentInfo
import com.hiczp.factorio.mcp.linuxbridge.FmLinuxShared
import com.hiczp.factorio.mcp.linuxbridge.fm_ipc_load
import kotlinx.cinterop.*
import platform.posix.fstat
import platform.posix.memcpy
import platform.posix.stat

/** Validates retained IPC through a typed data export before any reused resident function is invoked. */
internal object ResidentMapping {
    fun open(process: ProcessHandle, residentPath: String): SharedMapping = memScoped {
        val symbol =
            ProcessModules(process).objects(setOf("fm_linux_resident"), residentPath).getValue("fm_linux_resident")
        require(symbol.size == sizeOf<FmLinuxResidentInfo>()) { "Resident bootstrap metadata has an invalid size" }
        val info = alloc<FmLinuxResidentInfo>()
        val bytes = process.readMemory(symbol.address, symbol.size.toInt())
        bytes.usePinned { memcpy(info.ptr, it.addressOf(0), bytes.size.toULong()) }
        require(info.initialized == 1u && info.descriptor >= 0 && info.mapping in 1uL..Long.MAX_VALUE.toULong()) {
            "Retained resident has no initialized IPC; restart Factorio before attaching"
        }
        val address = info.mapping.toLong()
        val region = process.mappings().singleOrNull {
            it.start == address && it.end - it.start >= sizeOf<FmLinuxShared>() && it.offset == 0L &&
                    it.readable && it.writable && !it.executable && it.permissions[3] == 's'
        } ?: error("Resident IPC is not mapped as writable shared data")
        val mapping = SharedMapping.open(process.fileDescriptorPath(info.descriptor), sizeOf<FmLinuxShared>())
        try {
            val storage = alloc<stat>()
            check(fstat(mapping.descriptorNumber, storage.ptr) == 0) { "Cannot identify resident IPC storage" }
            val device = storage.st_dev
            val major = ((device shr 8) and 0xfffu) or ((device shr 32) and 0xfffff000u)
            val minor = (device and 0xffu) or ((device shr 12) and 0xffffff00u)
            require(
                region.deviceMajor.toULong() == major && region.deviceMinor.toULong() == minor &&
                        region.inode.toULong() == storage.st_ino
            ) { "Resident IPC descriptor does not identify its mapped storage" }
            val shared = mapping.memory.reinterpret<FmLinuxShared>().pointed
            require(fm_ipc_load(shared.ptr.reinterpret()) == 1u && shared.process == process.pid.toUInt()) {
                "Resident IPC is uninitialized or belongs to another process"
            }
            mapping
        } catch (failure: Throwable) {
            mapping.close()
            throw failure
        }
    }
}
