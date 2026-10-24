package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Typed SDL button construction. No allocating input lookup or event conversion is executed. */
internal data class SdlButtonConversion(
    val header: EventHeader,
    val admission: SdlButtonAdmission.Proof,
    val payload: Payload,
    val kinds: Map<SdlButtonAdmission.Transition, Long>,
) {
    data class Payload(
        val code: Long,
        val x: Long,
        val y: Long,
        val defaults: Map<Long, Int>,
        val timeStore: Long,
    )

    companion object {
        fun resolve(image: ElfImage): SdlButtonConversion {
            val function = image.symbol(SdlEvents.CONVERT)
            val complete = X64ControlFlow.resolve(image, function)
            val table = GuardedByteTable.resolve(image, function).single()
            val admission = SdlButtonAdmission.analyze(complete, table)
            val header = EventHeader.resolve(image)
            val copy = MousePayloadCopy.resolve(image, header)
            val constructor = image.symbol(SdlEvents.MOUSE_EMPLACE)
            val call =
                complete.instructions
                    .single {
                        it.offset in complete.reachable &&
                            it.operation == Operation.CALL &&
                            it.destination == Immediate(constructor.address - function.address)
                    }
                    .offset
            val flow = complete.reaching(call)
            require(flow.dominates(table.load, call))
            require(SysVArgumentFlow(flow).register(call, 7) == SysVArgumentFlow.Reference(6)) {
                "Mouse construction does not receive the original queue"
            }
            val scalar = ScalarExpression(flow, SdlButtonAdmission.extents(table))
            val kinds = SdlButtonAdmission.kinds(table, scalar.before(call, Register(6, 4)))
            val codeValue = PrivateValueCopies.Read(table.load, NativeField(0, 4))
            val codeCopies =
                PrivateValueCopies(flow, mapOf(table.load to codeValue), terminalConsumer = call)
            val coordinates =
                SdlPointerCoordinates(flow, SdlEvents.BUTTON_EXTENT, terminalConsumer = call)
            val used = copy.bytes.values.toSet()
            val fields =
                (0L..copy.extent - 4L).filter { offset ->
                    (offset until offset + 4).all { it in used }
                }
            val code =
                fields.single { offset ->
                    runCatching { codeCopies.terminalField(2, copy.extent, offset, 4) == codeValue }
                        .getOrDefault(false)
                }
            fun coordinate(sdk: Long) =
                fields.single { offset ->
                    runCatching {
                            coordinates.terminalFields(2, copy.extent, offset, 4) == listOf(sdk)
                        }
                        .getOrDefault(false)
                }
            val x = coordinate(SdlEvents.MOUSE_X)
            val y = coordinate(SdlEvents.MOUSE_Y)
            val dynamic = listOf(x, y, code).flatMap { (it until it + 4).toList() }.toSet()
            require(dynamic.size == 12)
            val defaults = copy.defaults.toMutableMap()
            val stores = PrivateByteStores(flow, call).bytes(2, copy.extent, used - dynamic)
            val values = mutableMapOf<Long, Long>()
            for ((output, input) in copy.bytes) {
                if (input in dynamic) continue
                val byte = stores.getValue(input)
                val value =
                    values.getOrPut(byte.store.offset) {
                        if (byte.store.operation == Operation.MOV && byte.store.source is Immediate)
                            return@getOrPut byte.store.source.value
                        val expression =
                            if (byte.store.operation == Operation.SET) scalar.set(byte.store.offset)
                            else {
                                require(byte.store.operation == Operation.MOV)
                                when (val source = byte.store.source) {
                                    is Register -> scalar.before(byte.store.offset, source)
                                    else -> error("Mouse default lacks a scalar producer")
                                }
                            }
                        require(
                            ScalarExpression.inputs(expression).all {
                                it.field == SdlEvents.field(SdlEvents.MOUSE_WHICH, 4)
                            }
                        )
                        ScalarExpression.evaluate(expression) { 0 }
                    }
                defaults[output] = (value ushr (byte.index * 8) and 255).toInt()
            }
            return SdlButtonConversion(
                header,
                admission,
                Payload(copy.field(code, 4), copy.field(x, 4), copy.field(y, 4), defaults, call),
                kinds,
            )
        }
    }
}
