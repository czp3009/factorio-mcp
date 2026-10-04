@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp.acceptance

import com.hiczp.factorio.mcp.McpHttpClient
import com.hiczp.factorio.mcp.toolValue
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.cinterop.toKString
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.serialization.json.*
import platform.posix.getenv

/** Uses only the explicitly configured local fixture, with the ordinary zoom bindings. */
class WheelInputAcceptanceTest {
    @Test
    fun wheelIsOneImpulseForWorldZoom() = runBlocking {
        fun environment(name: String) = getenv(name)?.toKString()?.takeIf { it.isNotBlank() }
        val serverLog = environment("FACTORIO_MCP_UI_SERVER_LOG") ?: return@runBlocking
        val clientLog = checkNotNull(environment("FACTORIO_MCP_UI_CLIENT_LOG"))
        val client = McpHttpClient(checkNotNull(environment("FACTORIO_MCP_ACCEPTANCE_URL")))
        val pid = checkNotNull(environment("FACTORIO_MCP_TEST_PID")).toInt()
        fun read(path: String) =
            SystemFileSystem.source(Path(path)).buffered().use { it.readString() }

        fun crc(path: String) = read(path).lineSequence().count { "UI_ACTION_FULL_CRC " in it }
        suspend fun zoom() =
            client
                .tool(
                    "world_query",
                    buildJsonObject { putJsonObject("selection") { put("kind", "player") } },
                )
                .toolValue()
                .getValue("player")
                .jsonObject
                .getValue("zoom")
                .jsonPrimitive
                .double

        suspend fun awaitZoom(description: String, predicate: (Double) -> Boolean): Double =
            withTimeout(10_000) {
                var value = zoom()
                while (!predicate(value)) {
                    delay(20)
                    value = zoom()
                }
                assertTrue(predicate(value), description)
                value
            }

        suspend fun wheel(direction: String, ticks: Int, x: Int, y: Int) {
            val result =
                client
                    .tool(
                        "input",
                        buildJsonObject {
                            putJsonArray("timeline") {
                                addJsonObject {
                                    put("device", "mouse")
                                    putJsonObject("position") {
                                        put("space", "viewport")
                                        put("x", x)
                                        put("y", y)
                                    }
                                    put("tick", "0-${ticks - 1}")
                                }
                                addJsonObject {
                                    put("device", "mouse")
                                    put("wheel", direction)
                                    put("tick", "0-${ticks - 1}")
                                }
                            }
                        },
                    )
                    .toolValue()
            assertEquals("completed", result.getValue("status").jsonPrimitive.content)
            assertEquals(ticks, result.getValue("evaluated_ticks").jsonPrimitive.int)
        }
        withTimeout(90_000) {
            try {
                client.initialize()
                client.tool("attach", buildJsonObject { put("pid", pid) }).toolValue()
                val view = client.tool("world_overview").toolValue().getValue("viewport").jsonObject
                val x = view.getValue("width").jsonPrimitive.int * 3 / 4
                val y = view.getValue("height").jsonPrimitive.int / 2
                val before = zoom()
                wheel("up", 1, x, y)
                // Task completion acknowledges dispatch, not the later authoritative effect.
                val one = awaitZoom("Wheel up must increase zoom") { it > before }
                wheel("down", 1, x, y)
                awaitZoom("Wheel down must restore zoom") { abs(it - before) < 0.0001 }
                wheel("up", 30, x, y)
                awaitZoom("Wheel up must reach its prior zoom") { abs(it - one) < 0.0001 }
                val settledClientCrc = crc(clientLog)
                val settledServerCrc = crc(serverLog)
                while (
                    crc(clientLog) <= settledClientCrc || crc(serverLog) <= settledServerCrc
                ) delay(100)
                assertTrue(
                    abs(zoom() - one) < 0.0001,
                    "Holding ticks must not repeat a wheel impulse",
                )
                wheel("down", 1, x, y)
                awaitZoom("Wheel down must restore zoom") { abs(it - before) < 0.0001 }

                val clientCrc = crc(clientLog)
                val serverCrc = crc(serverLog)
                while (crc(clientLog) < clientCrc + 2 || crc(serverLog) < serverCrc + 2) delay(100)
                assertFalse(read(clientLog).contains("desynchron", ignoreCase = true))
                assertFalse(read(serverLog).contains("desynchron", ignoreCase = true))
            } finally {
                client.close()
            }
        }
    }
}
