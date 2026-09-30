package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.SysVReceiverFlow.Original
import com.hiczp.factorio.mcp.SysVReceiverFlow.Receiver
import com.hiczp.factorio.mcp.X64Instructions.*

/** A native Widget flag gating the nested click dispatch inside mouse-down dispatch. */
internal object WidgetClickGate {
    fun resolve(image: ElfImage): NativeAccessor {
        val size = SysVObjectSize.resolve(image, "4agui6Widget")
        val function = image.symbol("_ZN4agui6Widget17dispatchMouseDownERKNS_10MouseEventE")
        val click = image.symbol("_ZN4agui6Widget13dispatchClickERKNS_10MouseEventE")
        EhFrames(image).function(function)
        return analyze(image.functionBytes(function, 8192), function.address, size, click.address)
    }

    fun analyze(bytes: BinaryView, address: Long, widgetSize: Long, click: Long): NativeAccessor {
        require(bytes.size in 1..8192 && address >= 0 && address <= Long.MAX_VALUE - bytes.size && click >= 0)
        val flow = SysVReceiverFlow(bytes, address, widgetSize)
        val body = flow.instructions.associateBy { it.offset }
        val pointers = SysVArgumentFlow(X64ControlFlow(flow.instructions))
        val calls = flow.instructions.filter { instruction ->
            val target = (instruction.destination as? Immediate)?.value
            instruction.offset in flow.reachable && instruction.operation == Operation.CALL &&
                    target != null && target >= -address && target <= Long.MAX_VALUE - address && target + address == click
        }
        require(calls.isNotEmpty()) { "Mouse-down entry has no nested native click dispatch" }
        for (call in calls) {
            val arguments = flow.dispatchArguments(call.offset)
            require(arguments[7] == Receiver() && arguments[6] == Original(6)) {
                "Nested click does not preserve the original widget and event"
            }
        }
        val candidates = flow.instructions.mapNotNull { instruction ->
            if (instruction.offset !in flow.reachable || instruction.operation != Operation.TEST) return@mapNotNull null
            val memory = instruction.destination as? Memory ?: return@mapNotNull null
            val literal = instruction.source as? Immediate ?: return@mapNotNull null
            if (memory.relative || memory.index != null || memory.width !in listOf(1, 2, 4, 8)) return@mapNotNull null
            val mask = literal.value.toULong()
            if (mask == 0UL || mask and (mask - 1UL) != 0UL ||
                memory.width < 8 && mask >= (1UL shl (memory.width * 8))
            ) return@mapNotNull null
            val read = pointers.memory(instruction.offset, memory) ?: return@mapNotNull null
            if (read.reference.argument != 7) return@mapNotNull null
            var previous = instruction.offset
            var branch = body[instruction.offset + instruction.size] ?: return@mapNotNull null
            while (branch.operation in listOf(Operation.NOP, Operation.ENDBR)) {
                require(flow.predecessors[branch.offset] == setOf(previous)) { "Click flag has another producer" }
                previous = branch.offset
                branch = body[branch.offset + branch.size] ?: error("Click flag leaves its body")
            }
            if (branch.operation != Operation.JCC || branch.condition !in listOf(4, 5)) return@mapNotNull null
            require(flow.predecessors[branch.offset] == setOf(previous)) { "Click flag branch has another producer" }
            val target = (branch.destination as? Immediate)?.value ?: error("Indirect click flag branch")
            val nonzero = if (branch.condition == 4) branch.offset + branch.size else target
            if (!calls.all { flow.requiresEdge(it.offset, branch.offset, nonzero) }) return@mapNotNull null
            NativeAccessor(read.reference.offset, memory.width, mask, mask.countTrailingZeroBits())
                .withinObject(widgetSize)
        }
        return candidates.distinct().singleOrNull() ?: error("Mouse-down has no unique native click flag gate")
    }
}
