package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.SysVReceiverFlow.Pointer
import com.hiczp.factorio.mcp.SysVReceiverFlow.Receiver
import com.hiczp.factorio.mcp.X64Instructions.*

/** Element extent from a typed traversal loop. Does not read or invoke the container at runtime. */
internal object VectorElementSize {
    fun resolve(image: ElfImage, container: String, element: String): Long {
        val owner = image.symbol(container)
        val destructor = image.symbol(element)
        EhFrames(image).function(owner)
        EhFrames(image).function(destructor)
        image.functionBytes(destructor, 1)
        require(owner.size in 1..4096)
        return analyze(image.functionBytes(owner, 4096), owner.address, destructor.address)
    }

    fun analyze(bytes: BinaryView, address: Long, destructor: Long): Long {
        require(bytes.size in 1..4096 && address >= 0 && destructor > 0)
        // A recursive call to the exact entry is an ordinary ABI call, not an edge into the current frame.
        // Keep the original bytes for receiver analysis and loaded evidence; normalize only this CFG target.
        val target = if (destructor == address) -1L else destructor - address
        val instructions = X64Instructions(bytes).all(1024).map {
            if (destructor == address && it.operation == Operation.CALL && it.destination == Immediate(0))
                it.copy(destination = Immediate(target)) else it
        }
        val flow = X64ControlFlow(instructions)
        val call = flow.instructions.single {
            it.offset in flow.reachable && it.operation == Operation.CALL &&
                    it.destination == Immediate(target)
        }
        val prefix = bytes.slice(0, call.offset + call.size)
        // This is a symbolic analysis bound, not a container size used by any runtime reader.
        val values = SysVReceiverFlow(prefix, address, 4096)
        val atCall = values.call(call.offset)
        val begin = atCall[7] as? Pointer ?: error("Element destruction has no original container cursor")
        require(begin.base == Receiver() && begin.offset in 0..4088 && begin.offset % 8 == 0L)
        val preserved = setOf(3, 5, 12, 13, 14, 15)
        val cursor = atCall.indices.filter { it in preserved && atCall[it] == begin }.singleOrNull()
            ?: error("Element cursor is not uniquely preserved across destruction")
        var position = call.offset + call.size
        var stride: Long? = null
        var end: Pointer? = null
        var compared = false
        var loop: Long? = null
        repeat(16) {
            if (loop != null) return@repeat
            val instruction = flow.body.getValue(position)
            position += instruction.size
            when (instruction.operation) {
                Operation.NOP -> Unit
                Operation.ADD, Operation.SUB -> {
                    require(stride == null && !compared && instruction.destination == Register(cursor, 8))
                    val amount = (instruction.source as? Immediate)?.value
                        ?: error("Element cursor has no constant stride")
                    require(amount in -(16 * 1024 * 1024)..(16 * 1024 * 1024))
                    stride = (if (instruction.operation == Operation.SUB) -amount else amount).also {
                        require(it in 1..16 * 1024 * 1024) { "Element stride exceeds its analysis bound" }
                    }
                }

                Operation.CMP -> {
                    require(stride != null && !compared)
                    val operands = listOf(instruction.destination, instruction.source)
                    require(Register(cursor, 8) in operands)
                    val other = operands.single { it != Register(cursor, 8) } as? Register
                        ?: error("Element loop does not compare its preserved end pointer")
                    require(other.width == 8 && other.number in preserved)
                    end = (atCall[other.number] as? Pointer)?.also {
                        require(
                            it.base == Receiver() && it.offset in 0..4088 && it.offset % 8 == 0L &&
                                    it.offset != begin.offset
                        )
                    } ?: error("Element loop end is not from the original container")
                    compared = true
                }

                Operation.JCC -> {
                    require(compared && instruction.condition == 5)
                    loop = (instruction.destination as? Immediate)?.value?.also {
                        require(
                            it > maxOf(begin.loadedAt, checkNotNull(end).loadedAt) && it <= call.offset &&
                                    it in flow.body
                        ) { "Element loop reloads or loses its advancing cursor" }
                    } ?: error("Element loop has no direct backedge")
                }

                else -> error("Element destruction has an unsupported loop suffix")
            }
        }
        val header = checkNotNull(loop) { "Element destruction has no bounded loop" }
        val limit = checkNotNull(end)
        val empty = flow.instructions.filter { it.offset < header && it.operation == Operation.JCC }.singleOrNull()
            ?: error("Element loop has no unique empty-range guard")
        val comparison = flow.instructions.singleOrNull { it.offset + it.size == empty.offset }
            ?: error("Element range guard has no adjacent comparison")
        val skip = (empty.destination as? Immediate)?.value
        require(
            comparison.operation == Operation.CMP && empty.condition == 4 &&
                    skip != null && skip >= position && skip in flow.body
        ) { "Element range guard does not skip the complete loop" }
        val before = values.before(comparison.offset)
        val operands = listOf(comparison.destination, comparison.source).map {
            val register = it as? Register ?: error("Element range guard does not compare pointer registers")
            require(register.width == 8)
            before[register.number]
        }
        require(operands.toSet() == setOf(begin, limit)) { "Element loop guard uses different container bounds" }

        // Replaying only the loop header must forward the advancing cursor to the same typed destructor.
        val aliases = mutableSetOf(cursor)
        var next = header
        while (next < call.offset) {
            val instruction = flow.body.getValue(next)
            next += instruction.size
            when (instruction.operation) {
                Operation.NOP -> Unit
                Operation.MOV -> {
                    val target = instruction.destination as? Register ?: error("Element loop header writes memory")
                    val source = instruction.source as? Register ?: error("Element loop header reloads its cursor")
                    require(target.width == 8 && source.width == 8 && target.number !in preserved && target.number != 4)
                    val copied = source.number in aliases
                    aliases.remove(target.number)
                    if (copied) aliases.add(target.number)
                }

                else -> error("Element loop header has an unsupported operation")
            }
        }
        require(next == call.offset && 7 in aliases) { "Next element is not the next typed destructor receiver" }
        // No other normal-entry path may enter the call or cursor-update block midway.
        val region = flow.instructions.filter { it.offset in header until position }.map { it.offset }.toSet()
        for (site in region - header) require(flow.predecessors[site].orEmpty().all { it in region }) {
            "Element destruction loop has an additional entry"
        }
        return checkNotNull(stride)
    }
}
