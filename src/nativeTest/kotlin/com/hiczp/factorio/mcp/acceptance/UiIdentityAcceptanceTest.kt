@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp.acceptance

import com.hiczp.factorio.mcp.McpHttpClient
import com.hiczp.factorio.mcp.toolValue
import kotlinx.cinterop.toKString
import kotlinx.coroutines.*
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.serialization.json.*
import platform.posix.getenv
import kotlin.test.*

/** Observes the existing non-admin inventory fixture on the explicitly selected isolated local server. */
class UiIdentityAcceptanceTest {
    @Test
    fun nativeItemAndQualityValuesMatchAuthoritativeFixtureAndKeepFullCrc() = runBlocking {
        fun environment(name: String) = checkNotNull(getenv(name)).toKString()
        val serverLog = environment("FACTORIO_MCP_UI_SERVER_LOG")
        val clientLog = environment("FACTORIO_MCP_UI_CLIENT_LOG")
        val client = McpHttpClient(environment("FACTORIO_MCP_ACCEPTANCE_URL"))
        val pid = environment("FACTORIO_MCP_TEST_PID").toInt()
        fun read(path: String) = SystemFileSystem.source(Path(path)).buffered().use { it.readString() }
        fun record(kind: String) = read(serverLog).lineSequence().filter { "UI_ACTION_ACCEPTANCE " in it }
            .map { Json.parseToJsonElement(it.substringAfter("UI_ACTION_ACCEPTANCE ")).jsonObject }
            .single { it["kind"]?.jsonPrimitive?.content == kind }
        fun crc(path: String) = read(path).lineSequence().count { "UI_ACTION_FULL_CRC " in it }
        withTimeout(300_000) {
            var opened = false
            var key: String? = null
            suspend fun toggle() = client.tool("ui_action", buildJsonObject {
                put("action", "press_key")
                put("key", checkNotNull(key))
            }).toolValue()
            try {
                client.initialize()
                client.tool("attach", buildJsonObject { put("pid", pid) }).toolValue()
                val control = client.tool("input_bindings", buildJsonObject {
                    putJsonArray("ids") { add("open-character-gui") }
                }).toolValue().getValue("controls").jsonArray.single().jsonObject
                key = (control["effective_bindings"] ?: control.getValue("bindings")).jsonArray.map { it.jsonObject }
                    .first { it["type"]?.jsonPrimitive?.content == "Keyboard" && it.getValue("modifiers").jsonArray.isEmpty() }
                    .getValue("name").jsonPrimitive.content
                toggle()
                suspend fun slots() = client.tool("ui_read").toolValue().getValue("nodes").jsonArray.map { it.jsonObject }
                    .filter { it["type"]?.jsonPrimitive?.content == "InventoryGuiSlot" }
                    .mapNotNull { it["properties"] as? JsonObject }
                var properties = slots()
                while (properties.isEmpty()) {
                    delay(100)
                    properties = slots()
                }
                opened = true
                fun matching(name: String) = properties.filter {
                    it["prototype"]?.jsonObject?.get("name")?.jsonPrimitive?.content == name
                }
                val filters = matching("copper-ore").map { it.getValue("quality_condition").jsonObject }
                assertEquals(6, filters.size)
                assertEquals(setOf(">", "<", "=", "≥", "≤", "≠"), filters.map { it.getValue("comparison").jsonPrimitive.content }.toSet())
                assertTrue(filters.all { it.getValue("quality_name").jsonPrimitive.content == "rare" &&
                        it.getValue("quality_lookup").jsonPrimitive.content == "present" })
                assertEquals(1, filters.map { it.getValue("quality_index") }.toSet().size)
                assertFalse(record("condition_filters").getValue("admin").jsonPrimitive.boolean)
                assertEquals(6, record("condition_filters").getValue("data").jsonArray.size)
                for (property in matching("copper-ore")) {
                    val equal = property.getValue("quality_condition").jsonObject.getValue("comparison").jsonPrimitive.content == "="
                    assertEquals(if (equal) JsonPrimitive("rare") else JsonNull,
                        property.getValue("quality").jsonObject.getValue("name"))
                }
                val fixture = record("item_properties").getValue("data").jsonObject
                for ((name, member, field) in listOf(Triple("stone-furnace", "health", "health"),
                    Triple("repair-pack", "durability_left", "durability"), Triple("firearm-magazine", "magazine_left", "ammo"))) {
                    val items = matching(name).mapNotNull { (it["element"] as? JsonObject)?.get("item") as? JsonObject }
                    assertTrue(items.any { it[member]?.jsonPrimitive?.double == fixture.getValue(field).jsonPrimitive.double }, name)
                }
                assertEquals(JsonNull, matching("coal").single().getValue("element"))
                assertTrue(matching("iron-plate").all { it.getValue("element").jsonObject["item"] == JsonNull })
                println("Observed all six native comparison strings, quality identities and raw item fields")
                toggle()
                opened = false
                val serverCrc = crc(serverLog)
                val clientCrc = crc(clientLog)
                while (crc(serverLog) < serverCrc + 2 || crc(clientLog) < clientCrc + 2) delay(100)
                assertFalse(read(serverLog).contains("desynchron", true))
                assertFalse(read(clientLog).contains("desynchron", true))
            } finally {
                withContext(NonCancellable) {
                    try { if (opened) toggle() } finally { client.close() }
                }
            }
        }
    }
}
