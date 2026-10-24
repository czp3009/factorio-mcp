@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.Immediate
import com.hiczp.factorio.mcp.X64Instructions.Operation
import com.hiczp.factorio.mcp.linuxbridge.FmLinuxMouseEventLayout

/**
 * Cross-checked event fields. Complete event construction and callable entry/state-update ABIs
 * remain separate.
 */
internal data class MouseEventFields(
    val extent: Int,
    val x: Long,
    val y: Long,
    val source: Long,
    val wheel: Long,
    val button: Long,
    val type: Long,
    val time: Long,
    val alt: Long,
    val control: Long,
    val shift: Long,
) {
    init {
        require(extent in 1..256)
        val fields =
            listOf(
                x to 4,
                y to 4,
                source to 8,
                wheel to 4,
                button to 2,
                type to 4,
                time to 8,
                alt to 1,
                control to 1,
                shift to 1,
            )
        require(fields.all { (offset, width) -> offset >= 0 && offset <= extent - width })
        require(
            fields.withIndex().all { (index, left) ->
                fields.drop(index + 1).all { right ->
                    left.first + left.second <= right.first ||
                        right.first + right.second <= left.first
                }
            }
        ) {
            "Mouse event fields overlap"
        }
    }

    fun writeTo(output: FmLinuxMouseEventLayout) {
        output.size = extent.toUInt()
        output.x = x.toUInt()
        output.y = y.toUInt()
        output.source = source.toUInt()
        output.wheel = wheel.toUInt()
        output.button = button.toUInt()
        output.type = type.toUInt()
        output.time = time.toUInt()
        output.alt = alt.toUInt()
        output.control = control.toUInt()
        output.shift = shift.toUInt()
        output.hasPrevious = 0U
        output.previous = 0U
    }

    companion object {
        fun resolve(image: ElfImage): MouseEventFields {
            val widgetSize = SysVObjectSize.resolve(image, "4agui6Widget")
            val table = ItaniumVtable.resolve(image, "_ZTVN4agui6WidgetE")
            val parent =
                SysVRectangleAbi.resolve(
                        image,
                        image.symbol("_ZNK4agui6Widget20getAbsoluteRectangleEv"),
                        widgetSize,
                        table,
                    )
                    .parent
            val copy =
                MouseEventCopy.resolve(
                    image,
                    image.symbol("_ZNK4agui10MouseEvent17copyWithNewSourceEPNS_6WidgetE"),
                    widgetSize,
                    parent,
                    table.method(image, "_ZNK4agui6Widget14getLeftPaddingEv").slot,
                    table.method(image, "_ZNK4agui6Widget13getTopPaddingEv").slot,
                )
            val debug = image.inlines
            val button =
                WidgetEventDispatch.mask(
                        image,
                        image.symbol("_ZN4agui6Widget17dispatchMouseDownERKNS_10MouseEventE"),
                        widgetSize,
                        copy.extent.toLong(),
                        table.method(image, "_ZN4agui6Widget9mouseDownERKNS_10MouseEventE"),
                    )
                    .also { require(it.width == 2) }
                    .event
            val wheel = MouseWheelField.resolve(image, copy.extent)
            val type = MouseClickEvent.resolve(image, copy.extent).type
            val axes = image.symbol("_ZN4agui3Gui15handleMouseAxesENS_10MouseEventE")
            val input = MouseInputLayout.resolve(image)
            val function = image.symbol("_ZN4agui3Gui5logicEb")
            val flow = X64ControlFlow.resolve(image, function)
            val locals =
                debug
                    .find(function, "logic", setOf("dequeueMouseInput"))
                    .map { inline ->
                        InlineFrameCopy.resolve(
                            flow,
                            inline.ranges.map {
                                DwarfRanges.Range(
                                    it.start - function.address,
                                    it.end - function.address,
                                )
                            },
                            input.queue.extent,
                        )
                    }
                    .distinct()
            val local = locals.singleOrNull() ?: error("Mouse input has no unique local copy")
            val calls =
                flow.instructions.filter {
                    it.operation == Operation.CALL &&
                        (it.destination as? Immediate)?.value?.plus(function.address) ==
                            axes.address
                }
            require(calls.isNotEmpty())
            val conversions =
                calls
                    .map { call ->
                        FrameFieldCopies.resolve(
                            flow,
                            call.offset,
                            local.frame,
                            input.queue.extent,
                            copy.extent,
                            mapOf(
                                "alt" to NativeField(input.alt, 1),
                                "control" to NativeField(input.control, 1),
                                "shift" to NativeField(input.shift, 1),
                                "time" to NativeField(input.time, 8),
                            ),
                            argument = 4,
                        )
                    }
                    .distinct()
            val conversion = conversions.singleOrNull() ?: error("Mouse event conversions disagree")
            fun offset(name: String, width: Int): Long =
                conversion.getValue(name).also { require(it.width == width) }.offset
            return MouseEventFields(
                copy.extent,
                copy.x,
                copy.y,
                copy.source,
                wheel,
                button,
                type,
                offset("time", 8),
                offset("alt", 1),
                offset("control", 1),
                offset("shift", 1),
            )
        }
    }
}
