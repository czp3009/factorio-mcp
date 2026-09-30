package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Original source-string reads and embedded inline storage. Allocation/copy/destruction remain separate proofs. */
internal object ChatActionPayload {
    fun resolve(image: ElfImage, actionSize: Long, type: Long, string: NativeStringLayout): Long {
        val entry =
            image.symbol("_ZN11InputActionC2E15InputActionTypeRKNSt7__cxx1112basic_stringIcSt11char_traitsIcESaIcEEE")
        EhFrames(image).function(entry)
        return analyze(X64ControlFlow.resolve(image, entry), actionSize, type, string)
    }

    fun analyze(flow: X64ControlFlow, actionSize: Long, type: Long, string: NativeStringLayout): Long {
        require(
            actionSize in 2..4096 && type in 0..actionSize - 2 && string.size in 1..actionSize &&
                    string.data in 0..string.size - 8 && string.length in 0..string.size - 8 &&
                    string.local in 0 until string.size && string.data != string.length
        )
        val arguments = SysVArgumentFlow(flow)
        val reads = mutableListOf<Pair<Long, SysVArgumentFlow.Read>>()
        val initializations = mutableListOf<Pair<Long, Long>>()
        for (instruction in flow.instructions.filter { it.offset in flow.reachable }) {
            val source = arguments.source(instruction.offset)
            if (source?.reference?.argument == 2 && instruction.operation != Operation.LEA) {
                require(
                    instruction.operation == Operation.MOV && source.width == 8 &&
                            (instruction.destination as? Register)?.width == 8
                ) {
                    "Action constructor reads its source string with an unsupported width or operation"
                }
                reads += instruction.offset to source
            }
            val target = instruction.destination as? Memory ?: continue
            val member = arguments.memory(instruction.offset, target) ?: continue
            require(member.reference.argument != 2) {
                "Action constructor uses its source string as an additional memory operand"
            }
            if (instruction.operation != Operation.MOV || member.reference.argument != 7 || member.width != 8) continue
            val value = instruction.source as? Register ?: continue
            if (value.width != 8) continue
            val original = arguments.register(instruction.offset, value.number) ?: continue
            val offset = member.reference.offset - string.data
            if (original.argument == 7 && original.offset == offset + string.local) {
                require(
                    offset in 0..actionSize - string.size &&
                            (offset + string.size <= type || offset >= type + 2)
                ) {
                    "Embedded action string overlaps its discriminator or exceeds action storage"
                }
                initializations += instruction.offset to offset
            }
        }
        require(
            reads.size == 2 && reads.map { it.second }.toSet() == setOf(
                SysVArgumentFlow.Read(SysVArgumentFlow.Reference(2, string.data), 8),
                SysVArgumentFlow.Read(SysVArgumentFlow.Reference(2, string.length), 8)
            )
        ) {
            "Action constructor does not read exactly the original source string data and length"
        }
        val (initialization, offset) = initializations.singleOrNull()
            ?: error("Action constructor has no unique embedded inline string initialization")
        val pending = ArrayDeque<Long>()
        val visited = mutableSetOf<Long>()
        pending.add(0)
        while (pending.isNotEmpty()) {
            val at = pending.removeFirst()
            if (at == initialization || !visited.add(at)) continue
            require(reads.none { it.first == at }) { "Source string reads can bypass destination initialization" }
            pending.addAll(flow.successors.getValue(at))
        }
        return offset
    }
}
