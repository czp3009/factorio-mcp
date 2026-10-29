@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlinx.cinterop.CValue
import kotlinx.cinterop.useContents
import platform.linux.user_regs_struct
import platform.posix._SC_PAGESIZE
import platform.posix.sysconf

/** Reads an interrupted thread's active CFI chain from matching files or the kernel's mapped vDSO. */
internal class ProcessStack(private val process: ProcessHandle) {
    private fun <T> withImage(mapping: ProcMapping, action: (ElfImage) -> T): T {
        if (mapping.path != "[vdso]") return process.withMappedFile(mapping, action)
        val size = mapping.end - mapping.start
        require(mapping.inode == 0L && mapping.deviceMajor == 0L && mapping.deviceMinor == 0L && mapping.offset == 0L)
        require(mapping.readable && mapping.executable && !mapping.writable && size in 1..65536) {
            "Kernel vDSO mapping is not bounded read-only code"
        }
        // [vdso] is a kernel-provided maps identity, not an ordinary file or an anonymous VMA name.
        // Its ELF bytes exist only in this process. Require identical file/memory load offsets before
        // using the bounded mapped copy with the ordinary ELF/CFI reader.
        val image = ElfImage(BinaryView(process.readMemory(mapping.start, size.toInt())))
        require(image.positionIndependent && image.segments.filter { it.type == 1L }.all {
            it.address == it.offset && it.address <= size && it.memorySize <= size - it.address && it.flags and 2L == 0L
        }) { "Kernel vDSO load segments do not match its mapped ELF bytes" }
        return action(image)
    }

    fun contains(registers: CValue<user_regs_struct>, ranges: List<LongRange>): Boolean {
        require(ranges.isNotEmpty() && ranges.size <= 4096)
        val mappings = process.mappings()
        var values = registers.useContents {
            listOf(rax, rdx, rcx, rbx, rsi, rdi, rbp, rsp, r8, r9, r10, r11, r12, r13, r14, r15, rip)
                .mapIndexed { index, value -> index to value.toLong() }.toMap()
        }
        val stack = mappings.singleOrNull {
            it.readable && it.writable && !it.executable && values.getValue(7) in it.start until it.end
        } ?: error("Interrupted stack is not readable writable memory")
        require(stack.end - values.getValue(7) <= 16 * 1024 * 1024) { "Interrupted stack exceeds bound" }
        val groups = mappings.filter { it.inode != 0L && it.path?.startsWith('/') == true }
            .groupBy { Triple(it.deviceMajor, it.deviceMinor, it.inode) }
        val pageSize = sysconf(_SC_PAGESIZE)
        var returnAddress = false
        var depth = 0
        var previousAddress: Long? = null
        var previousModule: String? = null
        fun pc(): Long {
            val address = values[16] ?: return 0
            require(address >= 0) { "Invalid unwind instruction address" }
            return if (returnAddress && address != 0L) address - 1 else address
        }

        fun read(address: Long): Long {
            require(address >= stack.start && address <= stack.end - 8 && address % 8 == 0L) {
                "Unwind memory rule escapes the interrupted stack"
            }
            return BinaryView(process.readMemory(address, 8)).unsigned(0, 8)
        }
        while (pc() != 0L) {
            if (ranges.any { pc() in it }) return true
            require(depth < 128) { "Interrupted call chain exceeds bound" }
            val current = mappings.singleOrNull { it.executable && pc() in it.start until it.end }
                ?: error("Unwind instruction address is not mapped executable code")
            val group = groups[Triple(current.deviceMajor, current.deviceMinor, current.inode)]
                ?: listOf(current).takeIf { current.path == "[vdso]" }
                ?: error(
                    "Unwind instruction address 0x${pc().toString(16)} has no matching ELF file " +
                        "(mapping 0x${current.start.toString(16)}-0x${current.end.toString(16)}, " +
                        "path=${current.path}, depth=$depth, " +
                        "previous=${previousAddress?.let { "0x${it.toString(16)}" }} in $previousModule)"
                )
            var found = false
            withImage(current) { image ->
                val bias = image.loadBias(group, pageSize)
                val frames = image.unwindFrames
                val starts = frames.keys.sorted()
                while (pc() != 0L && group.any { it.executable && pc() in it.start until it.end }) {
                    if (ranges.any { pc() in it }) {
                        found = true
                        return@withImage
                    }
                    require(++depth <= 128) { "Interrupted call chain exceeds bound" }
                    val address = pc() - bias
                    val index = starts.binarySearch(address).let { if (it < 0) -it - 2 else it }
                    require(index >= 0) { "No unwind range covers the interrupted function" }
                    val frame = frames[starts[index]] ?: error("Ambiguous interrupted unwind range")
                    require(address in frame.start until frame.end) { "Interrupted function lacks unwind coverage" }
                    require(index == starts.lastIndex || frame.end <= starts[index + 1]) {
                        "Overlapping interrupted unwind ranges"
                    }
                    previousAddress = pc()
                    previousModule = current.path
                    values = DwarfCallFrame.caller(frame, address, values, ::read)
                    require(values.getValue(7) <= stack.end) { "Unwind CFA escapes the interrupted stack" }
                    returnAddress = true
                }
            }
            if (found) return true
        }
        return false
    }
}
