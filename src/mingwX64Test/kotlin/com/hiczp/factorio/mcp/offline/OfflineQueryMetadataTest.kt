@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp.offline

import com.hiczp.factorio.mcp.ChatLayouts
import com.hiczp.factorio.mcp.DebugTypes
import com.hiczp.factorio.mcp.InputTransferLayouts
import com.hiczp.factorio.mcp.RuntimeApi
import com.hiczp.factorio.mcp.nativebridge.*
import kotlinx.cinterop.*
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import platform.posix.getenv
import platform.windows.GetCurrentProcess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OfflineQueryMetadataTest {
    @Test
    fun chatLayoutResolvesFromPdbWithoutOpeningAGameProcess() = memScoped {
        val path =
            getenv("FACTORIO_MCP_TEST_PDB")?.toKString()?.takeIf { it.isNotBlank() }
                ?: return@memScoped
        println("factorio-mcp: offline chat PDB layout check enabled")
        val process = checkNotNull(GetCurrentProcess())
        check(SymInitializeW(process, null, 0) != 0)
        try {
            val base =
                SymLoadModuleExW(process, null, path.wcstr.ptr, null, 0x180000000uL, 0u, null, 0u)
            check(base != 0uL)
            val layout = alloc<ChatLayout>()
            ChatLayouts(DebugTypes(process, base)).write(layout)
            assertEquals(1u, layout.supported)
            assertEquals(1u, layout.sendSupported)
            assertTrue(layout.actionBuffer + layout.stringSize <= layout.actionSize)
            assertTrue(layout.lists[0] != layout.lists[1])
            val transfer = alloc<InputTransferLayout>()
            InputTransferLayouts(DebugTypes(process, base)).write(transfer)
            assertEquals(1u, transfer.supported)
            assertTrue(transfer.blockSize > 0u)
            assertTrue(transfer.segmentSize > transfer.totalSegments)
        } finally {
            SymCleanup(process)
        }
    }

    @Test
    fun installedApiCatalogFitsTheWireAndExcludesMutationMethods() {
        val path =
            getenv("FACTORIO_MCP_TEST_RUNTIME_API")?.toKString()?.takeIf { it.isNotBlank() }
                ?: return
        println("factorio-mcp: offline runtime API catalog check enabled")
        val document = SystemFileSystem.source(Path(path)).buffered().use { it.readString() }
        val api = RuntimeApi(Json.parseToJsonElement(document).jsonObject)
        assertTrue(api.catalog.toString().encodeToByteArray().size < FM_MAX_QUERY_ARGUMENTS)
        val player = api.catalog.getValue("LuaPlayer").jsonObject
        assertTrue(
            player.getValue("attributes").jsonArray.any {
                it.jsonPrimitive.content == "physical_position"
            }
        )
        assertFalse(
            api.catalog.values.any {
                "can_insert" in
                        it.jsonObject.getValue("methods").jsonArray.map { value ->
                            value.jsonPrimitive.content
                        }
            }
        )
    }
}
