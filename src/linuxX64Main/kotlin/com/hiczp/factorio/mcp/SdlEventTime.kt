package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** SDL2's Uint32 millisecond timestamp, through the game's own floating conversion into the selected Event store. */
internal data class SdlEventTime(val ticks: ElfImage.Symbol, val divisor: Double) {
    companion object {
        fun resolve(image: ElfImage, payload: KeyboardEventPayload): SdlEventTime {
            val function = image.symbol("_ZN5Event15convertSDLEventERKNS_5StateERK9SDL_EventR12CompactDequeIS_Ef")
            val flow = X64ControlFlow.resolve(image, function)
            val divisor = analyze(flow, function.address, payload.timeStore) { address ->
                require(image.sections.any {
                    it.flags and 3L == 2L && address >= it.address &&
                            it.size >= 8 && address - it.address <= it.size - 8
                })
                Double.fromBits(image.virtualBytes(address, 8).unsigned(0, 8))
            }
            val ticks = image.symbol("SDL_GetTicks")
            EhFrames(image).function(ticks)
            image.functionBytes(ticks, 4096)
            return SdlEventTime(ticks, divisor)
        }

        fun analyze(complete: X64ControlFlow, address: Long, timeStore: Long, literal: (Long) -> Double): Double {
            val flow = complete.reaching(timeStore)
            val store = flow.body.getValue(timeStore)
            require(store.operation == Operation.SCALAR_MOV && (store.destination as? Memory)?.width == 8)
            val output = store.source as? Register ?: error("Event time store has no scalar register source")
            require(output.number in 16..31 && output.width == 8)
            val definitions = ScalarExpression(flow)
            val arguments = SysVArgumentFlow(flow)
            val candidates = mutableListOf<Double>()
            val failures = mutableListOf<String>()
            for (division in flow.instructions.filter { it.offset in flow.reachable && it.operation == Operation.DOUBLE_DIVIDE }) {
                try {
                    val target = division.destination as Register
                    val constant = division.source as Memory
                    require(
                        target.number in 16..31 && target.width == 8 && constant.width == 8 &&
                                constant.relative && constant.base == null && constant.index == null
                    )
                    val conversion = flow.body.getValue(
                        flow.predecessors[division.offset]?.singleOrNull()
                            ?: error("Timestamp division has no unique conversion predecessor")
                    )
                    require(conversion.operation == Operation.INT_TO_DOUBLE && conversion.destination == target)
                    val integer = conversion.source as Register
                    require(integer.number in 0..15 && integer.width == 8)
                    val load = definitions.definition(conversion.offset, integer.number)
                    require(
                        load.operation == Operation.MOV && load.destination == Register(integer.number, 4) &&
                                arguments.source(load.offset) ==
                                SysVArgumentFlow.Read(SysVArgumentFlow.Reference(2, 4), 4)
                    )
                    val value = PrivateValueCopies.Read(division.offset, InlineArgumentFields.Field(0, 8))
                    val copies = PrivateValueCopies(flow, emptyMap(), mapOf(division.offset to value))
                    require(copies.field(timeStore, output) == value)
                    val next = address + division.offset + division.size
                    require(
                        address >= 0 && next >= address && constant.displacement >= -next &&
                                constant.displacement <= Long.MAX_VALUE - next
                    )
                    val divisor = literal(next + constant.displacement)
                    require(divisor.isFinite() && divisor > 0)
                    candidates += divisor
                } catch (failure: IllegalArgumentException) {
                    failures += "${division.offset}: ${failure.message}"
                } catch (failure: IllegalStateException) {
                    failures += "${division.offset}: ${failure.message}"
                } catch (failure: NoSuchElementException) {
                    failures += "${division.offset}: ${failure.message}"
                }
            }
            return candidates.singleOrNull() ?: error("Event time has no unique SDL timestamp conversion: $failures")
        }
    }
}
