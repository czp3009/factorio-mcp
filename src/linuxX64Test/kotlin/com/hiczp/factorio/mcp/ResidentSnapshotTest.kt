@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.linuxbridge.FmLinuxShared
import kotlinx.cinterop.*
import kotlin.test.*

class ResidentSnapshotTest {
    @Test
    fun copiesBoundedDropdownOptionsAndRejectsInvalidWireRanges() {
        SharedMapping.create(sizeOf<FmLinuxShared>()).use { mapping ->
            val snapshot = mapping.memory.reinterpret<FmLinuxShared>().pointed.snapshot
            snapshot.count = 1u
            val node = snapshot.nodes[0]
            node.parent = -1
            node.dropdownAvailable = 1u
            node.selectedIndex = -1
            node.optionsAvailable = 1u
            node.optionCount = 2u
            node.optionTotal = 5u
            snapshot.optionCount = 2u
            snapshot.options[1].size = 3u
            snapshot.options[1].text[0] = 'x'.code.toByte()
            snapshot.options[1].text[1] = 0
            snapshot.options[1].text[2] = 'y'.code.toByte()
            snapshot.options[1].truncated = 1u
            val properties = assertNotNull(snapshot.readWidgets().single().properties)
            assertEquals(-1, properties.selectedIndex)
            assertEquals(WidgetOptions(listOf(WidgetOption(""), WidgetOption("x\u0000y", true)), 5), properties.options)
            snapshot.options[1].text[0] = 0
            assertEquals("x\u0000y", properties.options?.values?.get(1)?.text)
            node.optionFirst = 1u
            assertFails { snapshot.readWidgets() }
            node.optionFirst = 0u
            snapshot.options[1].size = 512u
            assertFails { snapshot.readWidgets() }
            snapshot.options[1].size = 0u
            node.optionTotal = 1u
            assertFails { snapshot.readWidgets() }
            node.optionsAvailable = 0u
            assertNotNull(snapshot.readWidgets().single().properties?.optionsUnavailableReason)
            snapshot.optionCount = 1025u
            assertFails { snapshot.readWidgets() }
        }
    }

    @Test
    fun copiesNativeTextFlagsAndCoordinatesIntoPortablePostorderNodes() {
        SharedMapping.create(sizeOf<FmLinuxShared>()).use { mapping ->
            val snapshot = mapping.memory.reinterpret<FmLinuxShared>().pointed.snapshot
            snapshot.count = 2u
            val root = snapshot.nodes[0]
            root.parent = -1
            root.textAvailable = 1u
            val child = snapshot.nodes[1]
            child.parent = 0
            child.depth = 1u
            child.type[0] = 'T'.code.toByte()
            child.type[1] = 0
            child.enabled = 1u
            child.destroying = 1u
            child.selected = 1u
            child.visible = 1u
            child.hiddenBySearch = 1u
            child.renderEnabled = 1u
            child.bounds.x = -11
            child.bounds.y = -23
            child.bounds.width = 17
            child.bounds.height = 19
            child.textAvailable = 1u
            child.textSize = 3u
            child.textTotal = 4u
            child.textTruncated = 1u
            child.text[0] = 'x'.code.toByte()
            child.text[1] = 0
            child.text[2] = 'y'.code.toByte()
            child.checkAvailable = 1u
            child.checkState = -7
            child.sliderAvailable = 1u
            child.value = Double.NaN
            child.minimum = Double.NEGATIVE_INFINITY
            child.maximum = Double.POSITIVE_INFINITY
            child.step = -0.0
            child.progressAvailable = 1u
            child.progressValue = Double.POSITIVE_INFINITY
            child.progressDirection = 255u
            child.progressHasText = 1u
            val nodes = snapshot.readWidgets()
            child.value = 123.5
            child.progressValue = 0.25
            child.bounds.x = 0
            child.text[0] = 0
            assertEquals(listOf(1, 0), nodes.map { it.depth })
            val copied = nodes[0]
            assertEquals("T", copied.type)
            assertEquals("unknown_-7", copied.properties?.checkState)
            val slider = assertNotNull(copied.properties?.slider)
            assertEquals(WidgetProgress(Double.POSITIVE_INFINITY, "unknown_255", true), copied.properties?.progress)
            assertEquals(0.25, snapshot.readWidgets()[0].properties?.progress?.value)
            assertTrue(slider.value.isNaN())
            assertEquals(Double.NEGATIVE_INFINITY, slider.minimum)
            assertEquals(Double.POSITIVE_INFINITY, slider.maximum)
            assertEquals((-0.0).toBits(), slider.step.toBits())
            assertEquals(123.5, snapshot.readWidgets()[0].properties?.slider?.value)
            assertNull(nodes[1].properties)
            child.checkState = 2
            assertEquals("unknown_2", snapshot.readWidgets()[0].properties?.checkState)
            assertEquals("unknown_-7", copied.properties?.checkState)
            assertEquals("x\u0000y", copied.text)
            assertEquals(listOf(-11, -23, 17, 19), listOf(copied.x, copied.y, copied.width, copied.height))
            assertTrue(copied.enabled)
            assertTrue(copied.selected)
            assertFalse(nodes[1].selected)
            assertEquals(true, copied.flaggedForDestruction)
            assertEquals(true, copied.visible)
            assertEquals(true, copied.hiddenBySearch)
            assertEquals(true, copied.renderEnabled)
            assertEquals(false, nodes[1].renderEnabled)
            assertEquals(false, nodes[1].visible)
            assertTrue(copied.textTruncated)
            assertEquals("", nodes[1].text)
            child.textAvailable = 0u
            assertNull(snapshot.readWidgets()[0].text)
            child.parent = 1
            assertFails { snapshot.readWidgets() }
            child.parent = 0
            child.textSize = 1025u
            child.textTotal = 1025u
            assertFails { snapshot.readWidgets() }
        }
    }
}
