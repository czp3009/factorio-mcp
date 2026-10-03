package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Native scalar Event variants derived from the game's documented SDL2 input conversion. */
internal data class PointerEventPayloads(val header: EventHeader, val codes: List<Long>, val cases: List<Case>, val leaveKind: Long) {
    enum class OperationKind { PRESS, RELEASE, MOVE, WHEEL, ENTER }
    data class Case(
        val operation: OperationKind, val kind: Long, val x: Long? = null, val y: Long? = null,
        val code: Long? = null, val wheel: Long? = null, val wheelY: Long? = null,
        val defaults: Map<Long, Int>, val timeStore: Long? = null,
    ) {
        fun dynamic(header: EventHeader) = listOf(header.type to 4, header.time to 8) +
                listOfNotNull(x, y, code, wheel, wheelY).map { it to 4 }
        fun required(header: EventHeader): Set<Long> = dynamic(header).flatMap { (at, width) ->
            (at until at + width).toList()
        }.toSet()
        fun initialized(header: EventHeader): Set<Long> = required(header) + defaults.keys
    }

    init {
        require(header.extent in 16..256 && codes.size == 5 && codes.distinct().size == 5 && codes.all { it in 1..255 })
        require(cases.map { it.operation } == OperationKind.entries && cases.map { it.kind }.distinct().size == cases.size)
        require(leaveKind in 0..0xffffffffL && cases.none { it.kind == leaveKind })
        for (case in cases) {
            require(case.kind in 0..0xffffffffL && case.defaults.values.all { it in 0..255 })
            require(case.initialized(header).all { it in 0 until header.extent })
            val fields = case.dynamic(header)
            require(fields.sumOf { it.second } == case.required(header).size &&
                    case.required(header).intersect(case.defaults.keys).isEmpty())
        }
    }

    companion object {
        // SDL2 SDK layout/constants, connected below to the decoded conversion's original SDL_Event argument.
        // https://github.com/libsdl-org/SDL/blob/SDL2/include/SDL_events.h
        private const val SDK_BUTTON_EXTENT = 28L
        private const val SDK_MOTION_EXTENT = 36L
        private const val SDK_WHEEL_EXTENT = 44L
        private const val SDK_WINDOW_EXTENT = 24L
        private const val SDK_TYPE = 0L
        private const val SDK_WHICH = 12L
        private const val SDK_MOTION = 0x400L
        private const val SDK_WHEEL = 0x403L
        private const val SDK_WINDOW = 0x200L
        private const val SDK_WINDOW_ENTER = 10L
        private const val SDK_WINDOW_LEAVE = 11L
        private const val CONVERT = "_ZN5Event15convertSDLEventERKNS_5StateERK9SDL_EventR12CompactDequeIS_Ef"
        private const val EMPLACE = "_ZN12CompactDequeI5EventE12emplace_backIJRdNS0_4TypeEEEERS0_DpOT_"

        fun resolve(image: ElfImage): PointerEventPayloads {
            val conversion = SdlButtonConversion.resolve(image)
            val header = conversion.header
            val function = image.symbol(CONVERT)
            val flow = X64ControlFlow.resolve(image, function)
            val grow = image.symbol("_ZN12CompactDequeI5EventE4growEj")
            val calls = flow.instructions.filter {
                it.operation == Operation.CALL && it.destination == Immediate(grow.address - function.address)
            }.map { it.offset }.toSet()
            val buttonWrites = IndexedEventStores.analyze(flow, header, flow.body.getValue(conversion.payload.store), calls)
            val button = indexed(flow, header, buttonWrites, SDK_BUTTON_EXTENT, listOf(20, 24), conversion.payload.code)
            val press = Case(OperationKind.PRESS, conversion.kinds.getValue(SdlButtonAdmission.Transition.PRESS),
                button.x, button.y, conversion.payload.code, defaults = button.defaults, timeStore = button.timeStore)
            val release = press.copy(operation = OperationKind.RELEASE,
                kind = conversion.kinds.getValue(SdlButtonAdmission.Transition.RELEASE))
            val motions = mutableListOf<Case>()
            val failures = mutableListOf<String>()
            for (store in flow.instructions.filter { it.offset in flow.reachable }) {
                val target = store.destination as? Memory ?: continue
                val source = store.source as? Register ?: continue
                if (target.width != 16 || source.width != 16 || store.operation != Operation.VECTOR_MOV) continue
                try {
                    val writes = IndexedEventStores.analyze(flow, header, store, calls)
                    val result = indexed(flow, header, writes, SDK_MOTION_EXTENT, listOf(20, 24, 28, 32), null)
                    val input = SysVArgumentFlow(flow)
                    val load = flow.instructions.single { instruction ->
                        instruction.offset in flow.reaching(store.offset).reachable &&
                                instruction.operation in listOf(Operation.MOV, Operation.SCALAR_MOV, Operation.VECTOR_MOV) &&
                                input.source(instruction.offset) ==
                                SysVArgumentFlow.Read(SysVArgumentFlow.Reference(2, 20), 16)
                    }
                    ScalarBranchPath(flow, mapOf(2 to SDK_MOTION_EXTENT)).to(load.offset) {
                        require(it.field == sdk(SDK_TYPE, 4)) { "SDL motion admission depends on another input" }
                        SDK_MOTION
                    }
                    val type = field(writes, header.type, 4)
                    require(type.source is Immediate)
                    motions += Case(OperationKind.MOVE, (type.source as Immediate).value, result.x, result.y,
                        defaults = result.defaults, timeStore = result.timeStore)
                } catch (failure: IllegalArgumentException) {
                    failures += "${store.offset}: ${failure.message}"
                } catch (failure: IllegalStateException) {
                    failures += "${store.offset}: ${failure.message}"
                } catch (failure: NoSuchElementException) {
                    failures += "${store.offset}: ${failure.message}"
                }
            }
            val motion = motions.singleOrNull() ?: error("SDL motion has no unique typed native producer: $failures")
            // Absolute motion leaves the relative lanes at the native constructor defaults, as on Windows.
            // Do not infer their zero value by multiplying a synthetic zero by an unobserved runtime scale.
            val motionDefaults = ReturnedEventDefaults.resolve(image, header, motion.kind)
            val relative = checkNotNull(motion.x) + 8
            require((relative until relative + 8).all { motion.defaults[it] == 0 && motionDefaults[it] == 0 }) {
                "Absolute native motion has no proven relative-coordinate defaults"
            }
            val tables = X64JumpTables.resolve(image, function)
            val emplace = image.symbol(EMPLACE)
            val wheelPath = SdlPointerPath.construction(flow, tables, emplace.address - function.address,
                SDK_WHEEL_EXTENT, mapOf(sdk(SDK_TYPE, 4) to SDK_WHEEL))
            val wheel = wheel(flow, wheelPath, header, ReturnedEventDefaults.resolve(image, header, wheelPath.kind))
            val enterPath = SdlPointerPath.construction(flow, tables, emplace.address - function.address,
                SDK_WINDOW_EXTENT, mapOf(sdk(SDK_TYPE, 4) to SDK_WINDOW, sdk(12, 1) to SDK_WINDOW_ENTER))
            val enter = Case(OperationKind.ENTER, enterPath.kind,
                defaults = ReturnedEventDefaults.resolve(image, header, enterPath.kind))
            val leavePath = SdlPointerPath.construction(flow, tables, emplace.address - function.address,
                SDK_WINDOW_EXTENT, mapOf(sdk(SDK_TYPE, 4) to SDK_WINDOW, sdk(12, 1) to SDK_WINDOW_LEAVE))
            val table = conversion.admission.table
            val codes = listOf(SdlButtonAdmission.Button.LEFT, SdlButtonAdmission.Button.RIGHT,
                SdlButtonAdmission.Button.MIDDLE, SdlButtonAdmission.Button.X1, SdlButtonAdmission.Button.X2)
                .map { checkNotNull(table.value(it.sdkValue)) }
            return PointerEventPayloads(header, codes, listOf(press, release, motion, wheel, enter), leavePath.kind)
        }

        private data class Indexed(val x: Long, val y: Long, val defaults: Map<Long, Int>, val timeStore: Long)

        private fun field(writes: List<Instruction>, offset: Long, width: Int): Instruction = writes.filter {
            val target = it.destination as Memory
            target.displacement < offset + width && offset < target.displacement + target.width
        }.single().also {
            val target = it.destination as Memory
            require(target.displacement == offset && target.width == width)
        }

        private fun indexed(
            flow: X64ControlFlow, header: EventHeader, writes: List<Instruction>, sdkExtent: Long,
            coordinates: List<Long>, code: Long?,
        ): Indexed {
            val type = field(writes, header.type, 4)
            val time = field(writes, header.time, 8)
            val codeStore = code?.let { field(writes, it, 4) }
            val defaults = mutableMapOf<Long, Int>()
            val scalar = ScalarExpression(flow, mapOf(2 to sdkExtent))
            var position: Pair<Long, Long>? = null
            for (write in writes) {
                if (write == type || write == time || write == codeStore) continue
                val target = write.destination as Memory
                val source = write.source
                if (source is Register && source.number in 16..31 && target.width == coordinates.size * 4) {
                    require(position == null)
                    require(SdlPointerCoordinates(flow.reaching(write.offset), sdkExtent).fields(write.offset, source) == coordinates)
                    position = target.displacement to target.displacement + 4
                    for (byte in target.displacement + 8 until target.displacement + target.width) defaults[byte] = 0
                } else {
                    require(write.operation == Operation.MOV && target.width in listOf(1, 2, 4, 8))
                    val constant = when (source) {
                        is Immediate -> source.value
                        is Register -> {
                            val expression = scalar.before(write.offset, source)
                            require(ScalarExpression.inputs(expression).map { it.field }.toSet() == setOf(sdk(SDK_WHICH, 4)))
                            ScalarExpression.evaluate(expression) { 0 }
                        }
                        else -> error("Mouse default has an unsupported source")
                    }
                    repeat(target.width) { byte -> defaults[target.displacement + byte] =
                        (constant ushr (byte * 8) and 255).toInt() }
                }
            }
            val (x, y) = checkNotNull(position) { "Native mouse payload lacks SDL pixel coordinates" }
            require((x until x + 8).none { it in defaults })
            return Indexed(x, y, defaults, time.offset)
        }

        private fun wheel(
            complete: X64ControlFlow, path: SdlPointerPath.Proof, header: EventHeader,
            constructorDefaults: Map<Long, Int>,
        ): Case {
            val writes = mutableListOf<Instruction>()
            var site = path.call + complete.body.getValue(path.call).size
            val pointers = mutableMapOf(0 to 0L)
            val offsets = mutableMapOf<Long, Long>()
            for (step in 0 until 128) {
                val instruction = complete.body.getValue(site)
                if (instruction.operation in listOf(Operation.CALL, Operation.JMP, Operation.JCC, Operation.RET))
                    break
                val target = instruction.destination
                if (target is Memory && instruction.operation !in listOf(Operation.CMP, Operation.TEST, Operation.SCALAR_COMPARE)) {
                    val offset = if (!target.relative && target.index == null) target.base?.let { pointers[it] }
                        ?.plus(target.displacement) else null
                    require(offset != null && offset >= 0 && offset <= header.extent - target.width)
                    require(instruction.operation in listOf(Operation.MOV, Operation.SCALAR_MOV, Operation.VECTOR_MOV))
                    writes += instruction
                    offsets[site] = offset
                } else if (target is Register && instruction.operation !in listOf(Operation.CMP, Operation.TEST, Operation.SCALAR_COMPARE)) {
                    val copied = when (val source = instruction.source) {
                        is Register -> pointers[source.number].takeIf {
                            instruction.operation == Operation.MOV && target.width == 8 && source.width == 8
                        }
                        is Memory -> if (instruction.operation == Operation.LEA && target.width == 8 &&
                            !source.relative && source.index == null)
                            source.base?.let { pointers[it] }?.plus(source.displacement) else null
                        else -> null
                    }
                    pointers.remove(target.number)
                    if (copied != null) {
                        require(copied in 0..header.extent.toLong())
                        pointers[target.number] = copied
                    }
                }
                val next = site + instruction.size
                require(complete.successors.getValue(site) == listOf(next) && complete.predecessors[next] == setOf(site))
                site = next
            }
            require(writes.isNotEmpty() && complete.body.getValue(site).operation in listOf(Operation.JMP, Operation.RET))
            val flow = complete.reaching(writes.last().offset)
            val scalar = ScalarExpression(flow, mapOf(2 to SDK_WHEEL_EXTENT))
            val borrows = mapOf(path.call to listOf(ConstructorValues.Borrow(6, 8), ConstructorValues.Borrow(2, 4)))
            val coordinates = SdlPointerCoordinates(flow, SDK_WHEEL_EXTENT, borrows)
            val defaults = constructorDefaults.toMutableMap()
            var position: Pair<Long, Long>? = null
            val directions = mutableListOf<Long>()
            for (write in writes) {
                val target = write.destination as Memory
                val at = offsets.getValue(write.offset)
                for (byte in at until at + target.width) defaults.remove(byte)
                val source = write.source
                if (source is Register && source.number in 16..31) {
                    require(target.width == 8 && position == null && coordinates.fields(write.offset, source) == listOf(36L, 40L))
                    position = at to at + 4
                } else {
                    require(target.width in listOf(1, 2, 4, 8))
                    val values = when (source) {
                        is Immediate -> listOf(source.value, source.value)
                        is Register -> {
                            val expression = scalar.before(write.offset, source)
                            require(ScalarExpression.inputs(expression).all { it.field in setOf(sdk(16, 4), sdk(20, 4)) })
                            listOf(-1L, 1L).map { direction -> ScalarExpression.evaluate(expression) {
                                if (it.field == sdk(16, 4)) 0 else direction
                            } }
                        }
                        else -> error("Native wheel scalar is not derived from SDL wheel inputs")
                    }
                    if (target.width == 4 && values == listOf(0xffffffffL, 1L)) directions += at
                    else {
                        require(values[0] == values[1]) { "Native wheel payload requires another dynamic field" }
                        repeat(target.width) { byte -> defaults[at + byte] = (values[0] ushr (byte * 8) and 255).toInt() }
                    }
                }
            }
            require(directions.size == 2 && directions.distinct().size == 2)
            val (x, y) = checkNotNull(position)
            return Case(OperationKind.WHEEL, path.kind, x, y, wheel = directions[0], wheelY = directions[1], defaults = defaults)
        }

        private fun sdk(offset: Long, width: Int) = SysVArgumentFlow.Read(SysVArgumentFlow.Reference(2, offset), width)
    }
}
