@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.linuxbridge.FmLinuxPollHookConfig
import platform.posix.PROT_READ
import platform.posix.PROT_WRITE

/** The native event pump's typed poll call and its freshly initialized local Event. No hook is installed here. */
internal data class EventPollCall(
    val caller: ElfImage.Symbol,
    val poll: ElfImage.Symbol,
    val table: Long,
    val slot: Int,
    val returnOffset: Long,
    val callerReturnFromStack: Long,
    val eventFromEntry: Long,
    val defaults: Map<Long, Int>,
    val pump: ElfImage.Symbol,
    val pumpReturnOffset: Long,
) {
    fun writeTo(site: FmLinuxPollHookConfig, header: EventHeader, protection: Int, bias: Long) {
        require(protection == PROT_READ || protection == (PROT_READ or PROT_WRITE))
        fun address(value: Long): ULong {
            require(bias >= 0 && value > 0 && value <= Long.MAX_VALUE - bias)
            return (value + bias).toULong()
        }
        site.entry = address(table + slot * 8L)
        site.original = address(poll.address)
        site.table = address(table)
        site.caller = address(caller.address + returnOffset)
        site.pumpCaller = address(pump.address + pumpReturnOffset)
        site.stackReturn = callerReturnFromStack.toUInt()
        val local = eventFromEntry + callerReturnFromStack
        require(local in 0..callerReturnFromStack - header.extent)
        site.eventFromStack = local.toUInt()
        site.eventExtent = header.extent.toUInt()
        site.protection = protection.toUInt()
    }

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
            val proof = EventPollSite.analyze(image.functionBytes(caller, 8192), caller.address, poll.slot, header,
                X64ControlFlow.resolve(image, caller))
            return EventPollCall(
                caller, poll.function, table.addressPoint, poll.slot,
                proof.returned, proof.callerReturnFromStack, proof.eventFromEntry, proof.defaults, pump, pumpReturn
            )
        }
    }
}
