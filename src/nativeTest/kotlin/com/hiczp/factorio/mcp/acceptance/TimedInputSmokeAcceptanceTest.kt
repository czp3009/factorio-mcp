@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp.acceptance

import com.hiczp.factorio.mcp.McpHttpClient
import com.hiczp.factorio.mcp.toolValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.cinterop.toKString
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import platform.posix.getenv

/** A disposable single-player world with a stationary character and clear ground to its right. */
class TimedInputSmokeAcceptanceTest {
    @Test
    fun routesConfiguredMovementAndReleasesBeforeCompletion() = runBlocking {
        fun environment(name: String) =
            checkNotNull(getenv(name)) { "Select $name explicitly" }.toKString()
        val endpoint = environment("FACTORIO_MCP_ACCEPTANCE_URL")
        val pid = environment("FACTORIO_MCP_TEST_PID").toInt()
        withTimeout(600_000) {
            val client = McpHttpClient(endpoint)
            var attached = false
            try {
                client.initialize()
                client.tool("attach", buildJsonObject { put("pid", pid) }).toolValue()
                attached = true
                val controls =
                    client
                        .tool(
                            "input_bindings",
                            buildJsonObject { putJsonArray("ids") { add("move-right") } },
                        )
                        .toolValue()
                        .getValue("controls")
                        .jsonArray
                val control = controls.single().jsonObject
                val binding =
                    (control["effective_bindings"] ?: control.getValue("bindings"))
                        .jsonArray
                        .map { it.jsonObject }
                        .first {
                            it.getValue("type").jsonPrimitive.content == "Keyboard" &&
                                it.getValue("modifiers").jsonArray.isEmpty()
                        }
                val key = binding.getValue("name").jsonPrimitive.content
                suspend fun position(): JsonObject =
                    client
                        .tool(
                            "world_query",
                            buildJsonObject { putJsonObject("selection") { put("kind", "player") } },
                        )
                        .toolValue()
                        .getValue("player")
                        .jsonObject
                        .getValue("position")
                        .jsonObject

                fun request(ticks: Int, held: Boolean) = buildJsonObject {
                    putJsonArray("timeline") {
                        addJsonObject {
                            put("device", if (held) "keyboard" else "mouse")
                            if (held) put("key", key)
                            else
                                putJsonObject("position") {
                                    put("space", "viewport")
                                    put("x", 0)
                                    put("y", 0)
                                }
                            put("tick", "0-${ticks - 1}")
                        }
                    }
                }
                val before = position()
                val result = client.tool("input", request(3, true)).toolValue()
                assertEquals("completed", result.getValue("status").jsonPrimitive.content)
                assertEquals(3, result.getValue("evaluated_ticks").jsonPrimitive.int)
                assertEquals(1, result.getValue("completed_entries").jsonPrimitive.int)
                val after =
                    withTimeout(30_000) {
                        var value = position()
                        while (value == before) {
                            delay(20)
                            value = position()
                        }
                        value
                    }
                assertTrue(
                    after.getValue("x").jsonPrimitive.double >
                        before.getValue("x").jsonPrimitive.double
                )
                // In this dedicated single-player world, advancing additional native evaluations
                // with no
                // controls must leave the released character stationary. This observes effects only
                // in a test.
                client.tool("input", request(4, false)).toolValue()
                assertEquals(
                    after,
                    position(),
                    "Movement continued after the task released its key",
                )
                println(
                    "factorio-mcp timed input: configured key, three evaluations, movement and release observed"
                )
            } finally {
                withContext(NonCancellable) {
                    try {
                        if (attached) {
                            client
                                .tool(
                                    "input",
                                    buildJsonObject {
                                        put("stop_previous", true)
                                        putJsonArray("timeline") {}
                                    },
                                )
                                .toolValue()
                            client.tool("detach").toolValue()
                        }
                    } finally {
                        client.close()
                    }
                }
            }
        }
    }
}
