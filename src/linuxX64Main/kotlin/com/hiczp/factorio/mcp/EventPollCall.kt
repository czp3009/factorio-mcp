package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.Memory
import com.hiczp.factorio.mcp.X64Instructions.Operation

/** The native event pump's typed poll call and its freshly initialized local Event. No hook is installed here. */
internal data class EventPollCall(
    val caller: ElfImage.Symbol,
    val poll: ElfImage.Symbol,
    val table: Long,
    val slot: Int,
    val returnOffset: Long,
    val callerReturnFromFrame: Long,
    val frame: Long,
    val defaults: Map<Long, Int>,
    val pump: ElfImage.Symbol,
    val pumpReturnOffset: Long,
) {
    companion object {
        fun resolve(image: ElfImage, header: EventHeader): EventPollCall {
            val caller = image.symbol("_ZN13GlobalContext9nextEventEv")
            val pump = image.symbol("_ZN8MainLoop13processEventsEb")
            EhFrames(image).function(pump)
            val pumpPrefix = image.functionBytes(pump, 32768).let { it.slice(0, minOf(it.size, 4096)) }
            val pumpReturn = DirectCallSite.firstReturn(pumpPrefix, caller.address - pump.address)
            val table = ItaniumVtable.resolve(image, "_ZTV9SDLWindow")
            val poll = table.method(image, "_ZN9SDLWindow9pollEventER5Event")
            val window = ItaniumClass.resolve(image, "6Window")
            val concrete = ItaniumClass.resolve(image, "9SDLWindow")
            require(concrete.directBase(window, SysVObjectSize.resolve(image, "9SDLWindow"), 8) == 0L) {
                "SDL window does not have a primary Window base"
            }
            val complete = X64ControlFlow.resolve(image, caller)
            val call = complete.instructions.first { it.operation == Operation.CALL }
            val memory = call.destination as? Memory ?: error("First event-pump call is not virtual")
            require(
                !memory.relative && memory.index == null && memory.base != null && memory.width == 8 &&
                        memory.displacement == poll.slot * 8L
            )
            require(complete.successors.filterKeys { it > call.offset }.values.flatten().none { it <= call.offset }) {
                "Event-pump prefix is reentered after polling"
            }
            val flow = X64ControlFlow(complete.instructions.takeWhile { it.offset <= call.offset })
            val locals = SysVLocalArgument(flow)
            val frame = locals.argument(call.offset, 6, header.extent)
            val frameBase = checkNotNull(locals.registers(call.offset)[5]) {
                "Event-pump caller has no proven frame register"
            }
            require(frameBase in -16384..0)
            val arguments = ConstructorValues(
                flow, mapOf(
                    call.offset to
                            listOf(ConstructorValues.Borrow(6, header.extent))
                )
            )
            val receiver = arguments.register(call.offset, 7)
            val vtable = arguments.register(call.offset, memory.base) as? ConstructorValues.Load
            require(receiver == ConstructorValues.Argument(6) && vtable?.base == receiver && vtable.member == 0L) {
                "Event pump does not poll its original Window reference through its own table"
            }
            val defaults = LocalDefaults.before(
                flow, call.offset, header.extent,
                listOf(InlineArgumentFields.Field(header.type, 4), InlineArgumentFields.Field(header.time, 8))
            )
            require((header.time until header.time + 8).all { defaults[it] == 0 }) {
                "Fresh event header does not have the native empty timestamp"
            }
            return EventPollCall(
                caller, poll.function, table.addressPoint, poll.slot,
                call.offset + call.size, -frameBase, frame, defaults, pump, pumpReturn
            )
        }
    }
}
