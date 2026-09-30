package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.Immediate
import com.hiczp.factorio.mcp.X64Instructions.Operation

/** Queue fields copied into independently identified MouseEvent fields. Values and input-update ABI are separate. */
internal object MouseInputConversion {
    fun resolve(
        image: ElfImage,
        input: MouseInputLayout,
        event: MouseEventFields
    ): Map<String, InlineArgumentFields.Field> {
        val function = image.symbol("_ZN4agui3Gui5logicEb")
        val flow = X64ControlFlow.resolve(image, function)
        val locals = DwarfInlines(image).find(function, "logic", setOf("dequeueMouseInput")).map { inline ->
            InlineFrameCopy.resolve(flow, inline.ranges.map {
                DwarfRanges.Range(it.start - function.address, it.end - function.address)
            }, input.queue.extent)
        }.distinct()
        val local = locals.singleOrNull() ?: error("Mouse input has no unique local copy")
        val target = image.symbol("_ZN4agui3Gui15handleMouseAxesENS_10MouseEventE")
        val calls = flow.instructions.filter {
            it.operation == Operation.CALL &&
                    (it.destination as? Immediate)?.value?.plus(function.address) == target.address
        }
        require(calls.isNotEmpty())
        val fields = mapOf(
            "wheel" to InlineArgumentFields.Field(event.wheel, 4),
            "time" to InlineArgumentFields.Field(event.time, 8),
            "alt" to InlineArgumentFields.Field(event.alt, 1),
            "control" to InlineArgumentFields.Field(event.control, 1),
            "shift" to InlineArgumentFields.Field(event.shift, 1)
        )
        val copied = calls.map {
            FrameFieldCopies.sources(
                flow, it.offset, local.frame, input.queue.extent,
                event.extent, fields, argument = 4
            )
        }.distinct().singleOrNull()
            ?: error("Mouse input conversions disagree")
        require(
            copied.getValue("time").offset == input.time && copied.getValue("alt").offset == input.alt.field &&
                    copied.getValue("control").offset == input.control && copied.getValue("shift").offset == input.shift
        )
        val values = copied.values.toList()
        require(values.withIndex().all { (index, left) ->
            values.drop(index + 1).all { right ->
                left.offset + left.width <= right.offset || right.offset + right.width <= left.offset
            }
        }) { "Mouse input fields overlap" }
        return copied
    }
}
