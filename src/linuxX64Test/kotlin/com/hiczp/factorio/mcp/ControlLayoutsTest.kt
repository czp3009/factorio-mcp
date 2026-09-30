@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.linuxbridge.*
import kotlinx.cinterop.*
import platform.posix.memset
import kotlin.test.*

class ControlLayoutsTest {
    private fun metadata() = ControlLayouts(
        256, ElfImage.Symbol("fixture_registry", 0x1000, 24, 1, 1), 0x1100, 0x1200,
        NamedPointerRegistry(0, 8, 24, 32), ControlSlots(listOf(64, 96), 0, 4, 19),
        ControlModifiers(8, 8, 16, 32), 40, ControlPrototype(48, 128, 0x2000, 64, 65, 66),
        NativeAccessor(56, 1, 4UL, 2), ControlUsage(60, 23, 9, 12), 17, 21,
        mapOf(45 to "left", 47 to "right"), ControlWheelKinds(22, mapOf(37 to "up", 38 to "down")),
    )

    private fun identity(output: CPointer<ByteVar>, value: String) {
        value.encodeToByteArray().forEachIndexed { index, byte -> output[index] = byte }
        output[value.encodeToByteArray().size] = 0
    }

    @Test
    fun copiesOwnAndEffectiveSlotsWithoutInventingUnknownValues() = memScoped<Unit> {
        val source = alloc<FmLinuxControlsSnapshot>()
        memset(source.ptr, 0, sizeOf<FmLinuxControlsSnapshot>().toULong())
        source.registryCount = 1u
        source.count = 1u
        val row = source.controls[0]
        identity(row.id, "fixture-custom")
        identity(row.linked, "fixture-owner")
        identity(row.owner, "fixture-owner")
        row.custom = 1u
        row.spectating = 1u
        row.gui = 1u
        row.usage = -77
        row.bindings[0].type = 17u
        row.bindings[1].type = 99u
        row.bindings[1].code = UInt.MAX_VALUE
        row.bindings[1].modifiers = 128u
        row.effective[0].type = 19u
        row.effective[0].code = 4u
        row.effective[0].modifiers = 8u or 16u or 128u
        row.effective[1].type = 21u
        row.effective[1].code = 47u
        val snapshot = metadata().read(source, 123)
        val control = snapshot.controls.single()
        assertEquals(123L, snapshot.frame)
        assertEquals("fixture-owner", control.bindingOwner)
        assertEquals(false, control.enabled)
        assertEquals(true, control.spectating)
        assertEquals(-77, control.nativeUsage)
        assertEquals("Nothing", control.bindings[0].type)
        assertEquals("Unknown(99)", control.bindings[1].type)
        assertNull(control.bindings[1].name)
        assertEquals(-1, control.bindings[1].code)
        assertEquals(128, control.bindings[1].nativeModifierBits)
        assertEquals("A", control.effective[0].name)
        assertEquals(listOf("control", "shift"), control.effective[0].modifiers)
        assertEquals(152, control.effective[0].nativeModifierBits)
        assertEquals("right", control.effective[1].name)
        row.custom = 0u
        row.effective[0].type = 22u
        row.effective[0].code = 38u
        val refreshed = metadata().read(source, 124).controls.single()
        assertNull(refreshed.enabled)
        assertEquals("down", refreshed.effective[0].name)
        assertEquals("A", control.effective[0].name)
        row.effective[0].code = 999u
        assertNull(metadata().read(source, 125).controls.single().effective[0].name)
    }

    @Test
    fun rejectsMalformedWireBoundsFlagsAndNames() = memScoped<Unit> {
        val source = alloc<FmLinuxControlsSnapshot>()
        memset(source.ptr, 0, sizeOf<FmLinuxControlsSnapshot>().toULong())
        source.registryCount = 1u
        source.count = 1u
        val row = source.controls[0]
        identity(row.id, "fixture")
        identity(row.owner, "fixture")
        val metadata = metadata()
        metadata.read(source, 0)
        source.count = (FM_LINUX_MAX_CONTROLS + 1).toUInt()
        assertFails { metadata.read(source, 0) }
        source.count = 1u
        source.registryCount = 2u
        assertFails { metadata.read(source, 0) }
        source.truncated = 1u
        assertTrue(metadata.read(source, 0).truncated)
        row.gui = 2u
        assertFails { metadata.read(source, 0) }
        row.gui = 0u
        row.bindings[0].modifiers = 256u
        assertFails { metadata.read(source, 0) }
        row.bindings[0].modifiers = 0u
        for (index in 0 until FM_LINUX_CONTROL_ID_BYTES) row.id[index] = 65
        assertFails { metadata.read(source, 0) }
        row.id[0] = 0
        assertFails { metadata.read(source, 0) }
        row.id[0] = 0xff.toByte()
        row.id[1] = 0
        assertFails { metadata.read(source, 0) }
    }
}
