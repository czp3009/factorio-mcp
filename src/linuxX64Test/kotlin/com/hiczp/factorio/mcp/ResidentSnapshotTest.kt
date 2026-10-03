@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.linuxbridge.FmLinuxShared
import kotlin.test.*
import kotlinx.cinterop.*

class ResidentSnapshotTest {
    @Test
    fun copiesRawQualityConditionsAndKeepsMissingRegistryValuesDistinct() {
        SharedMapping.create(sizeOf<FmLinuxShared>()).use { mapping ->
            val snapshot = mapping.memory.reinterpret<FmLinuxShared>().pointed.snapshot
            snapshot.count = 1u
            val node = snapshot.nodes[0]
            node.parent = -1
            val condition = node.quality
            condition.available = 1u
            condition.quality = 0u
            condition.comparison = 1u
            condition.lookup = 1u
            condition.nameSize = 3u
            "raw".encodeToByteArray().forEachIndexed { index, byte -> condition.name[index] = byte }
            fun copied() =
                assertNotNull(
                    snapshot.readWidgets(listOf("=", "≥")).single().properties?.qualityCondition
                )
            val value = copied()
            assertEquals(WidgetQualityCondition(0, 1, "≥", "raw", false, "present"), value)
            condition.name[0] = 'X'.code.toByte()
            assertEquals("raw", value.qualityName)
            condition.lookup = 0u
            condition.nameSize = 0u
            assertEquals("null", copied().qualityLookup)
            assertNull(copied().qualityName)
            condition.comparison = 255u
            condition.lookup = 2u
            assertEquals("unknown_255", copied().comparison)
            assertEquals("index_out_of_range", copied().qualityLookup)
            condition.nameSize = 1u
            assertFails { copied() }
            condition.nameSize = 256u
            condition.lookup = 1u
            assertFails { copied() }
        }
    }

    @Test
    fun copiesRawElementsAndPreservesAbsentMaterializedItems() {
        SharedMapping.create(sizeOf<FmLinuxShared>()).use { mapping ->
            val snapshot = mapping.memory.reinterpret<FmLinuxShared>().pointed.snapshot
            snapshot.count = 1u
            val node = snapshot.nodes[0]
            node.parent = -1
            val element = node.element
            element.available = 1u
            fun copied() = assertNotNull(snapshot.readWidgets().single().properties?.element)
            assertEquals(WidgetElement(false, null, null), copied())
            element.flags = 3u
            element.count = UInt.MAX_VALUE
            assertEquals(WidgetElement(true, UInt.MAX_VALUE.toLong(), null), copied())
            element.flags = 63u
            element.typeSize = 4u
            "Tool"
                .forEachIndexed { index, character ->
                    element.type[index] = character.code.toByte()
                }
            element.health = Float.NaN
            element.durability = -0.0
            element.magazine = Float.POSITIVE_INFINITY
            val saved = copied()
            val item = assertNotNull(saved.item)
            assertEquals("Tool", item.nativeType)
            assertTrue(item.typeTruncated && item.health.isNaN())
            assertEquals((-0.0).toBits(), assertNotNull(item.durabilityLeft).toBits())
            assertEquals(Double.POSITIVE_INFINITY, item.magazineLeft)
            element.type[0] = 'X'.code.toByte()
            assertEquals("Tool", item.nativeType)
            element.typeSize = 256u
            assertFails { copied() }
            element.typeSize = 0u
            element.flags = 8u
            assertFails { copied() }
            element.flags = 4u
            assertFails { copied() }
            element.flags = 64u
            assertFails { copied() }
        }
    }

    @Test
    fun copiesPrototypeIdentityWithoutReplacingRawNamesOrMissingValues() {
        SharedMapping.create(sizeOf<FmLinuxShared>()).use { mapping ->
            val snapshot = mapping.memory.reinterpret<FmLinuxShared>().pointed.snapshot
            snapshot.count = 1u
            val node = snapshot.nodes[0]
            node.parent = -1
            node.identity.available = 1u
            val prototype = node.identity.prototype
            prototype.flags = 3u
            prototype.nameSize = 3u
            prototype.name[0] = 'x'.code.toByte()
            prototype.name[1] = 0
            prototype.name[2] = 'y'.code.toByte()
            prototype.typeSize = 1u
            prototype.type[0] = 'T'.code.toByte()
            val properties = assertNotNull(snapshot.readWidgets().single().properties)
            assertEquals(WidgetPrototype("x\u0000y", "T", true, false), properties.prototype)
            assertEquals(WidgetPrototype(null, null), properties.quality)
            prototype.name[0] = 0
            assertEquals("x\u0000y", properties.prototype?.name)
            prototype.flags = 8u
            assertFails { snapshot.readWidgets() }
            prototype.flags = 0u
            assertFails { snapshot.readWidgets() }
            prototype.nameSize = 0u
            prototype.typeSize = 256u
            assertFails { snapshot.readWidgets() }
        }
    }

    @Test
    fun copiesOpaqueIconReferencesWithoutImageData() {
        SharedMapping.create(sizeOf<FmLinuxShared>()).use { mapping ->
            val snapshot = mapping.memory.reinterpret<FmLinuxShared>().pointed.snapshot
            snapshot.count = 1u
            val node = snapshot.nodes[0]
            node.parent = -1
            node.iconsAvailable = 1u
            node.iconNormal = 0
            node.iconHovered = -1
            node.iconDisabled = -2
            val copied = snapshot.readWidgets().single().properties?.icons
            assertEquals(
                WidgetIcons(WidgetIcon(0), null, WidgetIcon(null, truncated = true)),
                copied,
            )
            node.iconNormal = 511
            assertEquals(WidgetIcon(511), snapshot.readWidgets().single().properties?.icons?.normal)
            assertEquals(WidgetIcon(0), copied?.normal)
            node.iconNormal = 512
            assertFails { snapshot.readWidgets() }
            node.iconNormal = -3
            assertFails { snapshot.readWidgets() }
        }
    }

    @Test
    fun preservesSuppressedUnknownInfiniteAndNonfiniteNumbers() {
        SharedMapping.create(sizeOf<FmLinuxShared>()).use { mapping ->
            val snapshot = mapping.memory.reinterpret<FmLinuxShared>().pointed.snapshot
            snapshot.count = 1u
            val node = snapshot.nodes[0]
            node.parent = -1
            node.numberAvailable = 1u
            fun number() = assertNotNull(snapshot.readWidgets().single().properties?.number)
            assertEquals(WidgetNumber(false, null, null, null, null), number())
            node.numberFlags = 7u
            assertEquals(WidgetNumber(true, null, true, true, false), number())
            node.numberFlags = 9u
            assertEquals(WidgetNumber(true, null, false, false, true), number())
            node.numberFlags = 17u
            node.numberValue = Double.NEGATIVE_INFINITY
            assertEquals(Double.NEGATIVE_INFINITY, number().value)
            node.numberValue = Double.NaN
            assertTrue(assertNotNull(number().value).isNaN())
            node.numberValue = -0.0
            val copied = number()
            node.numberValue = 7.0
            assertEquals((-0.0).toBits(), assertNotNull(copied.value).toBits())
        }
    }

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
            assertEquals(
                WidgetOptions(listOf(WidgetOption(""), WidgetOption("x\u0000y", true)), 5),
                properties.options,
            )
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
            child.switchAvailable = 1u
            child.switchState = 255u
            child.switchAllowNone = 1u
            val nodes = snapshot.readWidgets()
            child.value = 123.5
            child.progressValue = 0.25
            child.bounds.x = 0
            child.text[0] = 0
            assertEquals(listOf(1, 0), nodes.map { it.depth })
            val copied = nodes[0]
            assertEquals("T", copied.type)
            assertEquals(-7, copied.properties?.checkState)
            assertEquals(WidgetSwitch(255, "unknown_255", true), copied.properties?.switch)
            child.switchState = 0u
            child.switchAllowNone = 0u
            assertEquals(
                WidgetSwitch(0, "unknown_0", false),
                snapshot.readWidgets()[0].properties?.switch,
            )
            assertEquals(
                WidgetSwitch(0, "native_left", false),
                snapshot.readWidgets(switchNames = listOf("native_left"))[0].properties?.switch,
            )
            assertEquals(WidgetSwitch(255, "unknown_255", true), copied.properties?.switch)
            val slider = assertNotNull(copied.properties?.slider)
            assertEquals(
                WidgetProgress(Double.POSITIVE_INFINITY, "unknown_255", true),
                copied.properties?.progress,
            )
            assertEquals(
                "native_direction",
                snapshot
                    .readWidgets(progressNames = mapOf(255 to "native_direction"))[0]
                    .properties
                    ?.progress
                    ?.direction,
            )
            assertEquals("unknown_255", copied.properties?.progress?.direction)
            assertEquals(0.25, snapshot.readWidgets()[0].properties?.progress?.value)
            assertTrue(slider.value.isNaN())
            assertEquals(Double.NEGATIVE_INFINITY, slider.minimum)
            assertEquals(Double.POSITIVE_INFINITY, slider.maximum)
            assertEquals((-0.0).toBits(), slider.step.toBits())
            assertEquals(123.5, snapshot.readWidgets()[0].properties?.slider?.value)
            assertNull(nodes[1].properties)
            child.checkState = 2
            assertEquals(2, snapshot.readWidgets()[0].properties?.checkState)
            assertEquals(-7, copied.properties?.checkState)
            assertEquals("x\u0000y", copied.text)
            assertEquals(
                listOf(-11, -23, 17, 19),
                listOf(copied.x, copied.y, copied.width, copied.height),
            )
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
