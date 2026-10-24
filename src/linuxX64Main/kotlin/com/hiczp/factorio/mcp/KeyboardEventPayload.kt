package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/**
 * Nonrepeat keyboard defaults and SDL modifier bits, tied to the typed InputState scancode field.
 */
internal data class KeyboardEventPayload(
    val defaults: Map<Long, Int>,
    val modifiers: Long,
    val timeStore: Long,
) {
    fun required(header: EventHeader, code: Long): Set<Long> =
        (header.type until header.type + 4).toSet() +
            (header.time until header.time + 8) +
            (code until code + 4) +
            (modifiers until modifiers + 2)

    fun initialized(header: EventHeader, code: Long): Set<Long> =
        defaults.keys + required(header, code)

    companion object {
        fun resolve(
            image: ElfImage,
            header: EventHeader,
            update: InputStateKeyUpdate,
        ): KeyboardEventPayload {
            val function = image.symbol(SdlEvents.CONVERT)
            val flow = X64ControlFlow.resolve(image, function)
            val grow = image.symbol("_ZN12CompactDequeI5EventE4growEj")
            val calls =
                flow.instructions
                    .filter {
                        it.operation == Operation.CALL &&
                            it.destination == Immediate(grow.address - function.address)
                    }
                    .map { it.offset }
                    .toSet()
            return analyze(flow, header, update.code, update.press.kind, update.release.kind, calls)
        }

        fun analyze(
            flow: X64ControlFlow,
            header: EventHeader,
            code: Long,
            press: Long,
            release: Long,
            queueCalls: Set<Long>,
        ): KeyboardEventPayload {
            require(header.extent in 16..4096 && code in 0..header.extent - 4L && press != release)
            val scalars =
                ScalarExpression(flow, mapOf(SdlEvents.ARGUMENT to SdlEvents.KEYBOARD_EXTENT))
            val bits = ScalarBits(flow, mapOf(SdlEvents.ARGUMENT to SdlEvents.KEYBOARD_EXTENT))
            val sdkType = SdlEvents.field(SdlEvents.TYPE, 4)
            val sdkModifiers = SdlEvents.field(SdlEvents.KEY_MODIFIERS, 2)
            val sdkRepeat = SdlEvents.field(SdlEvents.KEY_REPEAT, 1)
            val candidates = mutableListOf<KeyboardEventPayload>()
            val failures = mutableListOf<String>()
            for (store in flow.instructions) {
                val output = store.destination as? Memory ?: continue
                if (
                    store.operation != Operation.MOV ||
                        output.width !in setOf(4, 8) ||
                        output.displacement != code ||
                        store.source !is Register ||
                        store.offset !in flow.reachable
                )
                    continue
                try {
                    val writes = IndexedEventStores.analyze(flow, header, store, queueCalls)
                    fun field(offset: Long, width: Int) =
                        writes
                            .filter {
                                val target = it.destination as Memory
                                target.displacement < offset + width &&
                                    offset < target.displacement + target.width
                            }
                            .single()
                            .also {
                                val target = it.destination as Memory
                                require(target.displacement == offset && target.width == width)
                            }
                    val type = field(header.type, 4)
                    val time = field(header.time, 8)
                    val typeRegister =
                        type.source as? Register
                            ?: error("Candidate event kind is not derived from the SDL type")
                    val kind = scalars.before(type.offset, typeRegister)
                    require(
                        ScalarExpression.inputs(kind).map { it.field }.toSet() == setOf(sdkType)
                    )
                    for ((sdk, native) in
                        listOf(SdlEvents.KEY_DOWN to press, SdlEvents.KEY_UP to release)) require(
                        ScalarExpression.evaluate(kind) { sdk } == native
                    )
                    val payload = mutableMapOf<Long, ScalarBits.Bit>()
                    for (write in writes) {
                        if (write == type || write == time) continue
                        val target = write.destination as Memory
                        require(
                            write.operation == Operation.MOV && target.width in setOf(1, 2, 4, 8)
                        )
                        repeat(target.width * 8) { bit ->
                            val at = target.displacement * 8 + bit
                            if (at in code * 8 until (code + 4) * 8) require(write == store)
                            else payload[at] = bits.operand(write.offset, write.source, bit)
                        }
                    }
                    val modifiers =
                        (0L..header.extent - 2).single { offset ->
                            (0 until 16).all {
                                payload[offset * 8 + it] == ScalarBits.Input(sdkModifiers, it)
                            }
                        }
                    val defaults = mutableMapOf<Long, Int>()
                    for (offset in payload.keys.map { it / 8 }.toSet()) {
                        if (offset in modifiers until modifiers + 2) continue
                        var value = 0
                        repeat(8) { bit ->
                            when (val input = payload.getValue(offset * 8 + bit)) {
                                is ScalarBits.Constant ->
                                    if (input.value) value = value or (1 shl bit)
                                is ScalarBits.Input ->
                                    require(input.field == sdkRepeat) {
                                        "Keyboard default reads an unrelated SDK field"
                                    }
                            }
                        }
                        defaults[offset] = value
                    }
                    val fields =
                        listOf(header.type to 4, header.time to 8, code to 4, modifiers to 2)
                    require(
                        fields.all { (at, width) -> at in 0..header.extent - width } &&
                            fields.indices.all { a ->
                                fields.indices.all { b ->
                                    a == b ||
                                        fields[a].first + fields[a].second <= fields[b].first ||
                                        fields[b].first + fields[b].second <= fields[a].first
                                }
                            }
                    )
                    require(
                        defaults.isNotEmpty() &&
                            defaults.keys.none { at ->
                                fields.any { (offset, width) -> at in offset until offset + width }
                            }
                    )
                    candidates += KeyboardEventPayload(defaults, modifiers, time.offset)
                } catch (failure: IllegalArgumentException) {
                    failures += "${store.offset}: ${failure.message}"
                } catch (failure: IllegalStateException) {
                    failures += "${store.offset}: ${failure.message}"
                } catch (failure: NoSuchElementException) {
                    failures += "${store.offset}: ${failure.message}"
                }
            }
            return candidates.singleOrNull()
                ?: error("Keyboard payload has no unique typed store block: $failures")
        }
    }
}
