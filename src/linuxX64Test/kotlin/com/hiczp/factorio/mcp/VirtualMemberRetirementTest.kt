@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.Operation
import com.hiczp.factorio.mcp.X64Instructions.Register
import kotlin.test.*
import kotlinx.cinterop.toKString
import platform.posix.getenv

class VirtualMemberRetirementTest {
    @Test
    fun distinguishesCapturedPointersFromRepeatedLoads() {
        val code = "55 48 89 e5 53 50 48 8b 5f 18 83 e9 01 75 fb 48 89 df e8 00 01 00 00"
        fun receiver(value: String) = SysVReceiverFlow(machineCode(value), 0x1000, 64).call(18)[7]
        assertEquals(SysVReceiverFlow.Pointer(SysVReceiverFlow.Receiver(), 24, 6), receiver(code))
        val repeated = SysVReceiverFlow(machineCode(code.replace("75 fb", "75 f7")), 0x1000, 64)
        // A new load can establish a fresh member expression, but a value carried to the next
        // iteration
        // must not identify the previous iteration's object before that load executes.
        assertFalse(repeated.before(6)[3] is SysVReceiverFlow.Pointer)
        assertEquals(
            SysVReceiverFlow.Pointer(SysVReceiverFlow.Receiver(), 24, 6),
            repeated.call(18)[7],
        )
    }

    @Test
    fun rejectsBypassedDispatchForeignReceiversAndSuffixReentry() {
        val prefix = "55 48 89 e5 53 50 48 89 fb 48 8b 7f 18 48 85 ff 74 0d 48 8b 07 ff 10"
        val clear = "48 c7 43 18 00 00 00 00"
        val tail = "48 83 c4 08 5b 5d c3"
        fun resolve(code: String = "$prefix $clear $tail", slot: Int = 0, member: Long = 24) =
            VirtualMemberRetirement.analyze(machineCode(code), 0x1000, 64, member, slot)
        assertEquals(VirtualMemberRetirement.Proof(24, 21, 31), resolve())
        assertFails { resolve(slot = 1) }
        assertFails { resolve(member = 32) }
        assertFails { resolve("${prefix.replace("74 0d", "75 0d")} $clear $tail") }
        assertFails { resolve("${prefix.replace("48 8b 07", "48 8b 03")} $clear $tail") }
        assertFails { resolve("$prefix ${clear.replace("43 18", "47 18")} $tail") }
        assertFails { resolve("$prefix $clear eb df c3") }
        val bypass = prefix.replace("74 0d", "74 0f").replace("ff 10", "74 02 ff 10")
        assertFails { resolve("$bypass $clear $tail") }
        assertFails { resolve("c3 ${prefix.drop(3)} $clear $tail") }
    }

    @Test
    fun requiresExplicitAtomicRegisterModel() {
        val bytes = machineCode("f0 41 0f c1 46 08 c3")
        assertFails { X64Instructions(bytes).all() }
        val body = X64Instructions(bytes, allowAtomicExchangeAdd = true).all()
        assertEquals(Operation.ATOMIC_EXCHANGE_ADD, body.first().operation)
        assertEquals(Register(0, 4), body.first().source)
        assertEquals(null, SysVArgumentFlow(X64ControlFlow(body)).register(body.last().offset, 0))
        assertEquals(
            0,
            X64JumpTables.resolve(body, 0x1000) { _, _ -> error("No table read expected") }.size,
        )
        assertFails {
            X64Instructions(machineCode("f0 0f c1 c0"), allowAtomicExchangeAdd = true).all()
        }
    }

    @Test
    fun derivesGuardedRetirementAcrossLoopsAndSpilledPointers() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        val members =
            listOf(1, 23).map { padding ->
                MappedBinary("$directory/view_lifetime_fixture_$padding").use { file ->
                    val image = ElfImage(file.view)
                    assertFails { image.symbol("_ZTV18GarbageCollectable") }
                    fun field(name: String) =
                        image.symbol("fixture_$name").let {
                            image.virtualBytes(it.address, 8).unsigned(0, 8)
                        }

                    val method =
                        ItaniumVtable.resolve(image, "_ZTV8GameView")
                            .method(image, "_ZN18GarbageCollectable13flagForDeleteEv")
                    val member = field("game_view")
                    val proof =
                        VirtualMemberRetirement.resolve(
                            image,
                            "_ZN4GameD2Ev",
                            field("game_size"),
                            member,
                            method,
                        )
                    assertEquals(member, proof.member)
                    assertTrue(proof.call < proof.boundary)
                    val metadata =
                        ViewRetirementMetadata.resolve(
                            image,
                            field("game_size"),
                            field("view_size"),
                            member,
                        )
                    assertEquals(proof, metadata.layout.owner)
                    metadata.verify(image, 0) { address, length ->
                        image.virtualBytes(address, length.toLong()).bytes(0, length)
                    }
                    member
                }
            }
        assertNotEquals(members[0], members[1])
    }
}
