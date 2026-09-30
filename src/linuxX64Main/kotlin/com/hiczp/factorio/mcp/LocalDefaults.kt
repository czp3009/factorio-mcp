package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Constant bytes of an unexposed local reference on every incoming path to its selected consumer. */
internal object LocalDefaults {
    fun before(
        flow: X64ControlFlow, call: Long, extent: Int, fields: List<InlineArgumentFields.Field>,
        argument: Int = 6
    ): Map<Long, Int> {
        require(fields.isNotEmpty() && fields.all {
            it.width in listOf(1, 2, 4, 8, 16) &&
                    it.offset >= 0 && it.offset <= extent - it.width
        })
        val frame = SysVLocalArgument(flow)
        val storage = frame.argument(call, argument, extent)
        val wanted = fields.flatMap { (offset, width) -> (offset until offset + width).toList() }.toSet()
        require(wanted.size <= 256)
        val values = ConstructorValues(flow, mapOf(call to listOf(ConstructorValues.Borrow(argument, extent))))
        val before = mutableMapOf<Long, Map<Long, Int>>(0L to emptyMap())
        val pending = ArrayDeque<Long>()
        pending.add(0)
        var steps = 0
        while (pending.isNotEmpty()) {
            require(++steps <= 65536) { "Local initialization exceeds analysis bound" }
            val site = pending.removeFirst()
            // Only paths before first admission contribute to the initialized input object.
            if (site == call) continue
            val bytes = before.getValue(site).toMutableMap()
            val instruction = flow.body.getValue(site)
            val memory = instruction.destination as? Memory
            if (memory != null && instruction.operation !in listOf(
                    Operation.CMP, Operation.TEST, Operation.NOP,
                    Operation.CALL, Operation.JMP
                )
            ) {
                val slot = frame.address(site, memory)
                if (slot != null) {
                    require(slot >= checkNotNull(frame.registers(site)[4]) && slot <= -memory.width)
                    val constant =
                        if (instruction.operation in listOf(Operation.MOV, Operation.SCALAR_MOV, Operation.VECTOR_MOV))
                            when (val source = instruction.source) {
                                is Immediate -> source.value
                                is Register -> (values.register(
                                    site,
                                    source.number
                                ) as? ConstructorValues.Constant)?.value

                                else -> null
                            } else null
                    for (index in 0 until memory.width) {
                        val offset = slot - storage + index
                        if (offset in wanted) {
                            bytes.remove(offset)
                            if (constant != null && (memory.width <= 8 || constant == 0L))
                                bytes[offset] = if (index >= 8) 0 else (constant ushr (index * 8) and 255).toInt()
                        }
                    }
                }
            }
            val top = checkNotNull(frame.registers(site)[4])
            if (instruction.operation in listOf(Operation.PUSH, Operation.POP)) {
                val overwritten = if (instruction.operation == Operation.PUSH) top - 8 else top
                bytes.keys.removeAll { storage + it in overwritten until overwritten + 8 }
            }
            if (instruction.destination == Register(4, 8) && instruction.operation in listOf(
                    Operation.ADD,
                    Operation.SUB
                )
            ) {
                val amount = (instruction.source as Immediate).value
                val nextTop = top + if (instruction.operation == Operation.ADD) amount else -amount
                bytes.keys.removeAll { storage + it < nextTop }
            }
            for (next in flow.successors.getValue(site)) {
                val old = before[next]
                val merged =
                    if (old == null) bytes.toMap() else old.filter { (offset, value) -> bytes[offset] == value }
                if (merged != old) {
                    before[next] = merged
                    pending.add(next)
                }
            }
        }
        return checkNotNull(before[call]).also {
            require(it.keys == wanted) { "Local input defaults are incomplete or path-dependent" }
        }
    }
}
