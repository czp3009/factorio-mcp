package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/**
 * Constant inline string passed from a private frame. Every byte must be defined on all incoming
 * paths.
 */
internal class LocalStringArgument(
    private val flow: X64ControlFlow,
    private val string: NativeStringLayout,
) {
    private val locals = SysVLocalArgument(flow)
    private val arguments = SysVArgumentFlow(flow)
    private val constants = RegisterConstant(flow)
    private var work = 0

    init {
        require(
            string.size in 16..256 &&
                string.data in 0..string.size - 8 &&
                string.length in 0..string.size - 8 &&
                string.local in 0 until string.size
        )
    }

    fun text(call: Long, register: Int): String {
        return textAt(call, locals.argument(call, register, string.size.toInt()))
    }

    /**
     * The enclosing object is independently connected to the native byte-view input by the caller.
     */
    fun textAt(call: Long, objectAddress: Long): String {
        work = 0
        val stack = checkNotNull(locals.registers(call)[4])
        require(call in flow.reachable && objectAddress >= stack && objectAddress <= -string.size)
        val data =
            stores(call, objectAddress + string.data) { instruction, location ->
                require(
                    instruction.operation == Operation.MOV &&
                        instruction.destination is Memory &&
                        instruction.destination.width == 8 &&
                        location == objectAddress + string.data
                )
                val source =
                    instruction.source as? Register ?: error("String data is not a local address")
                require(source.width == 8)
                locals.registers(instruction.offset)[source.number]
                    ?: error("String data leaves the private frame")
            }
        require(data == objectAddress + string.local) { "String uses unsupported nonlocal storage" }
        val length = unsigned(call, objectAddress + string.length, 8)
        require(length in 1..128 && string.local + length < string.size)
        val bytes = ByteArray(length.toInt() + 1) { unsigned(call, data + it, 1).toByte() }
        require(bytes.last() == 0.toByte() && bytes.dropLast(1).none { it == 0.toByte() })
        return bytes.copyOf(bytes.size - 1).decodeToString(throwOnInvalidSequence = true)
    }

    fun constant(call: Long, register: Int): Long = value(call, Register(register, 8), 8)

    private fun unsigned(site: Long, address: Long, width: Int): Long {
        require(width in 1..8)
        return (0 until width).fold(0L) { result, index ->
            val byte =
                stores(site, address + index) { instruction, location ->
                    require(
                        instruction.operation == Operation.MOV && instruction.destination is Memory
                    )
                    val target = instruction.destination
                    require(target.width in listOf(1, 2, 4, 8))
                    val offset = (address + index - location).toInt()
                    (value(instruction.offset, instruction.source, target.width) ushr
                        (offset * 8)) and 255L
                }
            result or (byte shl (index * 8))
        }
    }

    private fun value(site: Long, operand: Operand?, width: Int): Long {
        require(++work <= 8192 && width in 1..8)
        fun mask(value: Long, bytes: Int): Long =
            if (bytes == 8) value else value and ((1L shl (bytes * 8)) - 1)
        return when (operand) {
            is Immediate -> mask(operand.value, width)
            is Register -> {
                require(operand.number in 0..15 && operand.width >= width)
                constants.value(site, operand, width)
            }
            else -> error("Literal has an unsupported source")
        }
    }

    private fun <T> stores(site: Long, address: Long, read: (Instruction, Long) -> T): T {
        require(site in flow.reachable && address in -16384..-1)
        val active = mutableSetOf<Long>()
        val cached = mutableMapOf<Long, T>()
        fun visit(position: Long): T {
            cached[position]?.let {
                return it
            }
            require(++work <= 8192 && active.size < 256 && active.add(position)) {
                "Literal storage search is cyclic or exceeds its bound"
            }
            try {
                val instruction = flow.body.getValue(position)
                require(instruction.operation != Operation.CALL) {
                    "A native call can invalidate private literal storage"
                }
                val target = instruction.destination as? Memory
                if (
                    target != null &&
                        instruction.operation !in
                            setOf(
                                Operation.CMP,
                                Operation.TEST,
                                Operation.BIT_TEST,
                                Operation.SCALAR_COMPARE,
                            )
                ) {
                    val location = locals.address(position, target)
                    if (location != null) {
                        require(
                            location >= checkNotNull(locals.registers(position)[4]) &&
                                location <= -target.width
                        )
                        if (address in location until location + target.width)
                            return read(instruction, location)
                    } else {
                        require(
                            arguments.memory(position, target)?.reference?.argument in
                                listOf(7, 6, 2, 1, 8, 9)
                        ) {
                            "A store with unknown provenance may overlap the literal"
                        }
                    }
                }
                val predecessors = flow.predecessors[position].orEmpty()
                require(predecessors.isNotEmpty()) { "Literal byte is not initialized" }
                val result =
                    predecessors.map(::visit).distinct().singleOrNull()
                        ?: error("Incoming paths disagree on literal storage")
                cached[position] = result
                return result
            } finally {
                active.remove(position)
            }
        }
        val predecessors = flow.predecessors[site].orEmpty()
        require(predecessors.isNotEmpty())
        return predecessors.map(::visit).distinct().singleOrNull()
            ?: error("Incoming paths disagree on literal storage")
    }
}
