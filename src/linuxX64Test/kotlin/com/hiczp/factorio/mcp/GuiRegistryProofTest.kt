package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class GuiRegistryProofTest {
    @Test
    fun preservedMemberPointersSurviveUnrelatedLoopsButRejectAmbiguityAndClobbers() {
        fun pointer(code: String, register: Int): OriginalPointerOrigins.Value {
            val flow = X64ControlFlow(X64Instructions(machineCode(code)).all())
            return GuiRegistryProof.pointer(flow, OriginalPointerOrigins(flow), flow.instructions.last().offset, register)
        }
        val gui = OriginalPointerOrigins.Load(OriginalPointerOrigins.Argument(7), 64, 0)
        assertEquals(OriginalPointerOrigins.Load(gui, 40, 12),
            pointer("48 8b 47 40 48 89 c3 90 ff c9 75 fb 48 8b 7b 28 c3", 7))
        assertEquals(OriginalPointerOrigins.Adjusted(gui, 32),
            pointer("48 8b 47 40 48 89 c3 90 ff c9 75 fb 48 8d 4b 20 c3", 1))
        assertFails { pointer("85 c9 74 06 48 8b 47 40 eb 04 48 8b 47 48 c3", 0) }
        assertFails { pointer("48 8b 47 40 e8 f7 00 00 00 c3", 0) }
        assertFails { pointer("8b 47 40 c3", 0) }
    }

    @Test
    fun constantExtentsPreserveIntegerNarrowingAndRejectRuntimeInputs() {
        val literal = ScalarExpression.Literal(320, 4)
        assertEquals(320L, GuiRegistryProof.constant(ScalarExpression.Narrow(literal, 4)))
        assertEquals(64L, GuiRegistryProof.constant(ScalarExpression.Narrow(literal, 1)))
        assertFails {
            GuiRegistryProof.constant(ScalarExpression.Input(0,
                SysVArgumentFlow.Read(SysVArgumentFlow.Reference(7, 16), 4), 4))
        }
    }

    @Test
    fun abstractBaseExtentRequiresDirectOriginalReceiverAccesses() {
        fun extent(code: String) = GuiRegistryProof.receiverExtent(
            X64ControlFlow(X64Instructions(machineCode(code)).all()))
        assertEquals(100L, extent("53 48 89 fb c7 43 60 00 00 00 00 5b c3"))
        assertEquals(96L, extent("48 8b 47 58 c3"))
        assertFails { extent("48 8b 46 58 c3") }
        assertFails { extent("48 8b 44 c7 58 c3") }
        assertFails { extent("48 8b 47 f8 c3") }
    }

    @Test
    fun headerRequiresTheOriginalElementsGuiOwner() {
        fun owner(argument: Int = 7, offset: Long = 64) = OriginalPointerOrigins.Load(
            OriginalPointerOrigins.Argument(argument), offset, 5)
        assertEquals(64L to 24L, GuiRegistryProof.header(OriginalPointerOrigins.Adjusted(owner(), 24), 512))
        for (value in listOf(owner(), OriginalPointerOrigins.Adjusted(owner(6), 24),
            OriginalPointerOrigins.Adjusted(owner(offset = 512), 24),
            OriginalPointerOrigins.Adjusted(owner(), 25))) assertFails {
            GuiRegistryProof.header(value, 512)
        }
    }

    @Test
    fun indexFollowsCopiesAndUnsignedLoadsWithoutAssumingRegistersOrOffsets() {
        fun analyze(code: String) = GuiRegistryProof.index(
            X64ControlFlow(X64Instructions(machineCode(code)).all()), 0x1000, 512)
        assertEquals(92L, analyze("48 8b 47 20 8b 48 5c 89 c8 f2 48 0f 2a c0 c3"))
        assertEquals(96L, analyze("48 8b 57 28 8b 42 60 90 89 c1 f2 48 0f 2a c1 c3"))
        assertEquals(92L, analyze("48 8b 47 20 8b 48 5c 89 c8 f2 48 0f 2a c0 b8 01 00 00 00 f2 48 0f 2a c8 c3"))
        assertFails { analyze("48 8b 46 20 8b 48 5c 89 c8 f2 48 0f 2a c0 c3") }
        assertFails { analyze("48 8b 47 20 48 8b 48 5c f2 48 0f 2a c1 c3") }
    }

    @Test
    fun typedRegistryRootRequiresTheSamePlayerGuiMember() {
        fun root(argument: Int = 7, member: Long = 64, root: Long = 40) = OriginalPointerOrigins.Load(
            OriginalPointerOrigins.Load(OriginalPointerOrigins.Argument(argument), member, 0), root, 8)
        assertEquals(32L, GuiRegistryProof.registryHead(root(), 64, 512))
        assertEquals(64L, GuiRegistryProof.registryHead(root(root = 72), 64, 512))
        for (value in listOf(root(argument = 6), root(member = 72), root(root = 0), root(root = 41))) {
            assertFails { GuiRegistryProof.registryHead(value, 64, 512) }
        }
        assertFails { GuiRegistryProof.registryHead(root(), 512, 512) }
    }
}
