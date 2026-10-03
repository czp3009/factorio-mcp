package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** A bounded native member forwarded unchanged to the inline Lua boolean result. No Lua execution. */
internal data class LuaBooleanMember(val objectPointer: Long, val field: Long) {
    companion object {
        private sealed interface Value
        private data class Input(val register: Int) : Value
        private data class Object(val member: Long) : Value
        private data class ByteValue(val member: LuaBooleanMember, val width: Int) : Value
        private data object Top : Value
        private data object Unknown : Value

        fun resolve(
            image: ElfImage, function: String, ownerName: String, wrapperExtent: Long,
            objectExtent: Long, top: Long, valueSize: Long, debug: DwarfInlines = image.inlines
        ): LuaBooleanMember {
            val entry = image.symbol(function)
            val flow = X64ControlFlow.resolve(image, entry)
            val instances = debug.find(entry, ownerName, setOf("lua_pushboolean"))
            require(instances.map { it.origin }.distinct().size == 1)
            val boundaries = flow.body.keys.map { entry.address + it }.toSet() + (entry.address + entry.size)
            val ranges = instances.flatMap { it.ranges }
            require(ranges.all { it.start in boundaries && it.end in boundaries })
            val sites = flow.instructions.filter { instruction ->
                ranges.any {
                    entry.address + instruction.offset >= it.start &&
                            entry.address + instruction.offset + instruction.size <= it.end
                }
            }.map { it.offset }.toSet()
            return analyze(flow, wrapperExtent, objectExtent, top, valueSize, sites)
        }

        fun analyze(
            flow: X64ControlFlow, wrapperExtent: Long, objectExtent: Long, top: Long, valueSize: Long,
            pushSites: Set<Long>
        ): LuaBooleanMember {
            require(wrapperExtent in 8..4096 && objectExtent in 1..4096 && top in 0..4096 && valueSize in 8..128)
            require(pushSites.isNotEmpty() && pushSites.all { it in flow.body })
            val before = mutableMapOf<Long, List<Value>>(0L to List(16) { Input(it) })
            val pending = ArrayDeque<Long>()
            pending.add(0)
            var steps = 0
            fun source(registers: List<Value>, operand: X64Instructions.Operand?): Value = when (operand) {
                is Register -> when (val value = registers[operand.number]) {
                    is ByteValue -> if (operand.width <= value.width) value.copy(width = operand.width) else Unknown
                    else -> if (operand.width == 8) value else Unknown
                }

                is Memory -> {
                    val base = operand.base?.let { registers[it] }
                    if (operand.relative || operand.index != null) Unknown
                    else when {
                        base == Input(7) && operand.width == 8 && operand.displacement in 0..wrapperExtent - 8 ->
                            Object(operand.displacement)

                        base is Object && operand.width == 1 && operand.displacement in 0 until objectExtent ->
                            ByteValue(LuaBooleanMember(base.member, operand.displacement), 1)

                        base == Input(6) && operand.width == 8 && operand.displacement == top -> Top
                        else -> Unknown
                    }
                }

                else -> Unknown
            }
            while (pending.isNotEmpty()) {
                require(++steps <= 65536) { "Lua boolean member analysis exceeds bound" }
                val site = pending.removeFirst()
                val registers = before.getValue(site).toMutableList()
                val instruction = flow.body.getValue(site)
                val target = instruction.destination
                when (instruction.operation) {
                    Operation.MOV, Operation.MOVZX -> if (target is Register) {
                        val value = source(registers, instruction.source)
                        registers[target.number] = when {
                            instruction.operation == Operation.MOVZX && value is ByteValue && target.width >= value.width ->
                                value.copy(width = target.width)

                            target.width == 8 -> value
                            value is ByteValue && target.width <= value.width -> value.copy(width = target.width)
                            else -> Unknown
                        }
                    }

                    Operation.CALL -> for (register in listOf(0, 1, 2, 6, 7, 8, 9, 10, 11)) registers[register] =
                        Unknown

                    Operation.PUSH, Operation.POP -> {
                        registers[4] = Unknown
                        if (instruction.operation == Operation.POP && target is Register) registers[target.number] =
                            Unknown
                    }

                    Operation.NOP, Operation.ENDBR, Operation.CMP, Operation.TEST, Operation.JCC, Operation.JMP,
                    Operation.RET -> Unit

                    Operation.LEA, Operation.ADD, Operation.SUB, Operation.SHL, Operation.SHR, Operation.SAR,
                    Operation.AND, Operation.OR, Operation.XOR, Operation.INC, Operation.DEC, Operation.MOVSX,
                    Operation.CMOV, Operation.SET -> if (target is Register) registers[target.number] = Unknown

                    else -> error("Unsupported Lua boolean member operation: ${instruction.operation}")
                }
                for (next in flow.successors.getValue(site)) {
                    val old = before[next]
                    val merged = if (old == null) registers.toList() else old.mapIndexed { index, value ->
                        if (value == registers[index]) value else Unknown
                    }
                    if (old != merged) {
                        before[next] = merged
                        pending.add(next)
                    }
                }
            }
            val outputs = mutableMapOf<Long, LuaBooleanMember>()
            val payloads = mutableSetOf<Long>()
            val tags = mutableSetOf<Long>()
            for (site in flow.reachable) {
                val instruction = flow.body.getValue(site)
                val target = instruction.destination as? Memory ?: continue
                if (instruction.operation in setOf(Operation.CMP, Operation.TEST)) continue
                val registers = before.getValue(site)
                val base = target.base?.let { registers[it] }
                require(base != Input(7) && base !is Object) { "Lua boolean reader writes its source object" }
                if (base != Top) continue
                require(
                    site in pushSites && instruction.operation == Operation.MOV && !target.relative &&
                            target.index == null && target.width == 4 && target.displacement in 0..valueSize - 4
                ) {
                    "Lua boolean output is not a bounded inline scalar push"
                }
                // The inline implementation also writes a constant type tag. Its position is not assumed.
                if (instruction.source is Immediate) {
                    tags += target.displacement
                    continue
                }
                val value = source(registers, instruction.source) as? ByteValue
                    ?: error("Lua boolean output does not preserve an original object byte")
                require(value.width == 4)
                outputs[site] = value.member
                payloads += target.displacement
            }
            val member =
                outputs.values.distinct().singleOrNull() ?: error("Lua boolean reader has no unique member result")
            val payload = payloads.singleOrNull() ?: error("Lua boolean reader has conflicting scalar outputs")
            require(tags.all { it + 4 <= payload || payload + 4 <= it }) { "Lua tag overwrites the boolean payload" }
            require(flow.instructions.any { it.operation == Operation.RET && it.offset in flow.reachable })
            // Every normal return must pass a proven result store. Exceptional exits establish no result.
            val visited = mutableSetOf<Long>()
            pending.add(0)
            while (pending.isNotEmpty()) {
                val site = pending.removeFirst()
                if (!visited.add(site) || site in outputs) continue
                require(flow.body.getValue(site).operation != Operation.RET) { "Lua boolean reader returns without its member" }
                pending.addAll(flow.successors.getValue(site))
            }
            return member
        }
    }
}
