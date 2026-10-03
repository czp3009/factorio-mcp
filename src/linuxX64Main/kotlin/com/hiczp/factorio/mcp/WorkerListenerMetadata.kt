@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.linuxbridge.FmLinuxWorkerCompletionConfig
import platform.posix.PROT_READ
import platform.posix.PROT_WRITE
import platform.posix._SC_PAGESIZE
import platform.posix.sysconf

/** Native worker completion ownership. Live thread binding and GUI-dispatch admission are separate obligations. */
internal data class WorkerListenerMetadata(
    val workerSize: Long,
    val listenerMember: Long,
    val listener: ItaniumType,
    val completion: ItaniumVtable.Method,
    val caller: Long,
    val evidence: ElfEvidence,
) {
    data class Binding(val entry: Long, val original: Long, val protection: Int)

    fun verifyLoaded(image: ElfImage, process: ProcessHandle, bias: Long) =
        evidence.verify(image, bias, process::readMemory)

    fun bind(image: ElfImage, process: ProcessHandle, bias: Long): Binding {
        verifyLoaded(image, process, bias)
        fun address(value: Long): Long {
            require(bias >= 0 && value > 0 && value <= Long.MAX_VALUE - bias)
            return value + bias
        }
        val entry = address(completion.entryAddress)
        val original = address(completion.function.address)
        val pageSize = sysconf(_SC_PAGESIZE)
        require(pageSize > 0 && pageSize and (pageSize - 1) == 0L && entry % 8 == 0L)
        val page = entry and -pageSize
        val mappings = process.executableMappings()
        val region = mappings.singleOrNull {
            it.start <= page && it.end >= page + pageSize && it.readable && !it.executable && it.permissions[3] == 'p'
        } ?: error("Worker listener entry is not private readable data within one mapped page")
        require(mappings.any {
            it.readable && it.executable && original >= it.start && original <= it.end - completion.function.size
        }) { "Worker completion function is not executable" }
        return Binding(entry, original, PROT_READ or if (region.writable) PROT_WRITE else 0)
    }

    fun writeTo(output: FmLinuxWorkerCompletionConfig, binding: Binding, bias: Long) {
        fun address(value: Long): ULong {
            require(bias >= 0 && value > 0 && value <= Long.MAX_VALUE - bias)
            return (value + bias).toULong()
        }
        output.layout.listenerVtable = address(listener.addressPoint)
        output.layout.listenerTypeInfo = address(listener.typeInfo)
        output.layout.caller = address(caller)
        output.layout.workerSize = workerSize.toUInt()
        output.layout.listenerMember = listenerMember.toUInt()
        output.entry = binding.entry.toULong()
        output.original = binding.original.toULong()
        output.protection = binding.protection.toUInt()
    }

    companion object {
        fun resolve(image: ElfImage): WorkerListenerMetadata {
            val pointers = mutableMapOf<Long, Long>()
            val scalars = mutableMapOf<Long, Long>()
            val (resolved, readonly) = image.withReadonlyEvidence {
                image.withFunctionEvidence {
                    val constructor = "_ZN12WorkerThreadC2EP14ThreadListenerPKcm"
                    val size = SysVLineAllocation.resolve(image, "_ZN10ThreadPoolC2EjPKcm", constructor)
                    val member = SysVArgumentMember.resolve(image, constructor, size)
                    val listener = ItaniumType.resolve(image, "N8MainLoop20UpdateThreadListenerE")
                    val completion = ItaniumVtable.resolve(image, "_ZTVN8MainLoop20UpdateThreadListenerE")
                        .method(image, "_ZN8MainLoop20UpdateThreadListener17onThreadAvailableEP12WorkerThread")
                    val loop = image.symbol("_ZN12WorkerThread4loopEv")
                    EhFrames(image).function(loop)
                    EhFrames(image).function(completion.function)
                    val returned = WorkerListenerCall.analyze(
                        image.functionBytes(loop, 8192), loop.address, size, member, completion.slot
                    )
                    scalars[listener.addressPoint - 16] = 0
                    pointers[listener.addressPoint - 8] = listener.typeInfo
                    pointers[listener.typeInfo + 8] = image.pointers.words(listener.typeInfo + 8, 1).single().pointer()
                    pointers[completion.entryAddress] = completion.function.address
                    WorkerListenerMetadata(size, member, listener, completion, loop.address + returned,
                        ElfEvidence(emptyList(), emptyList(), emptyMap(), emptyMap()))
                }
            }
            return resolved.first.copy(evidence = ElfEvidence(resolved.second, readonly, pointers, scalars))
        }
    }
}
