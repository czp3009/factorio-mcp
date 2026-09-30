@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.linuxbridge.FmLinuxCaptureLayout
import kotlinx.cinterop.get

/** Capture ownership and the native reset coupled to target unlinking. No widget addresses survive a safe point. */
internal data class UiCaptureMetadata(
    val guiSize: Long,
    val widgetSize: Long,
    val widgetTargetable: Long,
    val release: Long,
    val links: SysVTargeterRelease.Layout,
    val reset: GuiTargetReset.Proof,
    private val functions: List<ElfImage.Symbol>,
) {
    fun verifyLoaded(image: ElfImage, process: ProcessHandle, loadBias: Long) {
        verify(image, loadBias, process::readMemory)
    }

    internal fun verify(image: ElfImage, loadBias: Long, read: (Long, Int) -> ByteArray) {
        require(loadBias >= 0)
        for (function in functions) {
            require(
                function.address >= 0 && function.size in 1..(16 * 1024 * 1024) &&
                        function.address <= Long.MAX_VALUE - loadBias - function.size
            )
            require(
                read(function.address + loadBias, function.size.toInt()).contentEquals(
                    image.functionBytes(function, function.size.toInt()).bytes(0, function.size.toInt())
                )
            ) {
                "Live capture evidence differs from the selected executable"
            }
        }
    }

    fun writeTo(output: FmLinuxCaptureLayout, loadBias: Long) {
        require(loadBias >= 0 && release > 0 && release < Long.MAX_VALUE - loadBias)
        output.guiSize = guiSize.toUInt()
        output.widgetSize = widgetSize.toUInt()
        output.widgetTargetable = widgetTargetable.toUInt()
        output.targeterMember = reset.targeter.toUInt()
        output.targeter.release = (release + loadBias).toULong()
        output.targeter.targeterExtent = links.targeterExtent.toUInt()
        output.targeter.targetableExtent = links.targetableExtent.toUInt()
        output.targeter.target = links.target.toUInt()
        output.targeter.previous = links.previous.toUInt()
        output.targeter.next = links.next.toUInt()
        output.targeter.head = links.head.toUInt()
        require(reset.stores.size in 1..8)
        output.resetCount = reset.stores.size.toUInt()
        reset.stores.forEachIndexed { index, store ->
            output.resets[index].offset = store.offset.toUInt()
            output.resets[index].width = store.width.toUInt()
            output.resets[index].value = store.value.toULong()
        }
    }

    companion object {
        fun resolve(image: ElfImage): UiCaptureMetadata {
            val (resolved, functions) = image.withFunctionEvidence {
                val guiSize = SysVObjectSize.resolve(image, "4agui3Gui")
                val widgetSize = SysVObjectSize.resolve(image, "4agui6Widget")
                val gate = SysVPointerGate.resolve(image, "_ZN4agui3Gui11handleHoverEv", guiSize)
                val release = image.symbol("_ZN4agui19GenericTargeterBase8attachToEPNS_17GenericTargetableE")
                val links = SysVTargeterRelease.resolve(image, release.name)
                val base = ItaniumClass.resolve(image, "N4agui6WidgetE").directBase(
                    ItaniumClass.resolve(image, "N4agui17GenericTargetableE"), widgetSize, links.targetableExtent
                )
                val reset = GuiTargetReset.resolve(
                    image, "_ZN4agui3Gui23dispatchWidgetDestroyedEPNS_6WidgetE",
                    guiSize, gate.member, base, links
                )
                UiCaptureMetadata(guiSize, widgetSize, base, release.address, links, reset, emptyList())
            }
            return resolved.copy(functions = functions)
        }
    }
}
