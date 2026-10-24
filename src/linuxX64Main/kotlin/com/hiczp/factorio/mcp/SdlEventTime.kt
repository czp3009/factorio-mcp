package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/**
 * SDL3's Uint64 nanosecond timestamp, through the game's floating conversion into the selected
 * Event store.
 */
internal data class SdlEventTime(val ticks: ElfImage.Symbol, val divisor: Double) {
    companion object {
        fun resolve(image: ElfImage, timeStore: Long): SdlEventTime {
            val function = image.symbol(SdlEvents.CONVERT)
            val flow = X64ControlFlow.resolve(image, function)
            val divisor =
                analyze(flow, function.address, timeStore) { address, width ->
                    require(
                        image.sections.any {
                            it.flags and 3L == 2L &&
                                address >= it.address &&
                                it.size >= width &&
                                address - it.address <= it.size - width
                        }
                    )
                    image.virtualBytes(address, width.toLong())
                }
            val ticks = image.symbol("SDL_GetTicksNS_REAL")
            EhFrames(image).function(ticks)
            image.functionBytes(ticks, 4096)
            val producer = image.symbol("SDL_PushEvent_REAL")
            verifyClock(X64ControlFlow.resolve(image, producer), ticks.address - producer.address)
            return SdlEventTime(ticks, divisor)
        }

        /**
         * Connect the SDK clock's full integer return to the same SDK timestamp consumed by the
         * game.
         */
        internal fun verifyClock(flow: X64ControlFlow, target: Long) {
            val call =
                flow.instructions.single {
                    it.offset in flow.reachable &&
                        it.operation == Operation.CALL &&
                        it.destination == Immediate(target)
                }
            val arguments = SysVArgumentFlow(flow)
            val store =
                flow.instructions.single { instruction ->
                    val memory = instruction.destination as? Memory
                    instruction.offset in flow.reachable &&
                        instruction.operation == Operation.MOV &&
                        memory != null &&
                        arguments.memory(instruction.offset, memory) ==
                            SdlEvents.field(SdlEvents.TIMESTAMP, 8)
                }
            require(flow.dominates(call.offset, store.offset))
            val returned = PrivateValueCopies.Read(call.offset, NativeField(0, 8))
            val copies =
                PrivateValueCopies(
                    flow.reaching(store.offset),
                    emptyMap(),
                    callResults = mapOf(call.offset to returned),
                )
            require(copies.field(store.offset, store.source as Register) == returned) {
                "SDK clock return is narrowed or transformed before timestamp storage"
            }
        }

        fun analyze(
            complete: X64ControlFlow,
            address: Long,
            timeStore: Long,
            literal: (Long, Int) -> BinaryView,
        ): Double {
            val flow = complete.reaching(timeStore)
            val store = flow.body.getValue(timeStore)
            val consumer = store.operation == Operation.CALL
            require(
                consumer ||
                    store.operation == Operation.SCALAR_MOV &&
                        (store.destination as? Memory)?.width == 8
            )
            val output =
                if (consumer) Register(16, 8)
                else
                    store.source as? Register
                        ?: error("Event time store has no scalar register source")
            require(output.number in 16..31 && output.width == 8)
            // Public SDL3 SDL_Event ABI: the first const-reference argument's Uint64 timestamp is
            // at byte 8.
            // https://wiki.libsdl.org/SDL3/SDL_KeyboardEvent
            val conversion =
                UnsignedDoubleConversion(
                    flow,
                    address,
                    SysVArgumentFlow.Read(SysVArgumentFlow.Reference(7, 8), 8),
                    literal,
                )
            val candidates = mutableListOf<Double>()
            val failures = mutableListOf<String>()
            for (division in
                flow.instructions.filter {
                    it.offset in flow.reachable && it.operation == Operation.DOUBLE_DIVIDE
                }) {
                try {
                    val target = division.destination as Register
                    val constant = division.source as Memory
                    require(
                        target.number in 16..31 &&
                            target.width == 8 &&
                            constant.width == 8 &&
                            constant.relative &&
                            constant.base == null &&
                            constant.index == null
                    )
                    conversion.verify(division.offset, target)
                    val value = PrivateValueCopies.Read(division.offset, NativeField(0, 8))
                    val copies =
                        PrivateValueCopies(
                            flow,
                            emptyMap(),
                            mapOf(division.offset to value),
                            terminalConsumer = timeStore.takeIf { consumer },
                        )
                    require(copies.field(timeStore, output) == value)
                    val next = address + division.offset + division.size
                    require(
                        address >= 0 &&
                            next >= address &&
                            constant.displacement >= -next &&
                            constant.displacement <= Long.MAX_VALUE - next
                    )
                    val divisor =
                        Double.fromBits(literal(next + constant.displacement, 8).unsigned(0, 8))
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
            return candidates.singleOrNull()
                ?: error("Event time has no unique SDL timestamp conversion: $failures")
        }
    }
}
