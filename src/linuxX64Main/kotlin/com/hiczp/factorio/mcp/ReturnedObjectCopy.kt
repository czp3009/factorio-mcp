package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Complete copy of a selected callee's frame result into one bounded original-receiver member. */
internal object ReturnedObjectCopy {
    fun analyze(flow: X64ControlFlow, callee: Long, receiverSize: Long, resultSize: Int): Long {
        require(receiverSize in 8..64 * 1024 * 1024 && resultSize in 1..4096)
        val call = flow.instructions.single { it.operation == Operation.CALL && it.destination == Immediate(callee) }
        val frame = SysVLocalArgument(flow)
        val output = checkNotNull(frame.registers(call.offset)[7]) { "Result storage is not a local frame address" }
        val bottom = checkNotNull(frame.registers(call.offset)[4])
        require(output >= bottom && output <= -resultSize.toLong()) { "Result storage exceeds the caller frame" }
        val values = mutableMapOf<Int, Pair<Long, Int>>()
        val copied = mutableSetOf<Long>()
        val stores = mutableListOf<Pair<Instruction, Long>>()
        val receiverLoads = mutableListOf<Instruction>()
        var previous = call.offset
        var position = call.offset + call.size
        repeat(64) {
            val instruction = flow.body[position] ?: error("Result copy leaves the caller")
            require(flow.predecessors[position] == setOf(previous)) { "Result copy has an alternate entry" }
            require(instruction.operation in setOf(Operation.MOV, Operation.SCALAR_MOV, Operation.VECTOR_MOV)) {
                "Result copy contains an intervening operation"
            }
            val source = instruction.source
            val target = instruction.destination
            when {
                source is Memory && target is Register -> {
                    val address = frame.address(position, source) ?: error("Result copy reads outside the frame")
                    require(source.width == target.width)
                    if (address >= output && address <= output + resultSize - source.width) {
                        values[target.number] = (address - output) to source.width
                    } else {
                        require(source.width == 8 && target.number in 0..15 &&
                                (address + source.width <= output || address >= output + resultSize)) {
                            "Result copy contains an unrelated frame load"
                        }
                        values.remove(target.number)
                        receiverLoads += instruction
                    }
                }
                source is Register && target is Memory -> {
                    val value = checkNotNull(values[source.number]) { "Result copy stores an unrelated register" }
                    require(source.width == value.second && target.width == value.second &&
                            !target.relative && target.index == null && target.base != null)
                    stores += instruction to value.first
                    repeat(value.second) { byte -> require(copied.add(value.first + byte)) { "Result copy overlaps" } }
                }
                else -> error("Unsupported result copy operand")
            }
            if (copied.size == resultSize) {
                // This stronger provenance reader recovers private saved receiver arguments. The selected
                // return storage is the only admitted native borrow; any other escaped frame fails analysis.
                val arguments = ConstructorValues(flow.reaching(position),
                    mapOf(call.offset to listOf(ConstructorValues.Borrow(7, resultSize))))
                require(arguments.register(call.offset, 6) == ConstructorValues.Argument(7)) {
                    "Result getter does not receive the original receiver in RSI"
                }
                for (load in receiverLoads) {
                    require(arguments.register(load.offset + load.size, (load.destination as Register).number) ==
                            ConstructorValues.Argument(7)) { "Result copy reloads an unrelated frame value" }
                }
                val candidates = stores.map { (store, offset) ->
                    val destination = store.destination as Memory
                    val owner = arguments.register(store.offset, checkNotNull(destination.base)) as? ConstructorValues.Argument
                        ?: error("Result destination does not recover its original receiver")
                    require(owner.register == 7)
                    (owner.adjustment + destination.displacement - offset).also {
                        require(it in 8..receiverSize - resultSize) { "Result copy exceeds its bounded member" }
                    }
                }.distinct()
                return candidates.singleOrNull() ?: error("Result copy disagrees on its destination member")
            }
            previous = position
            position += instruction.size
        }
        error("Result copy exceeds its instruction bound")
    }
}
