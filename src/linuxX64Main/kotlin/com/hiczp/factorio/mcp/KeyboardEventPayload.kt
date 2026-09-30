package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Scalar defaults of a native nonrepeat keyboard event, associated with the typed InputState scancode field. */
internal data class KeyboardEventPayload(val defaults: Map<Long, Int>, val timeStore: Long) {
    companion object {
        // Public SDL2 ABI: SDL_KeyboardEvent and SDL_KEYDOWN/SDL_KEYUP.
        // https://github.com/libsdl-org/SDL/blob/SDL2/include/SDL_events.h
        private const val SDK_EXTENT = 32L
        private const val SDK_REPEAT = 13L
        private const val SDK_DOWN = 0x300L
        private const val SDK_UP = 0x301L

        fun resolve(image: ElfImage, header: EventHeader, update: InputStateKeyUpdate): KeyboardEventPayload {
            val function = image.symbol("_ZN5Event15convertSDLEventERKNS_5StateERK9SDL_EventR12CompactDequeIS_Ef")
            val flow = X64ControlFlow.resolve(image, function)
            val grow = image.symbol("_ZN12CompactDequeI5EventE4growEj")
            val calls = flow.instructions.filter {
                it.operation == Operation.CALL &&
                        it.destination == Immediate(grow.address - function.address)
            }.map { it.offset }.toSet()
            return analyze(flow, header, update.code, update.press.kind, update.release.kind, calls)
        }

        fun analyze(
            flow: X64ControlFlow, header: EventHeader, code: Long, press: Long, release: Long,
            queueCalls: Set<Long>
        ): KeyboardEventPayload {
            require(header.extent in 16..4096 && code in 0..header.extent - 8L && press != release)
            val fields = listOf(header.type to 4, header.time to 8, code to 8)
            require(fields.all { it.first >= 0 && it.first <= header.extent - it.second } &&
                    fields.indices.all { a ->
                        fields.indices.all { b ->
                            a == b ||
                                    fields[a].first + fields[a].second <= fields[b].first ||
                                    fields[b].first + fields[b].second <= fields[a].first
                        }
                    })
            val scalars = ScalarExpression(flow, mapOf(2 to SDK_EXTENT))
            val sdkType = SysVArgumentFlow.Read(SysVArgumentFlow.Reference(2, 0), 4)
            val sdkRepeat = SysVArgumentFlow.Read(SysVArgumentFlow.Reference(2, SDK_REPEAT), 1)
            val candidates = mutableListOf<KeyboardEventPayload>()
            val failures = mutableListOf<String>()
            for (store in flow.instructions) {
                val output = store.destination as? Memory ?: continue
                val source = store.source as? Register ?: continue
                if (store.operation != Operation.MOV || output.width != 8 || output.displacement != code ||
                    source.width != 8 || store.offset !in flow.reachable
                ) continue
                try {
                    val writes = IndexedEventStores.analyze(flow, header, store, queueCalls)
                    fun field(offset: Long, width: Int) = writes.filter {
                        val target = it.destination as Memory
                        target.displacement < offset + width && offset < target.displacement + target.width
                    }.single().also {
                        val target = it.destination as Memory
                        require(target.displacement == offset && target.width == width)
                    }

                    val type = field(header.type, 4)
                    field(header.time, 8)
                    require(field(code, 8) == store)
                    val kind = scalars.before(type.offset, type.source as Register)
                    require(ScalarExpression.inputs(kind).map { it.field }.toSet() == setOf(sdkType))
                    for ((sdk, native) in listOf(SDK_DOWN to press, SDK_UP to release))
                        require(ScalarExpression.evaluate(kind) { sdk } == native)
                    val definition = scalars.definition(store.offset, source.number)
                    require(
                        definition.operation == Operation.MOV && definition.destination == Register(source.number, 4) &&
                                (definition.source as? Register)?.width == 4
                    ) { "Keyboard code does not have proven zero upper bytes" }
                    val defaults = mutableMapOf<Long, Int>()
                    for (write in writes) {
                        val target = write.destination as Memory
                        if (write == type || write == field(header.time, 8)) continue
                        if (write == store) {
                            for (offset in code + 4 until code + 8) defaults[offset] = 0
                            continue
                        }
                        require(write.operation == Operation.MOV && target.width in listOf(1, 2, 4, 8))
                        val value = when (val input = write.source) {
                            is Immediate -> input.value
                            is Register -> {
                                val expression = scalars.before(write.offset, input)
                                require(ScalarExpression.inputs(expression).map { it.field }
                                    .toSet() == setOf(sdkRepeat))
                                ScalarExpression.evaluate(expression) { 0 }
                            }

                            else -> error("Keyboard default has an unsupported source")
                        }
                        repeat(target.width) { byte ->
                            defaults[target.displacement + byte] = (value ushr (byte * 8) and 255).toInt()
                        }
                    }
                    require(defaults.isNotEmpty() && defaults.keys.none { it in code until code + 4 })
                    candidates += KeyboardEventPayload(defaults, field(header.time, 8).offset)
                } catch (failure: IllegalArgumentException) {
                    failures += "${store.offset}: ${failure.message}"
                } catch (failure: IllegalStateException) {
                    failures += "${store.offset}: ${failure.message}"
                } catch (failure: NoSuchElementException) {
                    failures += "${store.offset}: ${failure.message}"
                }
            }
            return candidates.singleOrNull() ?: error("Keyboard payload has no unique typed store block: $failures")
        }
    }
}
