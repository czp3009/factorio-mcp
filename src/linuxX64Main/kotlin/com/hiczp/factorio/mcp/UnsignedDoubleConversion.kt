package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*
import kotlin.math.pow

/**
 * Verifies the arithmetic of a split unsigned-64 SSE conversion, independent of registers and
 * adjacency.
 */
internal class UnsignedDoubleConversion(
    private val flow: X64ControlFlow,
    private val address: Long,
    private val input: SysVArgumentFlow.Read?,
    private val literal: (Long, Int) -> BinaryView,
    private val callResult: Long? = null,
) {
    private sealed interface Word

    private data class Constant(val bits: Long) : Word

    private data class Part(val index: Int) : Word

    private sealed interface Lane

    private data object Unknown : Lane

    private data class Bits(val low: Word, val high: Word) : Lane

    private data class Scaled(val part: Int, val factor: Double) : Lane

    private data object Converted : Lane

    private val definitions = ScalarExpression(flow)
    private val arguments = SysVArgumentFlow(flow)
    private val cache = mutableMapOf<Pair<Long, Int>, List<Lane>>()
    private val returned = callResult?.let { PrivateValueCopies.Read(it, NativeField(0, 8)) }
    private val copies =
        returned?.let { PrivateValueCopies(flow, emptyMap(), callResults = mapOf(it.source to it)) }

    fun verify(site: Long, register: Register) {
        require((input?.width == 8) != (callResult != null))
        require(register.number in 16..31 && register.width == 8)
        require(value(site, register.number, 0).first() == Converted) {
            "Timestamp arithmetic is not an unsigned 64-bit conversion"
        }
    }

    private fun value(site: Long, register: Int, depth: Int): List<Lane> {
        require(depth < 48 && cache.size < 128) {
            "Unsigned timestamp conversion exceeds its local bound"
        }
        cache[site to register]?.let {
            return it
        }
        val definition = definitions.definition(site, register)
        val target =
            definition.destination as? Register ?: error("Timestamp definition has no register")
        require(target.number == register && register in 16..31)
        fun before() = value(definition.offset, register, depth + 1)
        fun source(): List<Lane> =
            when (val operand = definition.source) {
                is Register -> {
                    if (operand.number < 16) {
                        require(
                            operand.width == 8 &&
                                returned != null &&
                                copies?.field(definition.offset, operand) == returned
                        )
                        listOf(Bits(Part(0), Part(1)), Bits(Constant(0), Constant(0)))
                    } else {
                        require(operand.number in 16..31 && operand.width in listOf(8, 16))
                        value(definition.offset, operand.number, depth + 1)
                    }
                }
                is Memory -> {
                    require(operand.width in listOf(8, 16))
                    if (input != null && arguments.memory(definition.offset, operand) == input) {
                        listOf(Bits(Part(0), Part(1)), Bits(Constant(0), Constant(0)))
                    } else {
                        require(operand.relative && operand.base == null && operand.index == null)
                        val next = address + definition.offset + definition.size
                        require(
                            next >= address &&
                                operand.displacement >= -next &&
                                operand.displacement <= Long.MAX_VALUE - next
                        )
                        val bytes = literal(next + operand.displacement, operand.width)
                        require(bytes.size == operand.width.toLong())
                        List(2) { index ->
                            if (index * 8 >= operand.width) Bits(Constant(0), Constant(0))
                            else
                                Bits(
                                    Constant(bytes.unsigned(index * 8L, 4)),
                                    Constant(bytes.unsigned(index * 8L + 4, 4)),
                                )
                        }
                    }
                }
                else -> error("Timestamp conversion has an unknown source")
            }
        val result =
            when (definition.operation) {
                Operation.SCALAR_MOV ->
                    source().let {
                        if ((definition.source as? Register)?.number in 16..31)
                            listOf(it.first(), Unknown)
                        else it
                    }
                Operation.VECTOR_MOV -> source()
                Operation.VECTOR_UNPACK_LOW_FLOATS,
                Operation.VECTOR_UNPACK_LOW_DWORDS -> {
                    val left =
                        before().first() as? Bits
                            ?: error("Timestamp unpack changes floating values")
                    val right =
                        source().first() as? Bits
                            ?: error("Timestamp unpack has no constant exponent words")
                    listOf(Bits(left.low, right.low), Bits(left.high, right.high))
                }
                Operation.VECTOR_UNPACK_HIGH_QWORDS -> listOf(before()[1], source()[1])
                Operation.VECTOR_SUBTRACT_DOUBLES ->
                    before().zip(source()).map { (left, right) ->
                        val bits =
                            left as? Bits ?: error("Timestamp subtraction repeats arithmetic")
                        val part =
                            bits.low as? Part
                                ?: error("Timestamp lane has no original integer word")
                        val exponent =
                            bits.high as? Constant
                                ?: error("Timestamp lane has a variable exponent")
                        require(exponent.bits and 0x800fffffL == 0L)
                        val power = ((exponent.bits ushr 20) and 0x7ff).toInt() - 1023
                        require(power in 52..84)
                        val baseline = Double.fromBits(exponent.bits shl 32)
                        val constant =
                            right as? Bits ?: error("Timestamp subtraction has a variable baseline")
                        val low =
                            (constant.low as? Constant)?.bits
                                ?: error("Timestamp baseline has variable bits")
                        val high =
                            (constant.high as? Constant)?.bits
                                ?: error("Timestamp baseline has variable bits")
                        require(Double.fromBits(low or (high shl 32)) == baseline)
                        Scaled(part.index, 2.0.pow(power - 52))
                    }
                Operation.DOUBLE_ADD -> {
                    val left = before()
                    val low =
                        left.first() as? Scaled
                            ?: error("Timestamp addition has no exact integer lane")
                    val high =
                        source().first() as? Scaled
                            ?: error("Timestamp addition has no exact integer lane")
                    require(setOf(low, high) == setOf(Scaled(0, 1.0), Scaled(1, 4294967296.0)))
                    listOf(Converted, left[1])
                }
                else -> error("Unsupported unsigned timestamp arithmetic: ${definition.operation}")
            }
        cache[site to register] = result
        return result
    }
}
