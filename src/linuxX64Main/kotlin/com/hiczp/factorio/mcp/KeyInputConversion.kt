package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/**
 * Native key and extended-key values from the actual keyboard event producer, including control-key
 * overrides.
 */
internal object KeyInputConversion {
    data class Value(val key: Int, val extended: Int)

    fun nativeKey(image: ElfImage, input: Int): Int =
        NativeScalarFunction.evaluate(
            image,
            image.symbol("_ZN16InputHandlerAgui17getKeyFromKeycodeEj"),
            input,
        )

    fun resolve(image: ElfImage, fields: KeyInputFields, codes: Set<Int>): Map<Int, Value> {
        require(codes.isNotEmpty() && codes.size <= 32 && codes.all { it in 1..127 })
        val function = image.symbol("_ZNK16InputHandlerAgui14createKeyboardERK5Event")
        val caller = image.symbol("_ZN16InputHandlerAgui12processEventERK5Event")
        val callerFlow = X64ControlFlow.resolve(image, caller)
        val queue = image.symbol("_ZN4agui5Input17pushKeyboardEventERKNS_13KeyboardInputE")
        val create =
            callerFlow.instructions.single {
                it.operation == Operation.CALL &&
                    it.destination == Immediate(function.address - caller.address)
            }
        val nextCall =
            callerFlow.instructions.single {
                it.offset in callerFlow.reachable &&
                    it.operation == Operation.CALL &&
                    it.destination == Immediate(queue.address - caller.address)
            }
        require(
            nextCall.offset > create.offset && callerFlow.dominates(create.offset, nextCall.offset)
        )
        val between =
            callerFlow.instructions.filter {
                it.offset > create.offset && it.offset < nextCall.offset
            }
        require(
            between.all {
                it.operation == Operation.NOP ||
                    it.operation in setOf(Operation.MOV, Operation.LEA) &&
                        it.destination is Register &&
                        (it.source is Register || it.operation == Operation.LEA)
            } &&
                (listOf(create) + between).all {
                    callerFlow.successors.getValue(it.offset) == listOf(it.offset + it.size)
                }
        ) {
            "Keyboard producer is not directly submitted to its native input queue"
        }
        val extent = maxOf(fields.key + 4, fields.extended + 4).toInt()
        val callerFrame = SysVLocalArgument(callerFlow)
        require(
            callerFrame.argument(create.offset, 7, extent) ==
                callerFrame.argument(nextCall.offset, 6, extent)
        ) {
            "Keyboard queue receives a different aggregate than the producer output"
        }
        val flow = X64ControlFlow.resolve(image, function)
        val provenance = ConstructorValues(flow, emptyMap())
        fun output(field: Long): Long =
            flow.instructions
                .single { instruction ->
                    val memory = instruction.destination as? Memory
                    val receiver =
                        memory?.base?.let { provenance.register(instruction.offset, it) }
                            as? ConstructorValues.Argument
                    instruction.operation == Operation.MOV &&
                        memory != null &&
                        memory.width == 4 &&
                        !memory.relative &&
                        memory.index == null &&
                        receiver?.register == 7 &&
                        receiver.adjustment + memory.displacement == field
                }
                .offset

        val key = output(fields.key)
        val extended = output(fields.extended)
        require(key != extended)
        val conversion = image.symbol("_ZN16InputHandlerAgui17getKeyFromKeycodeEj")
        val call =
            flow.instructions.single {
                it.operation == Operation.CALL &&
                    it.destination == Immediate(conversion.address - function.address)
            }
        val definitions = ScalarExpression(flow)
        val definition = definitions.definition(call.offset, 7)
        require(definition.operation == Operation.MOV && definition.destination == Register(7, 4))
        val input =
            definition.source as? Register
                ?: error("Keyboard conversion has no scalar source register")
        require(input.width == 4)
        // The normalized SDL keycode can have multiple saved copies before conversion. Associate
        // those copies
        // through their common producer instead of assuming one particular preserved register.
        var site = definition.offset
        var register = input.number
        val visited = mutableSetOf<Pair<Long, Int>>()
        var producer: Long? = null
        while (producer == null) {
            require(visited.size < 32 && visited.add(site to register)) {
                "Keyboard keycode copy chain exceeds bounds"
            }
            val previous = definitions.definition(site, register)
            if (previous.operation == Operation.CALL) {
                require(register == 0) {
                    "Keyboard keycode does not originate in a scalar call result"
                }
                producer = previous.offset
            } else {
                require(
                    previous.operation == Operation.MOV &&
                        previous.destination == Register(register, 4)
                )
                val source =
                    previous.source as? Register
                        ?: error("Keyboard keycode has a non-scalar producer")
                require(source.width == 4)
                site = previous.offset
                register = source.number
            }
        }
        val producedAt = checkNotNull(producer)
        val origin = PrivateValueCopies.Read(producedAt, NativeField(0, 4))
        val copies = PrivateValueCopies(flow, emptyMap(), callResults = mapOf(producedAt to origin))
        val aliases =
            (0..15)
                .filter { candidate ->
                    try {
                        copies.field(definition.offset, Register(candidate, 4)) == origin
                    } catch (_: IllegalArgumentException) {
                        false
                    } catch (_: IllegalStateException) {
                        false
                    }
                }
                .toSet()
        require(input.number in aliases)
        return codes.associateWith { code ->
            val values =
                ScalarOutputSlice.evaluate(
                    flow,
                    definition.offset,
                    input,
                    code,
                    call.offset,
                    { KeyInputConversion.nativeKey(image, it) },
                    setOf(key, extended),
                    aliases,
                )
            Value(values.getValue(key).toInt(), values.getValue(extended).toInt())
        }
    }
}
