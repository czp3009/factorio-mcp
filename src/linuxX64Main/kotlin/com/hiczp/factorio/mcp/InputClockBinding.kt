@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.Memory
import com.hiczp.factorio.mcp.X64Instructions.Operation
import com.hiczp.factorio.mcp.linuxbridge.FmLinuxInputClockLayout

/** Typed GUI input association and concrete clock ABI; runtime use still requires fresh object/table checks. */
internal data class InputClockBinding(
    val guiSize: Long,
    val member: Long,
    val handlerSize: Long,
    val handlerTable: Long,
    val slot: Int,
    val getter: Long,
    val clock: InputClock.Proof,
) {
    fun writeTo(output: FmLinuxInputClockLayout, loadBias: Long) {
        require(
            loadBias >= 0 && getter > 0 && getter < Long.MAX_VALUE - loadBias &&
                    slot in 0..4095 && handlerTable > 0 && handlerTable <= Long.MAX_VALUE - loadBias - (slot + 1L) * 8
        )
        output.guiSize = guiSize.toUInt()
        output.member = member.toUInt()
        output.handlerSize = handlerSize.toUInt()
        output.slot = slot.toUInt()
        output.handlerTable = (handlerTable + loadBias).toULong()
        output.function = (getter + loadBias).toULong()
    }

    companion object {
        fun resolve(image: ElfImage): InputClockBinding {
            val guiSize = SysVObjectSize.resolve(image, "4agui3Gui")
            val handlerSize = SysVObjectSize.resolve(image, "16InputHandlerAgui")
            val base = ItaniumClass.resolve(image, "N4agui5InputE")
            val handler = ItaniumClass.resolve(image, "16InputHandlerAgui")
            // Only its vptr is read. Bounds come from the complete concrete object, not a fictitious base allocation.
            require(handler.directBase(base, handlerSize, 8) == 0L) {
                "Input handler does not use the expected primary input base"
            }
            val inputTable = ItaniumVtable.resolve(image, "_ZTVN4agui5InputE")
            val handlerTable = ItaniumVtable.resolve(image, "_ZTV16InputHandlerAgui")
            val baseMethod = inputTable.method(image, "_ZNK4agui5Input7getTimeEv")
            val method = handlerTable.method(image, "_ZNK16InputHandlerAgui7getTimeEv")
            require(baseMethod.slot == method.slot) { "Concrete input clock does not override the base slot" }
            val function = image.symbol("_ZN4agui3Gui11handleHoverEv")
            EhFrames(image).function(function)
            require(function.size in 1..8192)
            val bytes = image.functionBytes(function, 8192)
            val call = X64Instructions(bytes).all(2048).firstOrNull { instruction ->
                val operand = instruction.destination as? Memory
                instruction.operation == Operation.CALL && operand != null && !operand.relative &&
                        operand.index == null && operand.width == 8 && operand.displacement == method.slot * 8L
            } ?: error("GUI hover has no call through the typed clock slot")
            val member = SysVMemberCalls.virtualAt(bytes, function.address, method.slot, guiSize, call.offset)
            return InputClockBinding(
                guiSize, member, handlerSize, handlerTable.addressPoint,
                method.slot, method.function.address, InputClock.resolve(image)
            )
        }
    }
}
