@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp.offline

import com.hiczp.factorio.mcp.*
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.cinterop.toKString
import platform.posix.getenv

class UiResourceMetadataTest {
    @Test
    fun resolvesStoredNamesAndTooltips() {
        val path = checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")).toKString()
        MappedBinary(path).use { file ->
            val image = ElfImage(file.view)
            val names = WidgetIconNamesLayout.resolve(image)
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
