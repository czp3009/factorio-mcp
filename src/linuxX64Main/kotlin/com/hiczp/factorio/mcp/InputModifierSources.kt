package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.Operation

/** Original handler bytes copied into independently identified output fields. Does not authorize state mutation. */
internal data class InputModifierSources(val alt: Long, val control: Long, val shift: Long) {
    fun validate(size: Long) {
        require(size in 1..(64 * 1024 * 1024))
        val fields = listOf(alt, control, shift)
        require(fields.distinct().size == fields.size && fields.all { it in 0 until size }) {
            "Modifier fields overlap or exceed their object"
        }
    }

    companion object {
        fun resolve(
            flow: X64ControlFlow, sink: Long, ownerSize: Long, outputSize: Int,
            output: InputModifierSources
        ): InputModifierSources {
            require(outputSize in 1..4096)
            output.validate(outputSize.toLong())
            val arguments = SysVArgumentFlow(flow)
            val copies = LocalFieldCopies(flow)
            val wanted = setOf(output.alt, output.control, output.shift)
            val sources = wanted.associateWith { mutableSetOf<Long>() }
            for (instruction in flow.instructions) {
                if (instruction.operation !in listOf(
                        Operation.MOV, Operation.MOVZX, Operation.SCALAR_MOV,
                        Operation.VECTOR_MOV
                    )
                ) continue
                val read = arguments.source(instruction.offset) ?: continue
                if (read.reference.argument != 7 || read.width !in listOf(1, 2, 4, 8, 16)) continue
                require(
                    ownerSize in 1..(64 * 1024 * 1024) && read.reference.offset >= 0 &&
                            read.reference.offset <= ownerSize - read.width
                ) { "Handler read exceeds its object" }
                for (byte in 0 until read.width) {
                    val fields = try {
                        copies.fromRead(instruction.offset, sink, byteOffset = byte, byteWidth = 1)
                    } catch (_: IllegalArgumentException) {
                        continue // No bounded acyclic copy path to this particular sink.
                    } catch (_: IllegalStateException) {
                        continue // Unknown frame or provenance is not a source association.
                    }
                    for (field in fields) sources[field.offset]?.add(read.reference.offset + byte)
                }
            }
            fun source(field: Long) = sources.getValue(field).singleOrNull()
                ?: error("Output modifier has no unique original handler source")
            return InputModifierSources(source(output.alt), source(output.control), source(output.shift)).also {
                it.validate(ownerSize)
            }
        }
    }
}
