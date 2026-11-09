@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp.offline

import com.hiczp.factorio.mcp.*
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.cinterop.toKString
import platform.posix.getenv

class UiResourceMetadataTest {
    @Test
    fun resolvesGraphAndStoredWidgetData() {
        val path = checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")).toKString()
        MappedBinary(path).use { file ->
            val image = ElfImage(file.view)
            val graph = WidgetGraphLayout.resolve(image)
            assertTrue(graph.fields.values.stride == 4L && graph.fields.sampleCount + 4 <= graph.size)
            println("Stored graph: size=${graph.size}, fields=${graph.fields}")
            val stored = StoredWidgetLayouts.resolve(image, PrototypeNameLayout.resolve(image))
            assertTrue(stored.state != stored.shownState && stored.prototype + 8 <= stored.cardSize)
            println("Retained widget identities: connection ID=${stored.connectionId}, registry=${stored.first}/${stored.last}, card prototype=${stored.prototype}, state=${stored.state}/${stored.shownState}, sidebar=${stored.sidebar}")
        }
    }

    @Test
    fun resolvesScriptWidgetIdentityFromTheSelectedExecutable() {
        val path = checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")).toKString()
        MappedBinary(path).use { file ->
            val image = ElfImage(file.view)
            val world = WorldQueryMetadata.resolve(image)
            val registry = GuiRegistryLayout.resolve(image, world.playerSize)
            assertTrue(registry.playerGui + 8 <= registry.playerSize)
            assertTrue(registry.head + 32 <= registry.guiExtent)
            assertTrue(registry.index + 4 <= registry.elementSize)
            println("Script GUI registry: $registry")
        }
    }

    @Test
    fun resolvesStoredNamesAndTooltips() {
        val path = checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")).toKString()
        MappedBinary(path).use { file ->
            val image = ElfImage(file.view)
            val names = WidgetIconNamesLayout.resolve(image)
            val images = WidgetImageLayout.resolve(image)
            assertTrue(images.image + 8 <= images.widgetSize && images.sprite + 8 <= images.staticSize)
            println("Retained image: widget member=${images.image}, static sprite=${images.sprite}")
            println(
                "Sprite-name registry member=${names.registry}, string size=${names.string.size}"
            )
            val tips =
                WidgetTooltipLayout.resolve(image, SysVObjectSize.resolve(image, "4agui6Widget"))
            println(
                "Stored tooltip creator=${tips.creator}, title=${tips.title}, text=${tips.text}"
            )
            assertTrue(tips.title != tips.text)
        }
    }
}
