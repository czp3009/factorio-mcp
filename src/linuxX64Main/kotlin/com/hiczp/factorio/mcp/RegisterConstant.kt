package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Complete constant bits on every incoming path, with native zero extension and call clobbers. */
internal class RegisterConstant(private val flow: X64ControlFlow) {
    private val definitions = ScalarExpression(flow)
    private val values = mutableMapOf<Pair<Long, Register>, Long>()
    private val active = mutableSetOf<Pair<Long, Register>>()
    private var work = 0

    fun value(site: Long, register: Register, width: Int = register.width): Long {
        require(
            site in flow.reachable &&
                register.number in 0..15 &&
                register.width in listOf(1, 2, 4, 8) &&
                width in listOf(1, 2, 4, 8) &&
                width <= register.width
        )
        return constant(site, Register(register.number, width))
    }

    private fun mask(value: Long, width: Int): Long =
        if (width == 8) value else value and ((1L shl (width * 8)) - 1)

    private fun constant(site: Long, register: Register): Long {
        val key = site to register
        values[key]?.let {
            return it
        }
        require(++work <= 8192 && active.size < 128 && active.add(key)) {
            "Register constant is cyclic or exceeds its bound"
        }
        try {
            val incoming = definitions.definitions(site, register.number)
            require(incoming.isNotEmpty()) { "Register constant has no reaching definition" }
            val results =
                incoming
                    .map { instruction ->
                        val target =
                            instruction.destination as? Register
                                ?: error("Register constant is clobbered by a native call")
                        require(
                            target.number == register.number &&
                                target.width in listOf(1, 2, 4, 8) &&
                                (target.width >= register.width ||
                                    target.width == 4 && register.width == 8)
                        ) {
                            "Partial register definition leaves unknown constant bits"
                        }
                        fun source(): Long =
                            when (val operand = instruction.source) {
                                is Immediate -> mask(operand.value, target.width)
                                is Register -> {
                                    require(
                                        operand.number in 0..15 && operand.width == target.width
                                    )
                                    constant(instruction.offset, operand)
                                }
                                else -> error("Register constant has an unsupported source")
                            }
                        val result =
                            when (instruction.operation) {
                                Operation.MOV -> source()
                                Operation.XOR -> {
                                    require(instruction.source == target) {
                                        "Register XOR is not a complete zero definition"
                                    }
                                    0L
                                }
                                Operation.MOVZX -> {
                                    val operand =
                                        instruction.source as? Register
                                            ?: error("Constant widening has no register source")
                                    require(
                                        operand.number in 0..15 &&
                                            operand.width in listOf(1, 2) &&
                                            operand.width < target.width
                                    )
                                    constant(instruction.offset, operand)
                                }
                                else -> error("Register definition is not a constant copy")
                            }
                        mask(mask(result, target.width), register.width)
                    }
                    .distinct()
            return (results.singleOrNull()
                    ?: error("Incoming paths disagree on the register constant"))
                .also { values[key] = it }
        } finally {
            active.remove(key)
        }
    }
}
