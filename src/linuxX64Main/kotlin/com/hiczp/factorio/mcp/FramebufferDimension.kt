package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Two nullable backing objects feeding a nonmutating integer dimension getter's normal return. */
internal data class FramebufferDimension(val primary: Long, val fallback: Long) {
    companion object {
        fun analyze(flow: X64ControlFlow, size: Long): FramebufferDimension {
            require(size in 8..64 * 1024 * 1024)
            val exit = flow.instructions.single { it.operation == Operation.RET && it.offset in flow.reachable }
            val normal = flow.reaching(exit.offset)
            val body = normal.instructions.filter { it.offset in normal.reachable &&
                    it.operation !in setOf(Operation.NOP, Operation.ENDBR) }
            val arguments = SysVArgumentFlow(normal)
            val frame = SysVLocalArgument(normal)
            require(body.size == 11 && body[0].operation == Operation.PUSH && body[0].destination == Register(5, 8) &&
                    body[1].operation == Operation.MOV && body[1].destination == Register(5, 8) &&
                    body[1].source == Register(4, 8) && body[9].operation == Operation.POP &&
                    body[9].destination == Register(5, 8) && frame.registers(exit.offset)[4] == 0L)
            fun pointer(index: Int, condition: Int): Long {
                val load = body[index]
                val test = body[index + 1]
                val branch = body[index + 2]
                val register = load.destination as? Register ?: error("Framebuffer backing is not a pointer register")
                val source = checkNotNull(arguments.source(load.offset))
                require(load.operation == Operation.MOV && register.width == 8 && register.number in listOf(0, 1, 2, 6, 8, 9, 10, 11) &&
                        source.width == 8 && source.reference.argument == 7 && source.reference.offset in 8..size - 8 &&
                        test.operation == Operation.TEST && test.destination == register && test.source == register &&
                        branch.operation == Operation.JCC && branch.condition == condition &&
                        body[8].source is Memory && (body[8].source as Memory).base == register.number)
                require(normal.predecessors[test.offset] == setOf(load.offset) &&
                        normal.predecessors[branch.offset] == setOf(test.offset))
                return source.reference.offset
            }
            val primary = pointer(2, 5)
            val fallback = pointer(5, 4)
            require(primary != fallback && (primary + 8 <= fallback || fallback + 8 <= primary))
            val scalar = body[8]
            val memory = scalar.source as Memory
            require(scalar.operation == Operation.MOV && scalar.destination == Register(0, 4) &&
                    memory.width == 4 && !memory.relative && memory.index == null && memory.displacement in 0..4092 &&
                    body[4].destination == Immediate(scalar.offset) &&
                    (body[7].destination as? Immediate)?.value?.let { it !in normal.reachable } == true &&
                    normal.predecessors[scalar.offset] == setOf(body[4].offset, body[7].offset) &&
                    normal.successors[scalar.offset] == listOf(body[9].offset) &&
                    normal.successors[body[9].offset] == listOf(exit.offset)) {
                "Framebuffer dimension does not return the guarded scalar in EAX"
            }
            return FramebufferDimension(primary, fallback)
        }
    }
}
