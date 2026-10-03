package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** A side-effect-bounded path from a documented SDL event to its first native construction call. */
internal object SdlPointerPath {
    data class Proof(val flow: X64ControlFlow, val call: Long, val kind: Long)

    fun construction(
        flow: X64ControlFlow, tables: List<X64JumpTables.Table>, callTarget: Long, sdkExtent: Long,
        fields: Map<SysVArgumentFlow.Read, Long>
    ): Proof {
        val scalar = ScalarExpression(flow, mapOf(2 to sdkExtent))
        val frame = SysVLocalArgument(flow)
        val replacements = mutableMapOf<Long, Instruction>()
        val visited = mutableSetOf<Long>()
        val stores = mutableListOf<Instruction>()
        var site = 0L
        fun value(input: ScalarExpression.Input) = fields[input.field]
            ?: error("Mouse construction admission depends on an unverified input")
        while (true) {
            require(visited.size < 1024 && visited.add(site)) { "Mouse construction admission loops or exceeds bound" }
            val instruction = flow.body.getValue(site)
            if (instruction.operation == Operation.CALL) {
                require(instruction.destination == Immediate(callTarget)) { "Mouse admission reaches another native call" }
                val selected = X64ControlFlow(flow.instructions.map { replacements[it.offset] ?: it },
                    tables.filter { it.jump !in replacements }).reaching(site)
                val borrows = mapOf(site to listOf(ConstructorValues.Borrow(6, 8), ConstructorValues.Borrow(2, 4)))
                val provenance = ConstructorValues(selected, borrows)
                val location = frame.argument(site, 2, 4)
                frame.argument(site, 6, 8)
                require(provenance.register(site, 7) == ConstructorValues.Argument(1)) {
                    "Mouse constructor does not receive the original event queue"
                }
                val last = stores.lastOrNull { write ->
                    val target = write.destination as Memory
                    val local = frame.address(write.offset, target)
                    local != null && local < location + 4 && location < local + target.width
                } ?: error("Mouse constructor kind is not initialized on its selected path")
                val target = last.destination as Memory
                require(frame.address(last.offset, target) == location && target.width == 4 && last.operation == Operation.MOV)
                val kind = when (val source = last.source) {
                    is Immediate -> source.value and 0xffffffffL
                    is Register -> ScalarExpression.evaluate(scalar.before(last.offset, source), ::value)
                    else -> error("Mouse constructor kind is not scalar")
                }
                return Proof(selected, site, kind)
            }
            require(instruction.operation !in listOf(Operation.RET, Operation.POP)) { "Mouse admission restores its frame" }
            val memory = instruction.destination as? Memory
            if (memory != null && instruction.operation !in listOf(Operation.CMP, Operation.TEST, Operation.SCALAR_COMPARE)) {
                require(instruction.operation in listOf(Operation.MOV, Operation.SCALAR_MOV, Operation.VECTOR_MOV))
                val slot = frame.address(site, memory) ?: error("Mouse admission mutates an external object")
                require(slot >= checkNotNull(frame.registers(site)[4]) && slot <= -memory.width)
                stores += instruction
            }
            var next = site + instruction.size
            var replacementTarget: Long? = null
            when (instruction.operation) {
                Operation.JCC -> {
                    val table = tables.singleOrNull { it.guard == site }
                    if (table != null) {
                        val index = ScalarExpression.evaluate(scalar.before(site, table.index.copy(width = 4)), ::value)
                        require(index >= 0)
                        if (index < table.targets.size) {
                            next = table.targets[index.toInt()]
                            replacementTarget = site + instruction.size
                            val jump = flow.body.getValue(table.jump)
                            replacements[table.jump] = jump.copy(destination = Immediate(next))
                        } else next = (instruction.destination as Immediate).value
                    } else if (ScalarExpression.evaluate(scalar.branch(site), ::value) != 0L) {
                        next = (instruction.destination as Immediate).value
                    }
                    replacements[site] = instruction.copy(operation = Operation.JMP, destination = Immediate(replacementTarget ?: next),
                        source = null, condition = null)
                }
                Operation.JMP -> next = (instruction.destination as? Immediate)?.value
                    ?: error("Mouse SDK path has an unselected indirect branch")
                else -> Unit
            }
            site = next
        }
    }
}
