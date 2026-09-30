package com.hiczp.factorio.mcp

import kotlinx.serialization.json.*
import kotlin.random.Random

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
 * Observed snapshots, not an event subscription. No inference about messages evicted between reads.
 */
internal class ChatHistory {
    private data class Key(val identity: ULong, val tick: ULong, val stream: Int, val raw: String)

    private data class Entry(val sequence: Long, val record: ChatRecord)

    private val scope = Random.nextLong().toULong().toString(16)
    private var sequence = 0L
    private var console: ULong? = null
    private var visible = emptyMap<Key, Long>()
    private val history = ArrayDeque<Entry>()

    fun read(snapshot: ChatSnapshot, after: String?, limit: Int): JsonObject {
        require(limit in 1..128)
        val afterSequence =
            after?.let {
                require(it.startsWith("$scope:")) {
                    "Chat cursor belongs to another attachment; omit after to read current history"
                }
                it.substringAfter(':').toLongOrNull()?.also { value ->
                    require(value in 0..sequence)
                } ?: error("Invalid chat cursor")
            }
        val changed = console != null && console != snapshot.console
        if (changed) {
            visible = emptyMap()
            history.clear()
        }
        console = snapshot.console
        val observed = mutableMapOf<Key, Long>()
        // Each native list is newest-first. Preserve list order for equal ticks without
        // interpreting channels.
        snapshot.records
            .reversed()
            .sortedBy { it.tick }
            .forEach { record ->
                val key = Key(record.identity, record.tick, record.stream, record.raw)
                val existing = visible[key]
                val id = existing ?: (++sequence).also { history.addLast(Entry(it, record)) }
                observed[key] = id
                if (existing != null) {
                    val index = history.indexOfFirst { it.sequence == id }
                    if (index >= 0) history[index] = Entry(id, record)
                }
            }
        visible = observed
        while (history.size > 512) history.removeFirst()
        val candidates =
            if (afterSequence == null) history.takeLast(limit)
            else history.filter { it.sequence > afterSequence }
        val page = candidates.take(limit)
        val cursor = page.lastOrNull()?.sequence ?: sequence
        return buildJsonObject {
            put("observation", "local_console_history")
            put("cursor", "$scope:$cursor")
            put("has_more", candidates.size > page.size)
            put(
                "history_lost",
                changed ||
                        afterSequence != null &&
                        afterSequence < (history.firstOrNull()?.sequence ?: (sequence + 1)) - 1,
            )
            put("snapshot_truncated", snapshot.totals.any { it > 128uL })
            put("missed_between_reads_possible", true)
            putJsonArray("retained_counts") { snapshot.totals.forEach { add(JsonPrimitive(it)) } }
            putJsonArray("messages") {
                page.forEach { entry ->
                    addJsonObject {
                        val record = entry.record
                        put("id", entry.sequence)
                        put("tick", JsonPrimitive(record.tick))
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
    putJsonObject("after") {
        put("type", "string")
        put(
            "description",
            "Cursor from a previous read on this attachment. Returns later observations; not a lossless subscription.",
        )
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
