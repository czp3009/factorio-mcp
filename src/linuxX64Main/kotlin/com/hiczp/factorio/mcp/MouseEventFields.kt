@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.Immediate
import com.hiczp.factorio.mcp.X64Instructions.Operation
import com.hiczp.factorio.mcp.linuxbridge.FmLinuxMouseEventLayout

/** Cross-checked event fields. Complete event construction and callable entry/state-update ABIs remain separate. */
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
        val fields = listOf(
            x to 4, y to 4, source to 8, wheel to 4, button to 2, type to 4, time to 8,
            alt to 1, control to 1, shift to 1
        )
        require(fields.all { (offset, width) -> offset >= 0 && offset <= extent - width })
        require(fields.withIndex().all { (index, left) ->
            fields.drop(index + 1).all { right ->
                left.first + left.second <= right.first || right.first + right.second <= left.first
            }
        }) { "Mouse event fields overlap" }
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
            val parent = SysVRectangleAbi.resolve(
                image,
                image.symbol("_ZNK4agui6Widget20getAbsoluteRectangleEv"),
                widgetSize,
                table
            ).parent
            val copy = MouseEventCopy.resolve(
                image, image.symbol("_ZNK4agui10MouseEvent17copyWithNewSourceEPNS_6WidgetE"),
                widgetSize, parent, table.method(image, "_ZNK4agui6Widget14getLeftPaddingEv").slot,
                table.method(image, "_ZNK4agui6Widget13getTopPaddingEv").slot
            )
            val debug = DwarfInlines(image)
            val selected = listOf(
                Triple("_ZN4agui6Widget17dispatchMouseDownERKNS_10MouseEventE", "dispatchMouseDown", "getButton" to 2),
                Triple(
                    "_ZN7ModsGui21onDialogButtonPressedERKN4agui10MouseEventE",
                    "onDialogButtonPressed",
                    "control" to 1
                ),
                Triple(
                    "_ZNSt17_Function_handlerIFvRKN4agui10MouseEventEEZN15MapGeneratorGui24prepareSubheaderElementsEvE3${'$'}_2E9_M_invokeERKSt9_Any_dataS3_",
                    "_M_invoke", "shift" to 1
                ),
            )
            val references = selected.flatMap { (symbol, owner, getter) ->
                InlineArgumentFields.resolveEvidence(
                    image, image.symbol(symbol), owner, 6, copy.extent.toLong(),
                    mapOf(getter), debug
                ).entries.map { it.toPair() }
            }.toMap()
            val anchors = references.filterKeys { it in setOf("getButton", "shift") }
            val axes = image.symbol("_ZN4agui3Gui15handleMouseAxesENS_10MouseEventE")
            val stack = InlineArgumentFields.resolveStack(
                image, axes, "handleMouseAxes", copy.extent.toLong(), anchors,
                anchors.mapValues { it.value.field.width } + mapOf(
                    "alt" to 2, "getTimeStamp" to 8,
                    "getEvent" to 4, "getMouseWheelChange" to 4
                ), debug)
            // A CALL pushes the eight-byte return address before the independently identified incoming object.
            require(stack.base == 8L) { "Mouse event is not the first outgoing stack argument" }
            val input = MouseInputLayout.resolve(image)
            val function = image.symbol("_ZN4agui3Gui5logicEb")
            val flow = X64ControlFlow.resolve(image, function)
            val locals = debug.find(function, "logic", setOf("dequeueMouseInput")).map { inline ->
                InlineFrameCopy.resolve(flow, inline.ranges.map {
                    DwarfRanges.Range(it.start - function.address, it.end - function.address)
                }, input.queue.extent)
            }.distinct()
            val local = locals.singleOrNull() ?: error("Mouse input has no unique local copy")
            val calls = flow.instructions.filter {
                it.operation == Operation.CALL &&
                        (it.destination as? Immediate)?.value?.plus(function.address) == axes.address
            }
            require(calls.isNotEmpty())
            val conversions = calls.map { call ->
                FrameFieldCopies.resolve(
                    flow, call.offset, local.frame,
                    input.queue.extent, copy.extent, mapOf(
                        "alt" to InlineArgumentFields.Field(input.alt.field, 1),
                        "control" to InlineArgumentFields.Field(input.control, 1),
                        "shift" to InlineArgumentFields.Field(input.shift, 1),
                        "time" to InlineArgumentFields.Field(input.time, 8)
                    ), argument = 4
                )
            }.distinct()
            val conversion = conversions.singleOrNull() ?: error("Mouse event conversions disagree")
            return crossCheck(copy, references.mapValues { it.value.field }, stack.fields, conversion)
        }

        fun crossCheck(
            copy: MouseEventCopy.Proof, references: Map<String, InlineArgumentFields.Field>,
            stack: Map<String, InlineArgumentFields.Field>, conversion: Map<String, InlineArgumentFields.Field>
        ): MouseEventFields {
            fun field(fields: Map<String, InlineArgumentFields.Field>, name: String, width: Int): Long =
                fields.getValue(name).also { require(it.width == width) }.offset
            require(
                references.getValue("getButton") == stack.getValue("getButton") &&
                        references.getValue("shift") == stack.getValue("shift") &&
                        references.getValue("control") == conversion.getValue("control") &&
                        references.getValue("shift") == conversion.getValue("shift") &&
                        stack.getValue("getTimeStamp") == conversion.getValue("time")
            ) {
                "Native input conversion disagrees with independent named event getters"
            }
            val alt = field(conversion, "alt", 1)
            val control = field(conversion, "control", 1)
            val packed = field(stack, "alt", 2)
            require(
                setOf(alt, control) == setOf(
                    packed,
                    packed + 1
                )
            ) { "Packed modifier read disagrees with byte copies" }
            return MouseEventFields(
                copy.extent, copy.x, copy.y, copy.source, field(stack, "getMouseWheelChange", 4),
                field(references, "getButton", 2),
                field(stack, "getEvent", 4), field(conversion, "time", 8), alt, control, field(conversion, "shift", 1)
            )
        }
    }
}
