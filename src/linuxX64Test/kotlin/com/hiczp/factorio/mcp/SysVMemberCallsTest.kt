@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class SysVMemberCallsTest {
    @Test
    fun selectedVirtualCallRetainsIncomingEdgesFromTheCompleteBody() {
        val prefix = "53 48 89 fb 48 8b 7f 10 48 8b 07 ff 50 18"
        assertEquals(16L, SysVMemberCalls.virtualAt(machineCode("$prefix 5b c3"), 0x1000, 3, 32, 11))
        // Reloading the member from the preserved owner establishes a fresh receiver on every iteration.
        val loop = "53 48 89 fb 48 8b 7b 10 48 8b 07 ff 50 18 85 c0 75 f2 5b c3"
        assertEquals(16L, SysVMemberCalls.virtualAt(machineCode(loop), 0x1000, 3, 32, 11))
        assertFails { SysVMemberCalls.virtualAt(machineCode(loop.replace("8b 7b 10", "8b 7f 10")), 0x1000, 3, 32, 11) }
        // A later backedge replaces the receiver before reentering the same virtual call setup.
        assertFails { SysVMemberCalls.virtualAt(machineCode("$prefix 48 89 f7 eb f1"), 0x1000, 3, 32, 11) }
        assertFails { SysVMemberCalls.virtualAt(machineCode("$prefix 5b c3"), 0x1000, 4, 32, 11) }
        assertFails { SysVMemberCalls.virtualAt(machineCode("$prefix 5b c3"), 0x1000, 3, 32, 12) }
    }

    @Test
    fun resolvesEntryPrefixAssociationWithoutInterpretingUnrelatedLaterCleanup() {
        val valid = "53 48 89 fb e8 f7 01 00 00 48 8b 7b 10 e8 ee 00 00 00 f0"
        fun analyze(bytes: String) = SysVMemberCalls.directPrefix(machineCode(bytes), 0x1000, 0x1100, 32)
        assertEquals(16L, analyze(valid))
        assertFails { analyze(valid.replace("48 89 fb", "48 89 f3")) }
        assertFails { analyze(valid.replace("8b 7b 10", "8b 7f 10")) }
        assertFails { analyze(valid.replace("8b 7b 10", "8b 7b 20")) }
        assertFails { analyze(valid.replace("e8 ee 00", "e8 ef 00")) }
    }

    @Test
    fun prunesOnlyProvenNonReturningCallsFromNormalEntryPaths() {
        val code =
            machineCode("53 48 89 fb 85 f6 75 0e 48 8b 7b 10 e8 ef 00 00 00 5b c3 90 90 90 e8 e5 01 00 00 48 89 f3 eb e8")
        assertFails { SysVMemberCalls.direct(code, 0x1000, 0x1100, 32) }
        assertEquals(16L, SysVMemberCalls.direct(code, 0x1000, 0x1100, 32, setOf(0x1200)))
    }

    @Test
    fun derivesDirectAndVirtualMembersFromCompilerGeneratedDispatch() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        for (padding in listOf(1, 23)) MappedBinary("$directory/member_call_fixture_$padding").use { file ->
            val image = ElfImage(file.view)
            fun constant(name: String): Long =
                image.symbol(name).let { image.virtualBytes(it.address, 8).unsigned(0, 8) }

            val ownerSize = constant("fixture_owner_size")
            val caller = image.symbol("fixture_member_calls")
            val code = image.functionBytes(caller, 8192)
            val method = ItaniumVtable.resolve(image, "_ZTV5Child").method(image, "_ZN5ChildD0Ev")
            assertEquals(
                listOf(constant("fixture_first_offset"), constant("fixture_second_offset")),
                SysVMemberCalls.virtual(code, caller.address, method.slot, ownerSize)
            )
            val first = X64Instructions(code).all(2048).first {
                it.operation == X64Instructions.Operation.CALL &&
                        (it.destination as? X64Instructions.Memory)?.displacement == method.slot * 8L
            }
            assertEquals(
                constant("fixture_first_offset"),
                SysVMemberCalls.virtualAt(code, caller.address, method.slot, ownerSize, first.offset)
            )
            assertEquals(
                constant("fixture_leaf_offset"),
                SysVMemberCalls.direct(image, "fixture_member_calls", "_ZN4Leaf6finishEv", ownerSize)
            )
            assertEquals(
                constant("fixture_leaf_offset"),
                SysVMemberCalls.direct(image, "fixture_argument_reads", "_ZN4Leaf7consumeEPKjj", ownerSize)
            )
            assertFails { SysVMemberCalls.virtual(code, caller.address, method.slot + 1, ownerSize) }
            assertFails { SysVMemberCalls.virtual(code, caller.address, method.slot, 8) }
        }
    }

    @Test
    fun requiresMatchingVirtualReceiverAndPreservedFrame() {
        val valid = "53 48 89 fb 48 8b 7f 10 48 8b 07 ff 50 18 5b c3"
        fun analyze(code: String) = SysVMemberCalls.virtual(machineCode(code), 0x1000, 3, 32)
        assertEquals(listOf(16L), analyze(valid))
        for (code in listOf(
            valid.replace("48 8b 07", "48 8b 06"), // Another argument's vtable.
            valid.replace("48 8b 07", "48 8b 47 08"), // A non-primary vtable without a receiver adjustment.
            valid.replace("ff 50 18", "48 89 df ff 50 18"), // Parent passed instead of the child.
            valid.replace("ff 50 18", "89 ff ff 50 18"), // Truncated receiver.
            valid.removePrefix("53 "), // Unaligned call frame.
            valid.replace("ff 50 18", "48 89 e6 ff 50 18"), // Saved frame exposed to the callee.
        )) assertFails { analyze(code) }
        // Reloading the same parent member after another call may produce a different child.
        assertFails { analyze("53 48 89 fb 4c 8b 63 10 4d 8b 2c 24 e8 ef 00 00 00 48 8b 7b 10 41 ff 55 18 5b c3") }
    }

    @Test
    fun rejectsAmbiguousDirectMembersAndClobberedProvenance() {
        val valid = "53 48 89 fb 48 8b 7b 10 e8 f3 00 00 00 48 8b 7b 10 e8 ea 00 00 00 5b c3"
        fun analyze(code: String) = SysVMemberCalls.direct(machineCode(code), 0x1000, 0x1100, 32)
        assertEquals(16L, analyze(valid))
        assertFails { analyze(valid.replace("48 8b 7b 10 e8 ea", "48 8b 7b 18 e8 ea")) }
        assertFails { analyze(valid.replace("48 8b 7b 10 e8 ea", "48 8b 7f 10 e8 ea")) }
        assertFails { analyze(valid.replace("48 89 fb", "48 89 f3")) }
    }

    @Test
    fun unrelatedReadsCannotEstablishAReceiverOrAuthorizeWrites() {
        fun analyze(prefix: String): Long {
            val callOffset = prefix.split(' ').size
            val displacement = 0x100 - callOffset - 5
            val call = (0..3).joinToString(" ") { ((displacement shr (it * 8)) and 255).toString(16).padStart(2, '0') }
            return SysVMemberCalls.direct(machineCode("$prefix e8 $call 5b c3"), 0x1000, 0x1100, 32)
        }
        assertEquals(16L, analyze("53 48 89 fb 48 8b 06 48 8b 10 48 8b 7b 10"))
        assertFails { analyze("53 48 89 fb 48 8b 3e") }
        assertFails { analyze("53 48 89 fb 48 89 06 48 8b 7b 10") }
        assertFails { analyze("53 48 89 fb 48 8b 06 48 89 10 48 8b 7b 10") }
        // A frame pointer cannot become an innocuous Unknown value before escaping to another call.
        for (change in listOf("48 83 c6 01", "48 09 f6", "89 f6", "40 b6 00", "48 0f 45 f0")) {
            assertFails { analyze("53 48 89 fb 48 89 e6 $change 48 8b 7b 10") }
        }
        assertFails { analyze("53 48 89 fb 48 89 e6 85 c0 74 03 48 89 c6 48 8b 7b 10") }
        assertFails { analyze("53 48 89 fb 48 83 ec 10 85 c0 74 04 48 89 24 24 48 8b 34 24 48 8b 7b 10") }
        val savedFrame = "53 48 89 fb 48 83 ec 10 48 89 24 24"
        assertFails { analyze("$savedFrame 8b 34 24 48 8b 7b 10") }
        assertFails { analyze("$savedFrame c7 04 24 00 00 00 00 48 8b 34 24 48 8b 7b 10") }
        assertEquals(16L, analyze("$savedFrame 48 c7 04 24 00 00 00 00 48 8b 34 24 48 8b 7b 10"))
    }
}
