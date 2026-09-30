package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.SysVReceiverFlow.Constant
import com.hiczp.factorio.mcp.SysVReceiverFlow.Original
import com.hiczp.factorio.mcp.X64Instructions.*

/** Two keyboard/mouse value locations, distinguished from controller slots by native readers rather than memory order. */
internal data class ControlSlots(val values: List<Long>, val type: Long, val code: Long, val keyboardType: Long) {
    companion object {
        fun resolve(image: ElfImage, extent: Long, debug: DwarfInlines = DwarfInlines(image)): ControlSlots = resolve(
            image, extent,
            "_ZNK12ControlInput12hasKeyActionEi", "hasKeyAction", "getControlInputValuesForActiveInputMethod",
            "_ZNK12ControlInput24getControllerStickVectorEv",
            "_ZNK10InputState24getControllerStickValuesE15ControllerStick", "global", debug
        )

        fun resolve(
            image: ElfImage, extent: Long, lookup: String, lookupName: String, getter: String,
            controller: String, stick: String, global: String, debug: DwarfInlines = DwarfInlines(image)
        ): ControlSlots {
            val function = image.symbol(lookup)
            require(function.size in 1..4096 && extent in 16..4096)
            val instances = debug.find(function, lookupName, setOf(getter))
            val ranges =
                instances.flatMap { it.ranges }.map { (it.start - function.address) until (it.end - function.address) }
            val bytes = image.functionBytes(function, 4096)
            val pair = analyze(bytes, function.address, image.symbol(global).address, extent, ranges)
            val reader = image.symbol(controller)
            EhFrames(image).function(reader)
            val flow = X64ControlFlow.resolve(image, reader)
            val callee = image.symbol(stick)
            EhFrames(image).function(callee)
            val argument = discriminator(image.functionBytes(callee, 512), callee.address)
            val calls = flow.instructions.filter {
                it.operation == Operation.CALL &&
                        it.destination == Immediate(callee.address - reader.address)
            }
            require(calls.size == 2) { "Controller reader does not consume two native binding codes" }
            val scalars = ScalarExpression(flow, mapOf(7 to extent))
            val codes = calls.map {
                var value = scalars.before(it.offset, Register(argument, 4))
                while (value is ScalarExpression.Narrow) {
                    require(value.width == 4 && value.value.width == 4)
                    value = value.value
                }
                val input = value as? ScalarExpression.Input
                    ?: error("Controller stick code is not an original control member")
                require(input.width == 4 && input.field.reference.argument == 7)
                input.field.reference.offset
            }
            val candidates = listOf(pair.zero, pair.nonzero)
            val controllerPair = candidates.singleOrNull { members -> members.map { it + pair.code } == codes }
                ?: error("Native controller reader disagrees with the active input slots")
            val keyboardPair = candidates.single { it != controllerPair }
            return ControlSlots(keyboardPair, pair.type, pair.code, pair.keyboardType)
        }

        private fun discriminator(bytes: BinaryView, address: Long): Int {
            val decoder = X64Instructions(bytes)
            var site = 0L
            repeat(32) {
                val instruction = decoder.decode(site)
                site += instruction.size
                require(instruction.operation !in setOf(Operation.CALL, Operation.JMP, Operation.JCC, Operation.RET))
                if (instruction.operation == Operation.CMP) {
                    val input =
                        instruction.destination as? Register ?: error("Stick discriminator is not a scalar argument")
                    require(input.width == 4 && instruction.source is Immediate)
                    val original =
                        SysVReceiverFlow(bytes.slice(0, site), address, 4096).before(instruction.offset)[input.number]
                                as? Original ?: error("Stick discriminator loses its original argument")
                    require(original.number in listOf(6, 2, 1, 8, 9))
                    return original.number
                }
            }
            error("Controller stick discriminator exceeds prefix bound")
        }

        internal data class Pairs(
            val zero: List<Long>, val nonzero: List<Long>, val type: Long,
            val code: Long, val keyboardType: Long
        )

        fun analyze(
            bytes: BinaryView, address: Long, global: Long, extent: Long,
            ranges: List<LongRange>
        ): Pairs {
            require(bytes.size in 1..4096 && extent in 16..4096 && ranges.isNotEmpty())
            val instructions = X64Instructions(bytes).all(1024)
            val flow = X64ControlFlow(instructions)
            val definitions = ScalarExpression(flow)
            val receivers = SysVReceiverFlow(bytes, address, extent)
            val arguments = SysVArgumentFlow(flow)
            val end = instructions.last().offset + instructions.last().size
            require(ranges.all { it.first in flow.body && it.last + 1 in flow.body.keys + end })
            val selections = instructions.filter { instruction ->
                instruction.operation == Operation.CMOV && ranges.any {
                    instruction.offset in it && instruction.offset + instruction.size - 1 in it
                }
            }
            require(selections.size == 2) { "Active input getter does not select exactly two member pointers" }
            val methodSources = mutableSetOf<Pair<Long, Long>>()
            val types = mutableSetOf<Long>()
            val codes = mutableSetOf<Long>()
            val keyboardTypes = mutableSetOf<Long>()
            fun flags(site: Long): X64Instructions.Instruction {
                val pending = ArrayDeque(flow.predecessors[site].orEmpty())
                val seen = mutableSetOf<Long>()
                val producers = mutableSetOf<Long>()
                while (pending.isNotEmpty()) {
                    val previous = pending.removeFirst()
                    if (!seen.add(previous)) continue
                    require(seen.size <= 64)
                    val instruction = flow.body.getValue(previous)
                    if (instruction.operation in setOf(Operation.CMP, Operation.TEST)) producers += previous
                    else {
                        require(
                            instruction.operation in setOf(
                                Operation.MOV, Operation.MOVZX, Operation.LEA,
                                Operation.NOP, Operation.CMOV
                            )
                        ) { "Active input selection loses its condition flags" }
                        val incoming = flow.predecessors[previous].orEmpty()
                        require(incoming.isNotEmpty())
                        pending.addAll(incoming)
                    }
                }
                return flow.body.getValue(producers.single())
            }

            val pairs = selections.map { selection ->
                val target = selection.destination as? Register ?: error("Active slot selection writes memory")
                val source = selection.source as? Register ?: error("Active slot selection has no register source")
                require(target.width == 8 && source.width == 8 && selection.condition in setOf(4, 5))
                val state = receivers.before(selection.offset)
                val indexed = state[source.number] is Constant && state[target.number] is Constant
                fun member(register: Int): Long = if (indexed) (state[register] as Constant).value else {
                    val pointer = arguments.register(selection.offset, register)
                        ?: error("Active slot is not in the original control")
                    require(pointer.argument == 7)
                    pointer.offset
                }

                val yes = member(source.number)
                val no = member(target.number)
                require(yes in 0 until extent && no in 0 until extent && yes != no)
                val test = flags(selection.offset)
                val load: X64Instructions.Instruction
                val field: Memory
                if (test.operation == Operation.TEST) {
                    val method = test.destination as? Register ?: error("Active input mode has no scalar test")
                    require(test.source == method && method.width == 1)
                    load = definitions.definition(test.offset, method.number)
                    require(load.operation == Operation.MOVZX)
                    field = load.source as? Memory ?: error("Active input mode is not a native field")
                } else {
                    require(test.source == Immediate(0))
                    load = test
                    field = test.destination as? Memory ?: error("Active input mode has no byte comparison")
                }
                require(
                    field.width == 1 && !field.relative && field.index == null &&
                            field.displacement in 0..4095
                )
                // These intermediate pointer fields are symbolic evidence only, never read by the adapter.
                val owner = GlobalPointerLoad(flow, address, global, 64 * 1024 * 1024L)
                    .at(load.offset, checkNotNull(field.base))
                methodSources += owner to field.displacement

                val uses = instructions.filter { it.operation == Operation.CMP }.mapNotNull { instruction ->
                    val operands = listOf(instruction.destination, instruction.source)
                    val member = operands.filterIsInstance<Memory>().singleOrNull() ?: return@mapNotNull null
                    if (member.relative) return@mapNotNull null
                    if (indexed) {
                        if (member.index != target.number || member.scale != 1) return@mapNotNull null
                        require(member.base?.let {
                            arguments.register(
                                instruction.offset,
                                it
                            )
                        } == SysVArgumentFlow.Reference(7))
                    } else if (member.index != null || member.base != target.number) return@mapNotNull null
                    if (definitions.definition(instruction.offset, target.number) != selection) return@mapNotNull null
                    member to operands.single { it != member }
                }
                require(uses.size == 2) { "Active binding does not have distinct type and key comparisons" }
                val type = uses.single { it.first.width == 1 }
                val code = uses.single { it.first.width == 4 }
                val kind = type.second as? Immediate ?: error("Binding type comparison is not constant")
                val key = code.second as? Register ?: error("Binding code comparison loses the key argument")
                require(kind.value in 0..255 && key.width == 4)
                val codeSite = instructions.single {
                    it.operation == Operation.CMP &&
                            listOf(it.destination, it.source).toSet() == setOf(code.first, code.second) &&
                            definitions.definition(it.offset, target.number) == selection
                }
                require(arguments.register(codeSite.offset, key.number) == SysVArgumentFlow.Reference(6))
                for ((member, _) in uses) require(
                    member.displacement >= 0 &&
                            listOf(yes, no).all { it <= extent - member.displacement - member.width })
                require(
                    type.first.displacement + 1 <= code.first.displacement ||
                            code.first.displacement + 4 <= type.first.displacement
                )
                types += type.first.displacement
                codes += code.first.displacement
                keyboardTypes += kind.value
                if (selection.condition == 4) yes to no else no to yes
            }
            require(methodSources.size == 1 && pairs.flatMap { listOf(it.first, it.second) }.distinct().size == 4)
            val fields = pairs.flatMap { listOf(it.first, it.second) }.flatMap {
                listOf(
                    it + types.single() until it + types.single() + 1,
                    it + codes.single() until it + codes.single() + 4
                )
            }
            require(fields.indices.all { first ->
                fields.indices.all { second ->
                    first == second ||
                            fields[first].last < fields[second].first || fields[second].last < fields[first].first
                }
            })
            return Pairs(
                pairs.map { it.first },
                pairs.map { it.second },
                types.single(),
                codes.single(),
                keyboardTypes.single()
            )
        }
    }
}
