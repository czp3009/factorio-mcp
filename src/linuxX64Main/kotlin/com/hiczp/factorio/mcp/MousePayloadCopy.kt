package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** The typed MouseEvent argument's byte copies into an Event, including private compiler spills. */
internal data class MousePayloadCopy(
    val extent: Int,
    val bytes: Map<Long, Long>,
    val defaults: Map<Long, Int>,
) {
    fun field(offset: Long, width: Int): Long {
        require(offset >= 0 && offset <= extent - width)
        val at = bytes.filterValues { it == offset }.keys.single()
        require((0 until width).all { bytes[at + it] == offset + it })
        return at
    }

    companion object {
        fun resolve(image: ElfImage, header: EventHeader): MousePayloadCopy {
            val function = image.symbol(SdlEvents.MOUSE_EMPLACE)
            val flow = X64ControlFlow.resolve(image, function)
            require(EventHeader.analyze(flow) == header)
            val grow = image.symbol("_ZN12CompactDequeI5EventE4growEj")
            val calls =
                flow.instructions
                    .filter {
                        it.operation == Operation.CALL &&
                            it.destination == Immediate(grow.address - function.address)
                    }
                    .map { it.offset }
                    .toSet()
            return analyze(flow, header, calls)
        }

        fun analyze(
            flow: X64ControlFlow,
            header: EventHeader,
            queueCalls: Set<Long>,
        ): MousePayloadCopy {
            val arguments = SysVArgumentFlow(flow)
            val reads =
                flow.instructions
                    .mapNotNull { instruction ->
                        if (
                            instruction.offset !in flow.reachable ||
                                instruction.operation !in
                                    setOf(Operation.MOV, Operation.SCALAR_MOV, Operation.VECTOR_MOV)
                        )
                            return@mapNotNull null
                        val read = arguments.source(instruction.offset) ?: return@mapNotNull null
                        if (read.reference.argument != 2) return@mapNotNull null
                        require(
                            read.reference.offset >= 0 && read.reference.offset <= 256 - read.width
                        )
                        instruction.offset to
                            PrivateValueCopies.Read(
                                0,
                                NativeField(read.reference.offset, read.width),
                            )
                    }
                    .toMap()
            require(reads.isNotEmpty())
            val extent = reads.values.maxOf { it.field.offset + it.field.width }.toInt()
            val copies = PrivateValueCopies(flow, reads)
            val definitions = ScalarExpression(flow)
            val type =
                flow.instructions.single {
                    it.offset in flow.reachable &&
                        it.operation == Operation.MOV &&
                        (it.destination as? Memory)?.let { field ->
                            field.index != null &&
                                field.displacement == header.type &&
                                field.width == 4
                        } == true
                }
            val stores =
                IndexedEventStores.analyze(flow, header, type, queueCalls, privateLocals = true)
            val bytes = mutableMapOf<Long, Long>()
            val defaults = mutableMapOf<Long, Int>()
            for (store in stores) {
                val target = store.destination as Memory
                if (
                    target.displacement == header.type && target.width == 4 ||
                        target.displacement == header.time && target.width == 8
                )
                    continue
                val source = store.source
                val zero =
                    if (source is Register)
                        definitions.definition(store.offset, source.number).let {
                            it.operation in setOf(Operation.XOR, Operation.VECTOR_XOR) &&
                                it.destination == it.source
                        }
                    else false
                val constant = (source as? Immediate)?.value ?: if (zero) 0L else null
                val copied =
                    if (constant == null) copies.field(store.offset, source as Register) else null
                require(copied == null || copied.source == 0L && copied.field.width == target.width)
                require(target.width <= 8 || constant == null || constant == 0L)
                repeat(target.width) { byte ->
                    val at = target.displacement + byte
                    bytes.remove(at)
                    defaults.remove(at)
                    if (copied != null) bytes[at] = copied.field.offset + byte
                    else
                        defaults[at] =
                            if (byte >= 8) 0
                            else (checkNotNull(constant) ushr (byte * 8) and 255).toInt()
                }
            }
            require(bytes.isNotEmpty() && bytes.values.all { it in 0 until extent.toLong() })
            val headerBytes =
                (header.type until header.type + 4) + (header.time until header.time + 8)
            require((bytes.keys + defaults.keys).none { it in headerBytes })
            return MousePayloadCopy(extent, bytes, defaults)
        }
    }
}
