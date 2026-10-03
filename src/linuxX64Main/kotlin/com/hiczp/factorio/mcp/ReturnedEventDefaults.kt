package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Constants written by a selected typed emplace case to the element whose returned identity EventHeader proves. */
internal object ReturnedEventDefaults {
    fun resolve(image: ElfImage, header: EventHeader, kind: Long): Map<Long, Int> {
        val function = image.symbol("_ZN12CompactDequeI5EventE12emplace_backIJRdNS0_4TypeEEEERS0_DpOT_")
        val flow = X64ControlFlow.resolve(image, function)
        require(EventHeader.analyze(flow) == header)
        return analyze(flow, X64JumpTables.resolve(image, function).single(), header, kind)
    }

    fun analyze(flow: X64ControlFlow, table: X64JumpTables.Table, header: EventHeader, kind: Long): Map<Long, Int> {
        val expression = ScalarExpression(flow, mapOf(2 to 4L)).before(table.guard, table.index.copy(width = 4))
        require(ScalarExpression.inputs(expression).map { it.field }.toSet() ==
                setOf(SysVArgumentFlow.Read(SysVArgumentFlow.Reference(2), 4)))
        val index = ScalarExpression.evaluate(expression) { kind }
        require(index >= 0 && index < table.targets.size)
        val pointers = mutableMapOf(0 to 0L)
        val constants = mutableMapOf<Int, Long>()
        val defaults = mutableMapOf<Long, Int>()
        val visited = mutableSetOf<Long>()
        var site = table.targets[index.toInt()]
        fun offset(memory: Memory): Long? = if (!memory.relative && memory.index == null)
            memory.base?.let { pointers[it] }?.plus(memory.displacement) else null
        while (true) {
            require(visited.size < 256 && visited.add(site)) { "Event default case loops or exceeds bound" }
            val instruction = flow.body.getValue(site)
            var next = site + instruction.size
            when (instruction.operation) {
                Operation.MOV, Operation.SCALAR_MOV, Operation.VECTOR_MOV -> {
                    val constant = when (val source = instruction.source) {
                        is Immediate -> source.value
                        is Register -> constants[source.number]
                        else -> null
                    }
                    when (val target = instruction.destination) {
                        is Register -> {
                            val pointer = (instruction.source as? Register)?.takeIf { target.width == 8 && it.width == 8 }
                                ?.let { pointers[it.number] }
                            pointers.remove(target.number)
                            constants.remove(target.number)
                            if (pointer != null) pointers[target.number] = pointer
                            if (constant != null && target.width >= 4)
                                constants[target.number] = if (target.width == 4) constant and 0xffffffffL else constant
                        }
                        is Memory -> {
                            val at = offset(target) ?: error("Event default writes outside its returned element")
                            require(at >= 0 && at <= header.extent - target.width && target.width in listOf(1, 2, 4, 8, 16))
                            require(constant != null && (target.width <= 8 || constant == 0L)) {
                                "Event default case owns a non-scalar payload"
                            }
                            repeat(target.width) { byte ->
                                defaults[at + byte] = if (byte >= 8) 0 else (constant ushr (byte * 8) and 255).toInt()
                            }
                        }
                        else -> error("Event default has an unsupported destination")
                    }
                }
                Operation.XOR, Operation.VECTOR_XOR -> {
                    val target = instruction.destination as Register
                    require(instruction.source == target)
                    pointers.remove(target.number)
                    constants[target.number] = 0
                }
                Operation.LEA -> {
                    val target = instruction.destination as Register
                    require(target.width == 8 && target.number != 4)
                    val pointer = offset(instruction.source as Memory)
                    pointers.remove(target.number)
                    constants.remove(target.number)
                    if (pointer != null) {
                        require(pointer in 0..header.extent.toLong())
                        pointers[target.number] = pointer
                    }
                }
                Operation.JMP -> next = (instruction.destination as? Immediate)?.value
                    ?: error("Event default has another indirect branch")
                Operation.ADD -> require(instruction.destination == Register(4, 8) && instruction.source is Immediate)
                Operation.POP -> {
                    val target = instruction.destination as Register
                    require(target.number != 0 && target.number != 4)
                    pointers.remove(target.number)
                    constants.remove(target.number)
                }
                Operation.NOP, Operation.ENDBR -> Unit
                Operation.RET -> {
                    require(pointers[0] == 0L) { "Event default changes the returned element identity" }
                    require(defaults.keys.none { it in header.type until header.type + 4 || it in header.time until header.time + 8 })
                    return defaults
                }
                else -> error("Unsupported native Event default case: ${instruction.operation}")
            }
            require(next in flow.successors.getValue(site))
            site = next
        }
    }
}
