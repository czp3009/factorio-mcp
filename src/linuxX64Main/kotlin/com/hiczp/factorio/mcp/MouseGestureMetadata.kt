@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.linuxbridge.FmLinuxMouseGestureConfig
import kotlinx.cinterop.set

/** Complete finite-widget-gesture configuration; input ownership and command admission are separate. */
internal data class MouseGestureMetadata(
    val core: Core,
    val enter: MouseEnterConstruction,
    val capture: UiCaptureMetadata,
    val modal: UiModalMetadata,
) {
    data class Core(
        val widgetSize: Long,
        val event: MouseEventFields,
        val construction: MouseEventConstruction,
        val clock: InputClockBinding,
        val clickOnDown: NativeAccessor,
        val buttons: MouseButtonMasks,
        val entries: List<Long>,
        val functions: List<ElfImage.Symbol> = emptyList(),
        val readonly: List<ElfImage.ReadonlyRange> = emptyList(),
    )

    init {
        require(core.entries.size == 5 && core.entries.all { it > 0 })
        require(
            core.clock.guiSize == enter.guiSize && enter.guiSize == capture.guiSize &&
                    capture.guiSize == modal.guiSize && core.widgetSize == capture.widgetSize &&
                    capture.widgetSize == modal.widgetSize
        )
        require(
            enter.widgetTargetable == capture.widgetTargetable &&
                    capture.widgetTargetable == modal.widgetTargetable
        )
        core.clickOnDown.withinObject(core.widgetSize)
        val previous = checkNotNull(enter.template.previous)
        val zeroBytes = (enter.template.zeroBytes + (previous until previous + 8)).sorted()
        require(
            listOf(
                core.construction.down, core.construction.up, core.construction.click,
                core.construction.leave
            ).all { it.zeroBytes == zeroBytes && it.previous == null }) {
            "Gesture phases disagree on native event defaults"
        }
    }

    fun verifyLoaded(image: ElfImage, process: ProcessHandle, loadBias: Long) =
        verify(image, loadBias, process::readMemory)

    internal fun verify(image: ElfImage, loadBias: Long, read: (Long, Int) -> ByteArray) {
        require(loadBias >= 0)
        fun compare(address: Long, bytes: ByteArray) {
            require(
                bytes.size in 1..(16 * 1024 * 1024) && address >= 0 &&
                        address <= Long.MAX_VALUE - loadBias - bytes.size
            )
            require(read(address + loadBias, bytes.size).contentEquals(bytes)) {
                "Live mouse-gesture evidence differs from the selected executable"
            }
        }
        for (function in core.functions) {
            require(function.size in 1..(16 * 1024 * 1024))
            compare(
                function.address,
                image.functionBytes(function, function.size.toInt()).bytes(0, function.size.toInt())
            )
        }
        for (range in core.readonly) {
            require(range.size in 1..(16 * 1024 * 1024))
            compare(range.address, image.virtualBytes(range.address, range.size).bytes(0, range.size.toInt()))
        }
        for (literal in core.construction.literals)
            compare(literal.address, literal.bytes.map { it.toByte() }.toByteArray())
        enter.verify(image, loadBias, read)
        capture.verify(image, loadBias, read)
        modal.verify(image, loadBias, read)
    }

    fun writeTo(output: FmLinuxMouseGestureConfig, loadBias: Long) {
        require(loadBias >= 0)
        val entries = core.entries.map {
            require(it > 0 && it < Long.MAX_VALUE - loadBias)
            (it + loadBias).toULong()
        }
        core.event.writeTo(output.event)
        core.clock.writeTo(output.clock, loadBias)
        capture.writeTo(output.capture, loadBias)
        modal.writeTo(output.modal)
        enter.writeTo(output.event, output.gesture)
        fun type(template: MouseEventConstruction.Template): UInt {
            require(template.type in 0..UInt.MAX_VALUE.toLong())
            return template.type.toUInt()
        }
        output.gesture.downType = type(core.construction.down)
        output.gesture.clickType = type(core.construction.click)
        output.gesture.upType = type(core.construction.up)
        output.gesture.leaveType = type(core.construction.leave)
        output.gesture.clickOnDown.offset = core.clickOnDown.offset.toUInt()
        output.gesture.clickOnDown.width = core.clickOnDown.width.toUInt()
        output.gesture.clickOnDown.mask = core.clickOnDown.mask
        output.gesture.clickOnDown.shift = core.clickOnDown.shift.toUInt()
        output.enter = entries[0]
        output.down = entries[1]
        output.click = entries[2]
        output.up = entries[3]
        output.leave = entries[4]
        listOf(SdlButtonAdmission.Button.LEFT, SdlButtonAdmission.Button.RIGHT, SdlButtonAdmission.Button.MIDDLE)
            .forEachIndexed { index, button -> output.buttons[index] = core.buttons.values.getValue(button).toUShort() }
    }

    companion object {
        fun resolve(image: ElfImage): MouseGestureMetadata {
            val (resolved, readonly) = image.withReadonlyEvidence {
                image.withFunctionEvidence {
                    val widgetSize = SysVObjectSize.resolve(image, "4agui6Widget")
                    val targeter = image.symbol("_ZN4agui19GenericTargeterBase8attachToEPNS_17GenericTargetableE")
                    val links = SysVTargeterRelease.resolve(image, targeter.name)
                    SysVTargeterRelease.verifyFreshAttachment(image.functionBytes(targeter, 512), links)
                    val clearing = image.symbol("_ZN4agui17GenericTargetable23clearTargetingMeGenericEv")
                    EhFrames(image).function(clearing)
                    SysVTargeterRelease.verifyClearing(image.functionBytes(clearing, 512), links)
                    val event = MouseEventFields.resolve(image)
                    val table = ItaniumVtable.resolve(image, "_ZTVN4agui6WidgetE")
                    val entries = listOf(
                        "18dispatchMouseEnter" to "10mouseEnter", "17dispatchMouseDown" to "9mouseDown",
                        "13dispatchClick" to "10mouseClick", "15dispatchMouseUp" to "7mouseUp",
                        "18dispatchMouseLeave" to "10mouseLeave"
                    ).map { (entry, method) ->
                        val symbol = image.symbol("_ZN4agui6Widget${entry}ERKNS_10MouseEventE")
                        WidgetEventDispatch.verify(
                            image, symbol, widgetSize,
                            table.method(image, "_ZN4agui6Widget${method}ERKNS_10MouseEventE")
                        )
                        symbol.address
                    }
                    Core(
                        widgetSize, event, MouseEventConstruction.resolve(image, event),
                        InputClockBinding.resolve(image), WidgetClickGate.resolve(image),
                        MouseButtonMasks.resolve(
                            image, MouseInputLayout.resolve(image),
                            SdlButtonConversion.resolve(image), event
                        ), entries
                    )
                }
            }
            val core = resolved.first.copy(functions = resolved.second, readonly = readonly)
            return MouseGestureMetadata(
                core, MouseEnterConstruction.resolve(image, core.event),
                UiCaptureMetadata.resolve(image), UiModalMetadata.resolve(image)
            )
        }
    }
}
