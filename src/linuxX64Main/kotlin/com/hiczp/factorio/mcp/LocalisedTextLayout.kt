package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.SysVReceiverFlow.Pointer
import com.hiczp.factorio.mcp.SysVReceiverFlow.Receiver
import com.hiczp.factorio.mcp.X64Instructions.*

/** Cached string placement from native clearing operations; never invokes translation or clearing. */
internal data class LocalisedTextLayout(val size: Long, val cached: Long) {
    companion object {
        private const val CLEAR = "_ZN15LocalisedString23clearTranslationResultsEv"

        fun resolve(image: ElfImage, string: NativeStringLayout): LocalisedTextLayout {
            val size = VectorElementSize.resolve(image, CLEAR, CLEAR)
            val entry = image.symbol(CLEAR)
            return analyze(image.functionBytes(entry, 4096), entry.address, size, string)
        }

        fun analyze(bytes: BinaryView, address: Long, size: Long, string: NativeStringLayout): LocalisedTextLayout {
            require(
                size in 8..4096 && string.size in 1..size && string.data in 0..string.size - 8 &&
                        string.length in 0..string.size - 8 && string.data != string.length
            )
            val flow = SysVReceiverFlow(bytes, address, size)
            val lengths = mutableListOf<Long>()
            val buffers = mutableListOf<Long>()
            for (instruction in flow.instructions.filter {
                it.offset in flow.reachable && it.operation == Operation.MOV &&
                        it.source == Immediate(0) && it.destination is Memory
            }) {
                val memory = instruction.destination as Memory
                require(!memory.relative && memory.index == null && memory.base != null)
                val base = flow.before(instruction.offset)[memory.base]
                when {
                    memory.width == 8 && base is Receiver -> lengths += base.adjustment + memory.displacement
                    memory.width == 1 && base is Pointer && base.base == Receiver() && memory.displacement == 0L ->
                        buffers += base.offset
                }
            }
            val cached = lengths.singleOrNull()?.minus(string.length)
                ?: error("Translation clearing lacks a unique string length reset")
            require(
                cached in 0..size - string.size && cached % 8 == 0L &&
                        buffers.singleOrNull() == cached + string.data
            ) {
                "Translation clearing does not reset the same bounded cached string buffer"
            }
            return LocalisedTextLayout(size, cached)
        }
    }
}
