package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Named inline loads from bounded reference/stack objects. The enclosing caller ABI is established separately. */
internal object InlineArgumentFields {
    data class Field(val offset: Long, val width: Int)
    data class Evidence(val field: Field, val origin: Long)
    data class StackFields(val base: Long, val fields: Map<String, Field>)

    fun resolve(
        image: ElfImage, function: ElfImage.Symbol, ownerName: String, argument: Int, extent: Long,
        widths: Map<String, Int>, debug: DwarfInlines = DwarfInlines(image)
    ): Map<String, Field> {
        return resolveEvidence(image, function, ownerName, argument, extent, widths, debug).mapValues { it.value.field }
    }

    fun resolveEvidence(
        image: ElfImage, function: ElfImage.Symbol, ownerName: String, argument: Int, extent: Long,
        widths: Map<String, Int>, debug: DwarfInlines = DwarfInlines(image)
    ): Map<String, Evidence> {
        require(argument in listOf(7, 6, 2, 1, 8, 9) && extent in 1..(64 * 1024 * 1024))
        return loads(image, function, ownerName, argument, widths, debug).also { result ->
            require(result.values.all { it.field.offset >= 0 && it.field.offset <= extent - it.field.width })
        }
    }

    /** Identical abstract getters establish the incoming stack object's base, not a callable ABI. */
    fun resolveStack(
        image: ElfImage, function: ElfImage.Symbol, ownerName: String, extent: Long,
        anchors: Map<String, Evidence>, widths: Map<String, Int>,
        debug: DwarfInlines = DwarfInlines(image)
    ): StackFields {
        require(extent in 1..4096 && anchors.size >= 2)
        require(anchors.all { (name, evidence) ->
            widths[name] == evidence.field.width &&
                    evidence.field.offset >= 0 && evidence.field.offset <= extent - evidence.field.width
        })
        val reads = loads(image, function, ownerName, 4, widths, debug)
        val bases = anchors.map { (name, anchor) ->
            val read = reads.getValue(name)
            require(read.origin == anchor.origin) { "Stack getter differs from the reference object's getter" }
            read.field.offset - anchor.field.offset
        }.distinct()
        val base = bases.singleOrNull() ?: error("Named getters disagree on the incoming stack object's base")
        require(base in 8..4096 - extent) { "Named getters do not identify a bounded incoming stack object" }
        val fields = reads.mapValues { (_, evidence) ->
            evidence.field.copy(offset = evidence.field.offset - base).also {
                require(it.offset >= 0 && it.offset <= extent - it.width)
            }
        }
        return StackFields(base, fields)
    }

    private fun loads(
        image: ElfImage, function: ElfImage.Symbol, ownerName: String, argument: Int,
        widths: Map<String, Int>, debug: DwarfInlines
    ): Map<String, Evidence> {
        require(widths.isNotEmpty() && widths.values.all { it in listOf(1, 2, 4, 8, 16) })
        val instances = debug.find(function, ownerName, widths.keys)
        val flow = X64ControlFlow.resolve(image, function)
        val arguments = SysVArgumentFlow(flow, includeStack = argument == 4)
        val end = function.address + function.size
        val boundaries = flow.body.keys.map { function.address + it }.toSet() + end
        val fields = widths.keys.associateWith { mutableSetOf<Evidence>() }
        for (instance in instances) {
            require(instance.ranges.all { it.start in boundaries && it.end in boundaries }) {
                "Inline accessor range splits an instruction"
            }
            val reads = flow.instructions.filter { instruction ->
                instance.ranges.any { range ->
                    function.address + instruction.offset >= range.start &&
                            function.address + instruction.offset + instruction.size <= range.end
                }
            }.mapNotNull { instruction ->
                if (instruction.operation !in listOf(
                        Operation.MOV, Operation.MOVZX, Operation.SCALAR_MOV,
                        Operation.VECTOR_MOV
                    )
                ) return@mapNotNull null
                val target = instruction.destination as? Register ?: return@mapNotNull null
                val memory = instruction.source as? Memory ?: return@mapNotNull null
                val source = arguments.source(instruction.offset) ?: return@mapNotNull null
                if (source.reference.argument != argument) return@mapNotNull null
                require(target.width >= memory.width && source.width == widths.getValue(instance.name)) {
                    "Inline accessor has an incompatible or coalesced read width"
                }
                Field(source.reference.offset, source.width)
            }.distinct()
            val read = reads.singleOrNull() ?: error("Inline accessor has no unique original-argument load")
            fields.getValue(instance.name) += Evidence(read, instance.origin)
        }
        return fields.mapValues { (_, values) ->
            values.singleOrNull() ?: error("Inline instances disagree on their field")
        }
    }
}
