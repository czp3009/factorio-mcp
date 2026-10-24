@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*
import kotlin.test.*
import kotlinx.cinterop.toKString
import platform.posix.getenv

class InputClockTest {
    @Test
    fun verifiesTheSdkClockAndFloatingReturnWithoutExternalWrites() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        for (divisor in listOf(1000, 2000)) MappedBinary("$directory/input_clock_fixture_$divisor")
            .use { file ->
                val image = ElfImage(file.view)
                val function = image.symbol("fixture_input_time")
                val code = image.functionBytes(function, 2048)
                val slot = image.symbol("fixture_ticks").address
                fun analyze(
                    bytes: BinaryView = code,
                    clock: Long = slot,
                    literal: (Long, Int) -> BinaryView = { address, width ->
                        image.virtualBytes(address, width.toLong())
                    },
                ) = InputClock.analyze(bytes, function.address, clock, literal)
                assertEquals(divisor.toDouble(), analyze())
                assertFails { analyze(clock = slot + 8) }
                assertFails { analyze(literal = { _, width -> BinaryView(ByteArray(width)) }) }
                val body = X64Instructions(code).all()
                val end = body.last { it.operation == Operation.RET }.offset.toInt()
                val original = code.bytes(0, code.size.toInt())
                for (suffix in listOf("48 89 07 c3", "66 0f 57 c0 c3", "48 89 c3 c3")) {
                    val extra = machineCode(suffix)
                    assertFails {
                        analyze(
                            BinaryView(
                                original.copyOfRange(0, end) + extra.bytes(0, extra.size.toInt())
                            )
                        )
                    }
                }
                val call = body.single { it.operation == Operation.CALL }
                // Narrow MOVQ to MOVD without changing instruction lengths or literal addresses.
                val move =
                    body.single {
                        it.offset > call.offset &&
                            (it.source as? Register)?.let { register ->
                                register.number < 16 && register.width == 8
                            } == true &&
                            (it.destination as? Register)?.number in 16..31
                    }
                val altered = original.copyOf()
                val rex =
                    (move.offset.toInt() until (move.offset + move.size).toInt()).single {
                        altered[it].toInt() and 0xf8 == 0x48
                    }
                altered[rex] = (altered[rex].toInt() and 0xf7).toByte()
                assertFails { analyze(BinaryView(altered)) }
            }
    }
}
