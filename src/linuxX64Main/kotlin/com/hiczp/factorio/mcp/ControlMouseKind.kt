package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Type equality admitting a bounded original button code into the native returned button mask. */
internal object ControlMouseKind {
    fun resolve(
        image: ElfImage, extent: Long, type: Long, code: Long,
        function: String = "_ZNK17ControlInputValue17mouseButtonOfThisEv"
    ): Int {
        val entry = image.symbol(function)
        EhFrames(image).function(entry)
        return analyze(X64ControlFlow.resolve(image, entry), extent, type, code)
    }

    fun analyze(flow: X64ControlFlow, extent: Long, type: Long, code: Long): Int {
        require(extent in 8..4096 && type in 0 until extent && code in 0..extent - 4 && type !in code until code + 4)
        val arguments = SysVArgumentFlow(flow)
        for (instruction in flow.instructions.filter { it.offset in flow.reachable }) {
            if (instruction.operation in setOf(Operation.CMP, Operation.TEST, Operation.BIT_TEST)) continue
            val destinations = listOfNotNull(
                instruction.destination as? Memory,
                (instruction.source as? Memory).takeIf { instruction.operation == Operation.XCHG })
            require(destinations.none { arguments.memory(instruction.offset, it)?.reference?.argument == 7 }) {
                "Button mask function writes to its original source object"
            }
        }
        val reads = flow.instructions.mapNotNull { instruction ->
            if (instruction.operation !in setOf(Operation.MOV, Operation.MOVZX)) return@mapNotNull null
            val input = arguments.source(instruction.offset) ?: return@mapNotNull null
            if (input != SysVArgumentFlow.Read(SysVArgumentFlow.Reference(7, type), 1)) return@mapNotNull null
            instruction.offset to PrivateValueCopies.Read(0, InlineArgumentFields.Field(type, 1))
        }.toMap()
        val copies = reads.takeIf { it.isNotEmpty() }?.let { PrivateValueCopies(flow, it) }
        val found = mutableSetOf<Int>()
        for (compare in flow.instructions) {
            if (compare.offset !in flow.reachable || compare.operation != Operation.CMP) continue
            val value = compare.source as? Immediate ?: continue
            if (value.value !in 0..255) continue
            val originalType = when (val input = compare.destination) {
                is Register -> input.width == 1 && try {
                    copies?.field(compare.offset, input) ==
                            PrivateValueCopies.Read(0, InlineArgumentFields.Field(type, 1))
                } catch (_: IllegalArgumentException) {
                    false
                } catch (_: IllegalStateException) {
                    false
                }

                is Memory -> arguments.memory(compare.offset, input) ==
                        SysVArgumentFlow.Read(SysVArgumentFlow.Reference(7, type), 1)

                else -> false
            }
            if (!originalType) continue
            val branch = flow.body[compare.offset + compare.size] ?: continue
            if (branch.operation != Operation.JCC || branch.condition !in setOf(4, 5)) continue
            val jump = (branch.destination as? Immediate)?.value ?: continue
            val selected = if (branch.condition == 4) jump else branch.offset + branch.size
            val rejected = if (branch.condition == 4) branch.offset + branch.size else jump
            try {
                maskPath(flow, arguments, compare.offset, selected, rejected, code)
                found += value.value.toInt()
            } catch (_: IllegalArgumentException) {
                continue
            } catch (_: IllegalStateException) {
                continue
            }
        }
        return found.singleOrNull() ?: error("No unique control type returns its original bounded button mask")
    }

    private sealed interface Value
    private data class Constant(val number: Long) : Value
    private data object Code : Value
    private data object Mask : Value
    private data class Scalar(val value: Value, val width: Int)

    private fun maskPath(
        flow: X64ControlFlow, arguments: SysVArgumentFlow, gate: Long,
        start: Long, rejectedType: Long, code: Long
    ) {
        val pointers = MutableList(16) { arguments.register(gate, it) }
        val values = MutableList<Scalar?>(16) { null }
        var site = start
        var bound: Long? = null
        var bounded = false
        var shift: Long? = null
        var rejectedCode: Long? = null
        val visited = mutableSetOf<Long>()
        repeat(48) {
            require(visited.add(site)) { "Button mask path loops" }
            val instruction = flow.body.getValue(site)
            val target = instruction.destination as? Register
            fun write(value: Scalar?) {
                require(target != null && target.number in 0..15 && target.number != 4)
                pointers[target.number] = null
                values[target.number] = value
            }
            when (instruction.operation) {
                Operation.NOP -> Unit
                Operation.MOV -> {
                    require(target != null && target.width in setOf(1, 2, 4, 8))
                    when (val source = instruction.source) {
                        is Immediate -> write(Scalar(Constant(source.value), target.width))
                        is Register -> {
                            require(source.width == target.width)
                            val pointer = pointers[source.number].takeIf { source.width == 8 }
                            val value =
                                values[source.number]?.takeIf { it.width >= source.width }?.copy(width = source.width)
                            write(value)
                            pointers[target.number] = pointer
                        }

                        is Memory -> {
                            require(
                                source.width == 4 && target.width == 4 && !source.relative && source.index == null &&
                                    source.base?.let { pointers[it] }?.let {
                                        it.argument == 7 && it.offset + source.displacement == code
                                    } == true)
                            write(Scalar(Code, 4))
                        }

                        else -> error("Unsupported button mask value")
                    }
                }

                Operation.CMP -> {
                    require(!bounded && bound == null && target?.width == 4 && values[target.number] == Scalar(Code, 4))
                    bound = (instruction.source as? Immediate)?.value?.also { require(it in 0..15) }
                        ?: error("Button code range has no constant upper bound")
                }

                Operation.JCC -> {
                    require(bound != null && !bounded && instruction.condition in setOf(6, 7))
                    val destination =
                        (instruction.destination as? Immediate)?.value ?: error("Indirect button code bound")
                    bounded = true
                    rejectedCode = if (instruction.condition == 7) destination else site + instruction.size
                    site = if (instruction.condition == 6) destination else site + instruction.size
                    return@repeat
                }

                Operation.SHL -> {
                    val count = instruction.source as? Register ?: error("Button mask lacks a code shift")
                    require(
                        bounded && shift == null && target == Register(0, 4) && values[0] == Scalar(Constant(1), 4) &&
                                count.width == 1 && values[count.number]?.value == Code
                    )
                    values[0] = Scalar(Mask, 4)
                    shift = site
                }

                Operation.JMP -> {
                    site = (instruction.destination as? Immediate)?.value ?: error("Indirect button mask exit")
                    return@repeat
                }

                Operation.ADD -> require(shift != null && target == Register(4, 8) && instruction.source is Immediate)
                Operation.PUSH -> {
                    require(target?.width == 8 && target.number != 4)
                    pointers[4] = null
                }

                Operation.POP -> {
                    require(shift != null && target?.width == 8 && target.number !in setOf(0, 4))
                    pointers[target.number] = null
                    values[target.number] = null
                }

                Operation.RET -> {
                    require(values[0]?.let { it.value == Mask && it.width >= 2 } == true && shift != null && rejectedCode != null)
                    for (rejected in listOf(rejectedType, checkNotNull(rejectedCode))) {
                        val pending = ArrayDeque<Long>()
                        val seen = mutableSetOf<Long>()
                        pending.add(rejected)
                        while (pending.isNotEmpty()) {
                            val position = pending.removeFirst()
                            require(position != shift) { "Rejected button path reaches the mask" }
                            if (seen.add(position)) pending.addAll(flow.successors.getValue(position))
                        }
                    }
                    return
                }

                else -> error("Unsupported instruction in button mask path: ${instruction.operation}")
            }
            site += instruction.size
        }
        error("Button mask path exceeds bound")
    }
}
