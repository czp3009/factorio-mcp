package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/**
 * Associates the first original pointer-argument store with an in-bounds receiver member. Never
 * invokes the method.
 */
internal object SysVArgumentMember {
    fun resolve(
        image: ElfImage,
        name: String,
        receiverSize: Long,
        argument: Int = 6,
        member: Long? = null,
    ): Long {
        val entry = image.symbol(name)
        EhFrames(image).function(entry)
        return analyze(
            image.functionBytes(entry, 512),
            entry.address,
            receiverSize,
            argument,
            member,
        )
    }

    fun analyze(
        bytes: BinaryView,
        address: Long,
        receiverSize: Long,
        argument: Int = 6,
        member: Long? = null,
    ): Long {
        require(
            bytes.size in 1..4096 &&
                address >= 0 &&
                address <= Long.MAX_VALUE - bytes.size &&
                receiverSize >= 8 &&
                argument in listOf(6, 2, 1, 8, 9)
        )
        val decoder = X64Instructions(bytes)
        var position = 0L
        repeat(64) {
            require(position < bytes.size && position < 512)
            val instruction = decoder.decode(position)
            position += instruction.size
            require(instruction.operation != Operation.RET) {
                "Pointer member was not assigned in the entry prefix"
            }
            val target = instruction.destination as? Memory
            val source = instruction.source as? Register
            if (
                instruction.operation == Operation.MOV &&
                    target != null &&
                    source != null &&
                    source.width == 8 &&
                    target.width == 8 &&
                    !target.relative &&
                    target.index == null
            ) {
                val flow = X64ControlFlow(X64Instructions(bytes.slice(0, position)).all())
                if (instruction.offset in flow.reachable) {
                    SysVLocalArgument(flow)
                    val arguments = SysVArgumentFlow(flow)
                    val owner = arguments.memory(instruction.offset, target)?.reference
                    if (
                        owner?.argument == 7 &&
                            arguments.register(instruction.offset, source.number) ==
                                SysVArgumentFlow.Reference(argument)
                    ) {
                        val offset = owner.offset
                        require(offset in 0..receiverSize - 8 && offset % 8 == 0L)
                        if (member == null || member == offset) return offset
                    }
                }
            }
        }
        error("Pointer argument assignment exceeds prefix bound")
    }
}
