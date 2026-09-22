package com.hiczp.factorio.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path

private const val USAGE = """Usage:
  factorio-mcp [--no-stdio | --no-http] [--http-port PORT]
                                        Start MCP (stdio + local HTTP by default)
  factorio-mcp status PID [--timeout-ms N]
  factorio-mcp run PID [--lua FILE | --code LUA] [--ticks N] [--timeout-ms N]
  factorio-mcp detach PID [--timeout-ms N]

HTTP listens on http://127.0.0.1:3000/mcp by default; port 0 selects a free port.
The listening URL is reported on stderr. HTTP remains available after stdin EOF.
Use --no-http for an agent-owned stdio process that exits at stdin EOF.
At least one transport must remain enabled. SIGINT/SIGTERM closes both.

Attach to an already running Factorio process using its developer debug information.
run executes Lua once per official on_tick (default: once), prints the last
JSON-compatible return value, restores the original handler and exits.
Default Lua: return {tick=game.tick, players=#game.connected_players}
Use `local event = ...` to read the on_tick event. SIGINT/SIGTERM requests cleanup.
The timeout bounds waiting for ticks; it cannot preempt arbitrary Lua code.
No JVM, Frida, preloading, RCON or game launch mode is used.
"""

fun main(args: Array<String>) {
    var exitCode = 0
    try {
        if (args.firstOrNull() in listOf("--help", "-h")) {
            println(USAGE); return
        }
        if (args.isEmpty() || args[0].startsWith("--")) {
            serveMcp(McpOptions.parse(args)); return
        }
        require(args.size >= 2 && args[0] in listOf("status", "run", "detach")) { USAGE }
        val command = args[0]
        val pid = args[1].toIntOrNull()?.takeIf { it > 1 } ?: error("PID must be an integer greater than 1")
        var source = "return {tick=game.tick, players=#game.connected_players}"
        var ticks = 1
        var timeout = 10000
        val seen = mutableSetOf<String>()
        var i = 2
        while (i < args.size) {
            val option = args[i++]
            require(seen.add(option) && i < args.size) { "Duplicate option or missing value: $option" }
            val value = args[i++]
            when (option) {
                "--lua" -> {
                    require(command == "run" && "--code" !in seen); source =
                        Path(value).readBounded(48000).decodeToString(throwOnInvalidSequence = true)
                }

                "--code" -> {
                    require(command == "run" && "--lua" !in seen); source = value
                }

                "--ticks" -> {
                    require(command == "run"); ticks = value.toInt().also { require(it in 1..1000000) }
                }

                "--timeout-ms" -> {
                    timeout = value.toInt().also { require(it in 1..600000) }
                }

                else -> error("Unknown option: $option")
            }
        }
        require(source.encodeToByteArray().size <= 48000) { "Lua source exceeds 48000 bytes" }
        Platform.initializeCancellation()
        val game = GameProcess(pid)
        try {
            check(!Platform.cancelled) { "Interrupted; cancelling session" }
            runBlocking {
                when (command) {
                    "status" -> println(game.diagnosticStatus(timeout))
                    "detach" -> println(game.evaluate(cleanupScript(), timeout))
                    "run" -> {
                        val result = game.runTicks(source, ticks, timeout)
                        println(result.json)
                        if (!result.ok) exitCode = 2
                    }
                }
            }
        } finally {
            runBlocking { game.close() }
        }
    } catch (e: Exception) {
        Platform.writeError("factorio-mcp: ${e.message}")
        exitCode = 1
    }
    if (exitCode != 0) Platform.exitProcess(exitCode)
}
