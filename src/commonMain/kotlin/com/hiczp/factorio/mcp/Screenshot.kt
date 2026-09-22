package com.hiczp.factorio.mcp

import kotlinx.coroutines.delay
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.io.encoding.Base64
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

/** Game-rendered image; no desktop capture or window manipulation. */
internal suspend fun GameProcess.screenshot(timeoutMillis: Int): String {
    val filename = "factorio-mcp-${Uuid.random()}.png"
    val path = Path(outputPath(filename))
    try {
        executeOnTick(
            """
            local p=assert(__factorio_mcp_resident_v1).player()
            game.take_screenshot{player=p, by_player=p, show_gui=true, show_entity_info=true,
                show_cursor_building_preview=true, position=p.position, zoom=p.zoom,
                resolution=p.display_resolution, path=${luaQuote(filename)}, force_render=true}
            return true
        """.trimIndent(), timeoutMillis
        )
        val start = TimeSource.Monotonic.markNow()
        val end = byteArrayOf(0, 0, 0, 0, 73, 69, 78, 68, -82, 66, 96, -126) // PNG IEND
        while (start.elapsedNow().inWholeMilliseconds < timeoutMillis) {
            check(!Platform.cancelled) { "Interrupted waiting for screenshot" }
            val bytes = runCatching { path.readBounded(32 * 1024 * 1024) }.getOrNull()
            if (bytes != null && bytes.size >= end.size && bytes.takeLast(end.size).toByteArray().contentEquals(end)) {
                return Base64.encode(bytes)
            }
            delay(50)
        }
        error("Screenshot was not rendered before timeout; a graphical client is required")
    } finally {
        SystemFileSystem.delete(path, mustExist = false)
    }
}
