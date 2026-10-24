package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Original Event modifier bits copied into the original InputState on both keyboard cases. */
internal object InputStateModifiers {
    fun resolve(
        image: ElfImage,
        size: Long,
        header: EventHeader,
        update: InputStateKeyUpdate,
        payload: KeyboardEventPayload,
    ): Long =
        analyze(
            X64ControlFlow.resolve(image, image.symbol("_ZN10InputState6updateERK5Event")),
            size,
            header,
            update,
            payload.modifiers,
        )

    fun analyze(
        flow: X64ControlFlow,
        size: Long,
        header: EventHeader,
        update: InputStateKeyUpdate,
        event: Long,
    ): Long {
        require(size in 2..16 * 1024 * 1024 && event in 0..header.extent - 2)
        val arguments = SysVArgumentFlow(flow)
        val bits = ScalarBits(flow, mapOf(6 to header.extent.toLong()))
        val input = SysVArgumentFlow.Read(SysVArgumentFlow.Reference(6, event), 2)
        val copies =
            flow.instructions.mapNotNull { instruction ->
                val target = instruction.destination as? Memory ?: return@mapNotNull null
                if (
                    instruction.offset !in flow.reachable ||
                        instruction.operation != Operation.MOV ||
                        target.width != 2
                )
                    return@mapNotNull null
                val field =
                    arguments.memory(instruction.offset, target)?.reference
                        ?: return@mapNotNull null
                if (field.argument != 7 || field.offset !in 0..size - 2) return@mapNotNull null
                if (
                    (0 until 16).all {
                        bits.operand(instruction.offset, instruction.source, it) ==
                            ScalarBits.Input(input, it)
                    }
                )
                    instruction.offset to field.offset
                else null
            }
        val (store, field) = copies.single()
        for (case in listOf(update.press, update.release)) {
            val pending = ArrayDeque<Long>()
            val visited = mutableSetOf<Long>()
            pending.add(case.stores.first().site)
            while (pending.isNotEmpty()) {
                val site = pending.removeFirst()
                if (site == store || !visited.add(site)) continue
                require(
                    visited.size <= 512 && flow.body.getValue(site).operation != Operation.RET
                ) {
                    "Keyboard case can return without storing its original modifiers"
                }
                pending.addAll(flow.successors.getValue(site))
            }
            require(flow.predecessors[store].orEmpty().any { it in visited })
        }
        return field
    }
}
