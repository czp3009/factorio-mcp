package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** The initial bound comparison and first accepted input store of a named range setter. Never executes it. */
internal data class SliderRangeSetter(val field: Long, val opposite: Long) {
    companion object {
        fun resolve(image: ElfImage, name: String, size: Long, rejectedCondition: Int): SliderRangeSetter {
            val function = image.symbol(name)
            EhFrames(image).function(function)
            return analyze(image.functionBytes(function, 4096), size, rejectedCondition)
        }

        fun analyze(bytes: BinaryView, size: Long, rejectedCondition: Int): SliderRangeSetter {
            require(rejectedCondition in setOf(2, 7))
            val flow = X64ControlFlow(X64Instructions(bytes).all())
            val arguments = SysVArgumentFlow(flow)
            val store =
                flow.instructions.firstOrNull { it.operation == Operation.SCALAR_MOV && it.destination is Memory }
                    ?: error("Range setter has no scalar store")
            require(store.offset in flow.reachable && store.source == Register(16, 8))
            fun member(at: Long, operand: X64Instructions.Operand?): Long {
                val memory = operand as? Memory ?: error("Range setter does not access a member")
                val source = arguments.memory(at, memory) ?: error("Range setter lost its receiver")
                require(source.width == 8 && source.reference.argument == 7)
                return NativeAccessor(source.reference.offset, 8, ULong.MAX_VALUE, 0).withinObject(size).offset
            }

            val target = member(store.offset, store.destination)
            val prefix = flow.instructions.takeWhile { it.offset < store.offset }
            val compare = prefix.single { it.operation == Operation.SCALAR_COMPARE }
            val loads = prefix.filter { it.operation == Operation.SCALAR_MOV }
            val reversed = compare.source == Register(16, 8)
            val opposite = if (reversed) {
                val load = loads.single()
                require(
                    load.destination == compare.destination && load.destination is Register &&
                            load.destination != Register(16, 8) && load.destination.width == 8 &&
                            load.destination.number in 17..31 && load.offset < compare.offset
                )
                member(load.offset, load.source)
            } else {
                require(loads.isEmpty() && compare.destination == Register(16, 8))
                member(compare.offset, compare.source)
            }
            require(target != opposite)
            val branch = flow.body.getValue(compare.offset + compare.size)
            require(
                branch.operation == Operation.JCC && branch.condition == (if (reversed) {
                    if (rejectedCondition == 2) 7 else 2
                } else rejectedCondition) &&
                        branch.offset + branch.size == store.offset
            )
            val rejected = (branch.destination as? Immediate)?.value ?: error("Range rejection is indirect")
            require(rejected > store.offset + store.size && rejected in flow.reachable)
            for (instruction in prefix) {
                if (instruction == compare || instruction == branch || instruction in loads) continue
                require(instruction.operation in setOf(Operation.PUSH, Operation.MOV, Operation.NOP, Operation.ENDBR))
                require(instruction.destination == null || instruction.destination is Register)
                val register = instruction.destination as? Register
                require(register == null || register.number < 16) { "Range setter changes its floating argument" }
            }
            return SliderRangeSetter(target, opposite)
        }
    }
}
