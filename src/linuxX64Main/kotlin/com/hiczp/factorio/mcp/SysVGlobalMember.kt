package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Finds the global-context member cleared only when it equals a destructor's original receiver. */
internal object SysVGlobalMember {
    fun resolve(image: ElfImage, destructor: String, global: String, contextSize: Long, receiverSize: Long): Long {
        val function = image.symbol(destructor)
        val root = image.symbol(global)
        require(function.size in 1..8192 && root.type == 1 && root.size == 8L)
        require(image.segments.count {
            it.type == 1L && it.flags and 2L != 0L && root.address >= it.address &&
                    root.address - it.address <= it.memorySize && 8 <= it.memorySize - (root.address - it.address)
        } == 1) {
            "Global pointer is outside a writable ELF load segment"
        }
        EhFrames(image).function(function)
        return analyze(image.functionBytes(function, 8192), function.address, root.address, contextSize, receiverSize)
    }

    fun analyze(bytes: BinaryView, address: Long, global: Long, contextSize: Long, receiverSize: Long): Long {
        require(
            bytes.size in 1..8192 && address >= 0 && address <= Long.MAX_VALUE - bytes.size &&
                    global >= 0 && contextSize >= 8 && receiverSize > 0
        )
        val flow = SysVReceiverFlow(bytes, address, receiverSize, mapOf(global to contextSize))
        val instructions = flow.instructions
        val predecessors = flow.predecessors
        val candidates = instructions.indices.filter { index ->
            if (index + 2 >= instructions.size) false else {
                val compare = instructions[index]
                val branch = instructions[index + 1]
                val clear = instructions[index + 2]
                val memory = (compare.destination as? Memory) ?: (compare.source as? Memory)
                compare.operation == Operation.CMP && memory?.width == 8 && branch.operation == Operation.JCC &&
                        branch.condition == 5 && clear.operation == Operation.MOV && clear.destination == memory &&
                        clear.source == Immediate(0) && branch.destination == Immediate(clear.offset + clear.size) &&
                        predecessors[clear.offset] == setOf(branch.offset) && predecessors[branch.offset] == setOf(
                    compare.offset
                )
            }
        }
        val offsets = candidates.mapNotNull { candidate ->
            val compare = instructions[candidate]
            val registers = flow.before(compare.offset)
            val member = (compare.destination as? Memory) ?: (compare.source as? Memory) ?: return@mapNotNull null
            val receiver = (compare.destination as? Register) ?: (compare.source as? Register) ?: return@mapNotNull null
            if (receiver.width != 8 || registers[receiver.number] != SysVReceiverFlow.Receiver() || member.relative || member.index != null ||
                (member.base?.let { registers[it] } as? SysVReceiverFlow.Global)?.address != global
            ) return@mapNotNull null
            require(member.displacement in 0..contextSize - 8) { "Global receiver field exceeds context bounds" }
            member.displacement
        }.distinct()
        return offsets.singleOrNull() ?: error("Missing or ambiguous receiver-owned global context member")
    }
}
