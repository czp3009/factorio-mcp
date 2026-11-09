@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp.acceptance

import com.hiczp.factorio.mcp.worldQuery

import com.hiczp.factorio.mcp.McpHttpClient
import com.hiczp.factorio.mcp.toolValue
import kotlinx.cinterop.toKString
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import platform.posix.getenv
import kotlin.test.*

/** Read-only checks against an explicitly selected loaded client and endpoint. */
class ReadLimitAcceptanceTest {
    @Test
    fun finiteReadsAreCompleteAndUnsignedEntityLookupReachesTheGame() = runBlocking {
        fun environment(name: String) = checkNotNull(getenv(name)) { "Select $name explicitly" }.toKString()
        val client = McpHttpClient(environment("FACTORIO_MCP_ACCEPTANCE_URL"))
        val pid = environment("FACTORIO_MCP_TEST_PID").toInt()
        var attached = false
        withTimeout(240_000) {
            try {
                client.initialize()
                client.tool("attach", buildJsonObject { put("pid", pid) }).toolValue()
                attached = true
                suspend fun query(arguments: String) = client.worldQuery(Json.parseToJsonElement(arguments).jsonObject).toolValue()
                for (kind in listOf("players", "inventories")) {
                    val complete = query("""{"selection":{"kind":"$kind"}}""")
                    assertFalse(complete.getValue("has_more").jsonPrimitive.boolean)
                    assertFalse(complete.getValue("truncated").jsonPrimitive.boolean)
                    assertEquals(complete.getValue("candidates_observed").jsonPrimitive.int, complete.getValue("objects").jsonArray.size)
                }
                val members = query("""{"selection":{"kind":"inspect","target":{"kind":"game"}},"mode":"members"}""")
                val catalog = members.getValue("objects").jsonArray.single().jsonObject
                assertEquals(catalog.getValue("total").jsonPrimitive.int, catalog.getValue("members").jsonArray.size)
                assertFalse(members.getValue("has_more").jsonPrimitive.boolean)
                val first = query("""{"selection":{"kind":"inspect","target":{"kind":"game"}},"mode":"members","limit":1}""")
                assertTrue(first.getValue("has_more").jsonPrimitive.boolean)
                assertEquals(1, first.getValue("next_offset").jsonPrimitive.int)
                val last = query("""{"selection":{"kind":"inspect","target":{"kind":"game"}},"mode":"members","offset":1}""")
                assertEquals(catalog.getValue("total").jsonPrimitive.int - 1, last.getValue("objects").jsonArray.single().jsonObject.getValue("members").jsonArray.size)
                assertFalse(last.getValue("has_more").jsonPrimitive.boolean)
                for (identifier in listOf("4294967296", "18446744073709549568")) {
                    val result = client.worldQuery(Json.parseToJsonElement("""{"selection":{"kind":"entities","unit_number":$identifier}}""").jsonObject)
                    if (result["isError"]?.jsonPrimitive?.boolean == true) {
                        val error = result.getValue("structuredContent").jsonObject.getValue("error").jsonPrimitive.content
                        assertTrue(error.contains("Unit number is absent or not indexed by the game"), error)
                    } else {
                        assertTrue(result.toolValue().getValue("objects").jsonArray.size <= 1)
                    }
                }
            } finally {
                withContext(NonCancellable) {
                    try {
                        if (attached) client.tool("detach").toolValue()
                    } finally {
                        client.close()
                    }
                }
            }
        }
    }
}
