package com.hiczp.factorio.mcp

import kotlinx.serialization.json.*

internal data class ChatRecord(
    val identity: ULong,
    val tick: ULong,
    val stream: Int,
    val playerIndexRaw: Int,
    val text: String,
    val raw: String,
    val textTruncated: Boolean = false,
    val rawTruncated: Boolean = false,
)

internal data class ChatSnapshot(
    val console: ULong,
    val totals: List<ULong>,
    val records: List<ChatRecord>,
)

internal data class ChatOffset(val tick: ULong, val counts: List<Int> = listOf(0, 0)) {
    init {
        require(counts.size == 2 && counts.all { it in 0..65536 }) { "Invalid chat boundary counts" }
    }

    fun json() = buildJsonObject {
        put("tick", JsonPrimitive(tick))
        putJsonArray("counts") { counts.forEach { add(it) } }
    }
}

internal data class ChatReadRequest(val offset: ChatOffset?, val limit: Int, val timeout: Int)

private fun JsonElement.chatTick(): ULong {
    val value = this as? JsonPrimitive ?: error("Chat tick must be an unsigned JSON integer")
    require(!value.isString) { "Chat tick must be an unsigned JSON integer" }
    return value.content.toULongOrNull() ?: error("Chat tick must be an unsigned JSON integer")
}

internal fun parseChatRead(args: JsonObject): ChatReadRequest {
    require(args.keys.all { it in setOf("offset", "limit", "timeout") }) { "Unknown chat_read argument" }
    val offset = args["offset"]?.let { value ->
        if (value is JsonObject) {
            require(value.keys.all { it in setOf("tick", "counts") } && "tick" in value) { "Invalid chat offset" }
            ChatOffset(
                value.getValue("tick").chatTick(),
                value["counts"]?.jsonArray?.map { it.intArgument() } ?: listOf(0, 0),
            )
        } else {
            value.chatTick().takeUnless { it == 0uL }?.let { ChatOffset(it) }
        }
    }
    val limit = args["limit"]?.intArgument() ?: 64
    val timeout = args["timeout"]?.intArgument() ?: 0
    require(limit in 1..128) { "Chat limit must be 1..128" }
    require(timeout >= 0) { "Chat timeout must be nonnegative seconds" }
    return ChatReadRequest(offset, limit, timeout)
}

internal fun parseChatMessage(args: JsonObject): String {
    require(args.keys == setOf("text")) { "chat_send requires only text" }
    return validateChatMessage(args.getValue("text").stringArgument())
}

internal fun validateChatMessage(text: String): String {
    require(
        text.isNotBlank() &&
                text.encodeToByteArray().size <= 4096 &&
                text.none { it == '\u0000' || it == '\r' || it == '\n' } &&
                !text.trimStart().startsWith('/')
    ) {
        "Expected one plain chat message of at most 4096 UTF-8 bytes; console commands are not supported"
    }
    return text
}

/**
 * Native tick watermarks over retained player-console snapshots, not an event subscription.
 * Boundary positions count each storage separately; neither addresses nor attachment identities escape.
 */
internal class ChatHistory {
    private data class Entry(val record: ChatRecord, val position: Int)
    private var console: ULong? = null

    fun read(snapshot: ChatSnapshot, offset: ChatOffset?, limit: Int): JsonObject {
        require(limit in 1..128)
        require(snapshot.totals.size == 2 && snapshot.records.size <= 256 &&
                snapshot.records.all { it.stream in 0..1 }) { "Invalid native chat snapshot" }
        val changed = console != null && console != snapshot.console
        console = snapshot.console
        val positions = mutableMapOf<Pair<ULong, Int>, Int>()
        // Each native storage is newest-first. Equal-tick positions come from that storage's
        // chronological list order, independently of pointer values or which MCP first observed it.
        val observed = snapshot.records.reversed()
            .sortedWith(compareBy<ChatRecord> { it.tick }.thenBy { it.stream })
            .map { record ->
                val key = record.tick to record.stream
                val position = positions[key] ?: 0
                positions[key] = position + 1
                Entry(record, position)
            }
        val candidates =
            if (offset == null) observed.takeLast(limit)
            else observed.filter { entry ->
                entry.record.tick > offset.tick || entry.record.tick == offset.tick &&
                        entry.position >= offset.counts[entry.record.stream]
            }
        val page = candidates.take(limit)
        fun boundaryTruncated(tick: ULong): Boolean = (0..1).any { stream ->
            val records = snapshot.records.filter { it.stream == stream }
            snapshot.totals[stream] > records.size.toULong() && records.lastOrNull()?.tick == tick
        }
        var progress = offset ?: ChatOffset(0uL)
        val pageOffsets = page.map { entry ->
            val tick = entry.record.tick
            val counts = if (progress.tick == tick) progress.counts.toMutableList() else mutableListOf(0, 0)
            val stream = entry.record.stream
            counts[stream] = maxOf(counts[stream], entry.position + 1)
            // A recent-history read intentionally skips earlier entries from both streams.
            if (offset == null) {
                for (other in observed) {
                    if (other.record.tick == tick && other.record.stream < stream)
                        counts[other.record.stream] = maxOf(counts[other.record.stream], other.position + 1)
                }
            }
            ChatOffset(tick, counts.toList()).also { progress = it }
        }
        val lostBoundary = offset != null && (boundaryTruncated(offset.tick) || (0..1).any { stream ->
            val count = positions[offset.tick to stream] ?: 0
            offset.counts[stream] > count
        })
        return buildJsonObject {
            put("observation", "local_console_history")
            put("offset_basis", "native_tick_and_storage_position")
            put("next_offset", progress.json())
            put("boundary_truncated", boundaryTruncated(progress.tick))
            put("has_more", candidates.size > page.size)
            put("history_lost", changed || lostBoundary)
            put("snapshot_truncated", snapshot.totals.any { it > 128uL })
            put("missed_between_reads_possible", true)
            putJsonArray("retained_counts") { snapshot.totals.forEach { add(JsonPrimitive(it)) } }
            putJsonArray("messages") {
                page.forEachIndexed { index, entry ->
                    addJsonObject {
                        val record = entry.record
                        put("offset", pageOffsets[index].json())
                        put("tick", JsonPrimitive(record.tick))
                        put("position_in_tick", entry.position)
                        put("storage", if (record.stream == 0) "game_state" else "local")
                        put("player_index_raw", record.playerIndexRaw)
                        put("text", record.text)
                        put("raw", record.raw)
                        put("text_truncated", record.textTruncated)
                        put("raw_truncated", record.rawTruncated)
                    }
                }
            }
        }
    }
}

internal fun chatReadSchema() = buildJsonObject {
    putJsonObject("offset") {
        putJsonArray("anyOf") {
            addJsonObject {
                put("type", "integer")
                put("minimum", 0)
                put("maximum", JsonPrimitive(ULong.MAX_VALUE))
            }
            addJsonObject {
                put("type", "object")
                put("additionalProperties", false)
                putJsonArray("required") { add("tick") }
                putJsonObject("properties") {
                    putJsonObject("tick") {
                        put("type", "integer")
                        put("minimum", 0)
                        put("maximum", JsonPrimitive(ULong.MAX_VALUE))
                    }
                    putJsonObject("counts") {
                        put("type", "array")
                        put("minItems", 2)
                        put("maxItems", 2)
                        putJsonObject("items") {
                            put("type", "integer")
                            put("minimum", 0)
                            put("maximum", 65536)
                        }
                        put("description", "Already-read records at this tick, in game_state and local storage respectively.")
                    }
                }
            }
        }
        put("default", 0)
        put(
            "description",
            "Omit or use 0 for recent history. A positive native tick includes its boundary messages; pass returned next_offset to continue within that tick without duplicates. Offsets concern this player's retained console, not a global server log.",
        )
    }
    putJsonObject("timeout") {
        put("type", "integer")
        put("minimum", 0)
        put("maximum", Int.MAX_VALUE)
        put("default", 0)
        put("description", "Seconds to wait for messages when the selected history is empty. Zero returns immediately.")
    }
    putJsonObject("limit") {
        put("type", "integer")
        put("minimum", 1)
        put("maximum", 128)
        put("default", 64)
    }
}

internal fun chatSendSchema() = buildJsonObject {
    putJsonObject("text") {
        put("type", "string")
        put("minLength", 1)
        put("maxLength", 4096)
        put(
            "description",
            "One plain chat message, at most 4096 UTF-8 bytes. No NUL, newline or slash commands.",
        )
    }
}
