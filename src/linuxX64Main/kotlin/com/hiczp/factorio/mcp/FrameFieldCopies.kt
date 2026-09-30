package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.Memory
import com.hiczp.factorio.mcp.X64Instructions.Operation

/** Field copies from independently identified local storage into a fresh reference or outgoing stack argument. */
internal object FrameFieldCopies {
    fun resolve(
        flow: X64ControlFlow, sink: Long, source: Long, sourceExtent: Int, outputExtent: Int,
        fields: Map<String, InlineArgumentFields.Field>, argument: Int = 6
    ): Map<String, InlineArgumentFields.Field> =
        analyze(flow, sink, source, sourceExtent, outputExtent, fields, argument, reverse = false)

    /** Identifies source fields from independently identified destination fields, without assigning field names by order. */
    fun sources(
        flow: X64ControlFlow, sink: Long, source: Long, sourceExtent: Int, outputExtent: Int,
        fields: Map<String, InlineArgumentFields.Field>, argument: Int = 6
    ): Map<String, InlineArgumentFields.Field> =
        analyze(flow, sink, source, sourceExtent, outputExtent, fields, argument, reverse = true)

    private fun analyze(
        flow: X64ControlFlow, sink: Long, source: Long, sourceExtent: Int, outputExtent: Int,
        fields: Map<String, InlineArgumentFields.Field>, argument: Int,
        reverse: Boolean
    ): Map<String, InlineArgumentFields.Field> {
        require(sourceExtent in 1..4096 && outputExtent in 1..4096 && fields.isNotEmpty())
        val selectedExtent = if (reverse) outputExtent else sourceExtent
        require(fields.values.all {
            it.width in listOf(1, 2, 4, 8, 16) && it.offset >= 0 &&
                    it.offset <= selectedExtent - it.width
        })
        val frame = SysVLocalArgument(flow)
        val output =
            if (argument == 4) frame.outgoing(sink, outputExtent) else frame.argument(sink, argument, outputExtent)
        require(source >= checkNotNull(frame.registers(sink)[4]) && source <= -sourceExtent)
        require(source + sourceExtent <= output || output + outputExtent <= source) { "Input/output frame objects overlap" }
        val prefix = mutableListOf<Long>()
        var position = sink
        while (prefix.size < 256) {
            val previous = flow.predecessors[position]?.singleOrNull() ?: break
            val instruction = flow.body.getValue(previous)
            if (previous >= position || instruction.operation in listOf(
                    Operation.CALL, Operation.JCC,
                    Operation.JMP, Operation.RET
                )
            ) break
            prefix += previous
            position = previous
        }
        val copies = LocalFieldCopies(flow)
        return fields.mapValues { (name, field) ->
            val wanted = source + field.offset
            val candidates = prefix.flatMap { offset ->
                val instruction = flow.body.getValue(offset)
                if (instruction.operation !in listOf(
                        Operation.MOV, Operation.MOVZX, Operation.SCALAR_MOV,
                        Operation.VECTOR_MOV
                    )
                ) return@flatMap emptyList()
                val memory = instruction.source as? Memory ?: return@flatMap emptyList()
                val start = frame.address(offset, memory) ?: return@flatMap emptyList()
                if (start < source || start > source + sourceExtent - memory.width) return@flatMap emptyList()
                val bytes = if (reverse) (0..memory.width - field.width).toList()
                else if (wanted >= start && wanted <= start + memory.width - field.width)
                    listOf((wanted - start).toInt()) else emptyList()
                bytes.flatMap { byte ->
                    val found = copies.fromRead(offset, sink, argument, byteOffset = byte, byteWidth = field.width)
                        .filter { it.offset >= 0 && it.offset <= outputExtent - it.width }
                    if (reverse) found.filter { it.offset == field.offset && it.width == field.width }
                        .map { LocalFieldCopies.Field(start - source + byte, field.width) }
                    else found
                }
            }.distinct()
            val result = candidates.singleOrNull() ?: error("Local field $name has no unique fresh output copy")
            InlineArgumentFields.Field(result.offset, result.width)
        }
    }
}
