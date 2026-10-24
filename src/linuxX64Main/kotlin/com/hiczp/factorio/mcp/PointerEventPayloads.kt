package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Native scalar Event variants derived from the game's SDL3 input conversion. */
internal data class PointerEventPayloads(
    val header: EventHeader,
    val codes: List<Long>,
    val cases: List<Case>,
    val leaveKind: Long,
) {
    enum class OperationKind {
        PRESS,
        RELEASE,
        MOVE,
        WHEEL,
        ENTER,
    }

    data class Case(
        val operation: OperationKind,
        val kind: Long,
        val x: Long? = null,
        val y: Long? = null,
        val code: Long? = null,
        val wheel: Long? = null,
        val wheelY: Long? = null,
        val defaults: Map<Long, Int>,
        val timeStore: Long? = null,
    ) {
        fun dynamic(header: EventHeader) =
            listOf(header.type to 4, header.time to 8) +
                listOfNotNull(x, y, code, wheel, wheelY).map { it to 4 }

        fun required(header: EventHeader): Set<Long> =
            dynamic(header).flatMap { (at, width) -> (at until at + width).toList() }.toSet()

        fun initialized(header: EventHeader): Set<Long> = required(header) + defaults.keys
    }

    init {
        require(
            header.extent in 16..256 &&
                codes.size == 5 &&
                codes.distinct().size == 5 &&
                codes.all { it in 1..255 }
        )
        require(
            cases.map { it.operation } == OperationKind.entries &&
                cases.map { it.kind }.distinct().size == cases.size
        )
        require(leaveKind in 0..0xffffffffL && cases.none { it.kind == leaveKind })
        for (case in cases) {
            require(case.kind in 0..0xffffffffL && case.defaults.values.all { it in 0..255 })
            require(case.initialized(header).all { it in 0 until header.extent })
            val fields = case.dynamic(header)
            require(
                fields.sumOf { it.second } == case.required(header).size &&
                    case.required(header).intersect(case.defaults.keys).isEmpty()
            )
        }
    }

    companion object {
        fun resolve(image: ElfImage): PointerEventPayloads {
            val conversion = SdlButtonConversion.resolve(image)
            val header = conversion.header
            val function = image.symbol(SdlEvents.CONVERT)
            val tables = X64JumpTables.resolve(image, function)
            val complete = X64ControlFlow.resolve(image, function)
            val grow = image.symbol("_ZN12CompactDequeI5EventE4growEj")
            val calls =
                complete.instructions
                    .filter {
                        it.operation == Operation.CALL &&
                            it.destination == Immediate(grow.address - function.address)
                    }
                    .map { it.offset }
                    .toSet()
            fun selected(type: Long) = SdlEventCase.select(complete, tables, type)
            val enter =
                indexed(
                    selected(SdlEvents.WINDOW_ENTER),
                    header,
                    calls,
                    OperationKind.ENTER,
                    SdlEvents.WINDOW_EXTENT,
                    emptyList(),
                    emptyMap(),
                )
            val leave =
                indexed(
                    selected(SdlEvents.WINDOW_LEAVE),
                    header,
                    calls,
                    OperationKind.ENTER,
                    SdlEvents.WINDOW_EXTENT,
                    emptyList(),
                    emptyMap(),
                )
            val motion =
                indexed(
                    selected(SdlEvents.MOTION),
                    header,
                    calls,
                    OperationKind.MOVE,
                    SdlEvents.MOTION_EXTENT,
                    listOf(28, 32, 36, 40),
                    enter.defaults,
                )
            val wheel =
                indexed(
                    selected(SdlEvents.WHEEL),
                    header,
                    calls,
                    OperationKind.WHEEL,
                    SdlEvents.WHEEL_EXTENT,
                    listOf(36, 40),
                    emptyMap(),
                )
            val button = conversion.payload
            val press =
                Case(
                    OperationKind.PRESS,
                    conversion.kinds.getValue(SdlButtonAdmission.Transition.PRESS),
                    button.x,
                    button.y,
                    button.code,
                    defaults = button.defaults,
                    timeStore = button.timeStore,
                )
            val release =
                press.copy(
                    operation = OperationKind.RELEASE,
                    kind = conversion.kinds.getValue(SdlButtonAdmission.Transition.RELEASE),
                )
            val codes =
                listOf(
                        SdlButtonAdmission.Button.LEFT,
                        SdlButtonAdmission.Button.RIGHT,
                        SdlButtonAdmission.Button.MIDDLE,
                        SdlButtonAdmission.Button.X1,
                        SdlButtonAdmission.Button.X2,
                    )
                    .map { checkNotNull(conversion.admission.table.value(it.sdkValue)) }
            return PointerEventPayloads(
                header,
                codes,
                listOf(press, release, motion, wheel, enter),
                leave.kind,
            )
        }

        internal fun indexed(
            flow: X64ControlFlow,
            header: EventHeader,
            queueCalls: Set<Long>,
            operation: OperationKind,
            sdkExtent: Long,
            coordinateFields: List<Long>,
            constructorDefaults: Map<Long, Int>,
        ): Case {
            val type =
                flow.instructions.single {
                    it.offset in flow.reachable &&
                        it.operation == Operation.MOV &&
                        it.source is Immediate &&
                        (it.destination as? Memory)?.let { target ->
                            target.index != null &&
                                target.width == 4 &&
                                target.displacement == header.type
                        } == true
                }
            val writes =
                IndexedEventStores.analyze(flow, header, type, queueCalls, followJumps = true)
            val time =
                writes.single {
                    (it.destination as Memory).let { target ->
                        target.displacement == header.time && target.width == 8
                    }
                }
            val definitions = ScalarExpression(flow, mapOf(SdlEvents.ARGUMENT to sdkExtent))
            val defaults = mutableMapOf<Long, Int>()
            val dynamic = mutableMapOf<Long, String>()
            val directions = mutableListOf<Long>()
            var position: Pair<Long, Long>? = null
            for (write in writes) {
                if (write == type || write == time) continue
                val target = write.destination as Memory
                val source = write.source
                for (byte in target.displacement until target.displacement + target.width) {
                    defaults.remove(byte)
                    require(byte !in dynamic) {
                        "A native pointer field is overwritten after its proven producer"
                    }
                }
                val zero =
                    source is Register &&
                        runCatching {
                                definitions.definition(write.offset, source.number).let {
                                    it.operation in setOf(Operation.XOR, Operation.VECTOR_XOR) &&
                                        it.destination == it.source
                                }
                            }
                            .getOrDefault(false)
                if (source is Register && source.number in 16..31 && !zero) {
                    require(
                        position == null &&
                            coordinateFields.isNotEmpty() &&
                            target.width == coordinateFields.size * 4
                    )
                    require(
                        SdlPointerCoordinates(flow.reaching(write.offset), sdkExtent)
                            .fields(write.offset, source) == coordinateFields
                    )
                    position = target.displacement to target.displacement + 4
                    repeat(8) { dynamic[target.displacement + it] = "position" }
                    // Absolute motion uses the independently observed MouseEvent defaults for
                    // relative lanes.
                    // An unobserved scale multiplied by a synthetic zero does not establish a
                    // native default.
                    for (byte in target.displacement + 8 until target.displacement + target.width) {
                        require(constructorDefaults[byte] == 0) {
                            "Relative mouse lane has no native zero default"
                        }
                        defaults[byte] = 0
                    }
                    continue
                }
                val constant =
                    if (zero) 0L
                    else
                        when (source) {
                            is Immediate -> source.value
                            is Register -> {
                                val expression = definitions.before(write.offset, source)
                                if (operation == OperationKind.WHEEL) {
                                    when (wheelValue(expression)) {
                                        WheelValue.ZERO -> 0L
                                        WheelValue.VERTICAL -> {
                                            require(target.width == 4)
                                            directions += target.displacement
                                            repeat(4) {
                                                dynamic[target.displacement + it] = "wheel"
                                            }
                                            continue
                                        }
                                    }
                                } else {
                                    require(
                                        ScalarExpression.inputs(expression).all {
                                            it.field == SdlEvents.field(SdlEvents.MOUSE_WHICH, 4)
                                        }
                                    )
                                    ScalarExpression.evaluate(expression) { 0 }
                                }
                            }
                            else -> error("Pointer default has an unsupported producer")
                        }
                require(target.width <= 8 || constant == 0L)
                repeat(target.width) { byte ->
                    defaults[target.displacement + byte] =
                        if (byte >= 8) 0 else (constant ushr (byte * 8) and 255).toInt()
                }
            }
            require((position != null) == coordinateFields.isNotEmpty())
            require(directions.size == if (operation == OperationKind.WHEEL) 2 else 0)
            return Case(
                operation,
                (type.source as Immediate).value,
                position?.first,
                position?.second,
                wheel = directions.getOrNull(0),
                wheelY = directions.getOrNull(1),
                defaults = defaults,
                timeStore = time.offset,
            )
        }

        internal enum class WheelValue {
            ZERO,
            VERTICAL,
        }

        /** Exact for every signed wheel delta; this is not a sample of two directions. */
        internal fun wheelValue(value: ScalarExpression.Value): WheelValue {
            require(value.width in setOf(1, 4))
            return when (value) {
                is ScalarExpression.Literal -> {
                    require(value.value == 0L)
                    WheelValue.ZERO
                }
                is ScalarExpression.Input -> {
                    require(value.width == 4)
                    when (value.field) {
                        SdlEvents.field(SdlEvents.WHEEL_X, 4) -> WheelValue.ZERO
                        SdlEvents.field(SdlEvents.WHEEL_Y, 4) -> WheelValue.VERTICAL
                        else -> error("Wheel expression reads an unrelated field")
                    }
                }
                is ScalarExpression.Narrow -> {
                    require(value.width == 4 && value.value.width == 4)
                    wheelValue(value.value)
                }
                is ScalarExpression.Binary -> {
                    val left = wheelValue(value.left)
                    val right = wheelValue(value.right)
                    when {
                        value.operation == Operation.SUB && right == WheelValue.ZERO -> left
                        value.operation == Operation.AND && left == right -> left
                        else -> error("Wheel scalar is not an unchanged vertical delta")
                    }
                }
                is ScalarExpression.Select -> {
                    val left = wheelValue(value.left)
                    val right = wheelValue(value.right)
                    val yes = wheelValue(value.yes)
                    val no = wheelValue(value.no)
                    require(value.condition in setOf(4, 5))
                    when {
                        left == right -> if (value.condition == 4) yes else no
                        yes == no -> yes
                        value.condition == 4 -> no
                        else -> yes
                    }
                }
            }
        }
    }
}
