@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class SysVGlobalMemberTest {
    @Test
    fun requiresOriginalReceiverExactRootAndAnExclusiveEqualityGuard() {
        // Synthetic fixture addresses and layouts are independent of any game executable.
        val prefix = "48 89 fb 48 8b 05 f6 00 00 00"
        val guard = "48 39 58 10 75 08 48 c7 40 10 00 00 00 00 c3"
        fun analyze(code: String) = SysVGlobalMember.analyze(machineCode(code), 0x1000, 0x1100, 32, 64)
        assertEquals(16, analyze("$prefix $guard"))
        for (code in listOf(
            "$prefix 48 89 f3 $guard", // The receiver now comes from another argument.
            "$prefix 48 83 c3 08 $guard", // An adjusted pointer is not object identity.
            "$prefix 48 89 f0 $guard", // The memory base is not the selected context.
            "$prefix ${guard.replace("75 08", "74 08")}", // Clear on inequality.
            "$prefix ${guard.replace("c7 40 10", "c7 40 18")}", // Clear a different member.
            "$prefix ${guard.replace("00 00 00 00 c3", "01 00 00 00 c3")}",
            "$prefix 48 85 f6 74 06 $guard", // A second predecessor can bypass equality.
        )) assertFails { analyze(code) }
    }

    @Test
    fun derivesIdentityGuardedMemberAcrossCallsAndAtomicLoops() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        for (padding in listOf(1, 23)) MappedBinary("$directory/global_member_fixture_$padding").use { file ->
            val image = ElfImage(file.view)
            fun constant(name: String): Long =
                image.symbol(name).let { image.virtualBytes(it.address, 8).unsigned(0, 8) }

            val contextSize = constant("fixture_context_size")
            val receiverSize = constant("fixture_object_size")
            assertEquals(
                constant("fixture_current_offset"),
                SysVGlobalMember.resolve(image, "_ZN6ObjectD2Ev", "fixture_context", contextSize, receiverSize)
            )
            val symbol = image.symbol("_ZN6ObjectD2Ev")
            val code = image.functionBytes(symbol, 8192)
            val global = image.symbol("fixture_context").address
            assertFails { SysVGlobalMember.analyze(code, symbol.address, global + 8, contextSize, receiverSize) }
            assertFails { SysVGlobalMember.analyze(code, symbol.address, global, 8, receiverSize) }
            assertFails { SysVGlobalMember.analyze(code, symbol.address, global, contextSize, 1) }
            val decoded = X64Instructions(code).all()
            val compareIndex = decoded.indexOfLast { it.operation == X64Instructions.Operation.CMP }
            val branch = decoded[compareIndex + 1]
            val reversed = code.bytes(0, code.size.toInt())
            val opcode = branch.offset.toInt() + if (branch.size == 6) 1 else 0
            reversed[opcode] = (reversed[opcode].toInt() xor 1).toByte()
            assertFails {
                SysVGlobalMember.analyze(
                    BinaryView(reversed),
                    symbol.address,
                    global,
                    contextSize,
                    receiverSize
                )
            }
        }
    }
}
