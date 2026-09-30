package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Native key and extended-key values from the actual keyboard event producer, including control-key overrides. */
internal object KeyInputConversion {
    data class Value(val key: Int, val extended: Int)

    fun nativeKey(image: ElfImage, input: Int): Int = NativeScalarFunction.evaluate(
        image,
        image.symbol("_ZN16InputHandlerAgui17getKeyFromKeycodeEi"), input
    )

    fun resolve(image: ElfImage, fields: KeyInputFields, codes: Set<Int>): Map<Int, Value> {
        require(codes.isNotEmpty() && codes.size <= 32 && codes.all { it in 1..127 })
        val function = image.symbol("_ZN16InputHandlerAgui14createKeyboardERK5Eventbb")
        val caller = image.symbol("_ZN16InputHandlerAgui12processEventERK5Event")
        val callerFlow = X64ControlFlow.resolve(image, caller)
        val queue = image.symbol("_ZN4agui5Input17pushKeyboardEventERKNS_13KeyboardInputE")
        val create = callerFlow.instructions.single {
            it.operation == Operation.CALL &&
                    it.destination == Immediate(function.address - caller.address)
        }
        val nextCall = callerFlow.instructions.dropWhile { it.offset <= create.offset }
            .first { it.operation == Operation.CALL }
        require(nextCall.destination == Immediate(queue.address - caller.address))
        val between = callerFlow.instructions.filter { it.offset > create.offset && it.offset < nextCall.offset }
        require(between.all { it.operation == Operation.MOV && it.destination is Register && it.source is Register } &&
                (listOf(create) + between).all { callerFlow.successors.getValue(it.offset) == listOf(it.offset + it.size) }) {
            "Keyboard producer is not directly submitted to its native input queue"
        }
        val extent = maxOf(fields.key + 4, fields.extended + 4).toInt()
        val callerFrame = SysVLocalArgument(callerFlow)
        require(callerFrame.argument(create.offset, 7, extent) == callerFrame.argument(nextCall.offset, 6, extent)) {
            "Keyboard queue receives a different aggregate than the producer output"
        }
        val flow = X64ControlFlow.resolve(image, function)
        val provenance = ConstructorValues(flow, emptyMap())
        fun output(field: Long): Long = flow.instructions.single { instruction ->
            val memory = instruction.destination as? Memory
            val receiver =
                memory?.base?.let { provenance.register(instruction.offset, it) } as? ConstructorValues.Argument
            instruction.operation == Operation.MOV && memory != null && memory.width == 4 &&
                    !memory.relative && memory.index == null && receiver?.register == 7 &&
                    receiver.adjustment + memory.displacement == field
        }.offset

        val key = output(fields.key)
        val extended = output(fields.extended)
        require(key != extended)
        val conversion = image.symbol("_ZN16InputHandlerAgui17getKeyFromKeycodeEi")
        val call = flow.instructions.single {
            it.operation == Operation.CALL &&
                    it.destination == Immediate(conversion.address - function.address)
        }
        val definition = ScalarExpression(flow).definition(call.offset, 7)
        require(definition.operation == Operation.MOV && definition.destination == Register(7, 4))
        val input = definition.source as? Register ?: error("Keyboard conversion has no scalar source register")
        return codes.associateWith { code ->
            val values = ScalarOutputSlice.evaluate(
                flow, definition.offset, input, code, call.offset,
                { KeyInputConversion.nativeKey(image, it) }, setOf(key, extended)
            )
            Value(values.getValue(key).toInt(), values.getValue(extended).toInt())
        }
    }
}
