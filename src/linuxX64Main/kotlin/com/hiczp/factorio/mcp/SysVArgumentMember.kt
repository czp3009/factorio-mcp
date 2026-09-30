package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.SysVReceiverFlow.Original
import com.hiczp.factorio.mcp.SysVReceiverFlow.Receiver
import com.hiczp.factorio.mcp.X64Instructions.*

/** Associates the first original pointer-argument store with an in-bounds receiver member. Never invokes the method. */
internal object SysVArgumentMember {
    fun resolve(image: ElfImage, name: String, receiverSize: Long, argument: Int = 6): Long {
        val entry = image.symbol(name)
        EhFrames(image).function(entry)
        return analyze(image.functionBytes(entry, 512), entry.address, receiverSize, argument)
    }

    fun analyze(bytes: BinaryView, address: Long, receiverSize: Long, argument: Int = 6): Long {
        require(
            bytes.size in 1..4096 && address >= 0 && address <= Long.MAX_VALUE - bytes.size && receiverSize >= 8 &&
                    argument in listOf(6, 2, 1, 8, 9)
        )
        val decoder = X64Instructions(bytes)
        var position = 0L
        repeat(64) {
            require(position < bytes.size && position < 512)
            val instruction = decoder.decode(position)
            position += instruction.size
            require(instruction.operation != Operation.RET) { "Pointer member was not assigned in the entry prefix" }
            val target = instruction.destination as? Memory
            val source = instruction.source as? Register
            if (instruction.operation == Operation.MOV && target != null && source != null && source.width == 8 &&
                target.width == 8 && !target.relative && target.index == null
            ) {
                val flow = SysVReceiverFlow(bytes.slice(0, position), address, receiverSize)
                if (instruction.offset in flow.reachable) {
                    val registers = flow.before(instruction.offset)
                    val owner = target.base?.let { registers[it] } as? Receiver
                    if (owner != null && registers[source.number] == Original(argument)) {
                        val offset = owner.adjustment + target.displacement
                        require(offset in 0..receiverSize - 8 && offset % 8 == 0L)
                        return offset
                    }
                }
            }
        }
        error("Pointer argument assignment exceeds prefix bound")
    }
}
