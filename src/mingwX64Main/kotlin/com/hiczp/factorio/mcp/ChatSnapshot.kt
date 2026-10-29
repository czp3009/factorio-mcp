@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.nativebridge.FmChatRecord
import com.hiczp.factorio.mcp.nativebridge.FmChatSnapshot
import kotlinx.cinterop.*

internal fun FmChatSnapshot.readChatSnapshot(readMemory: (Long, Int) -> ByteArray): ChatSnapshot {
    require(consoleIdentity != 0uL && count <= Int.MAX_VALUE.toUInt()) { "Invalid chat snapshot" }
    val totals = List(2) { totals[it].also { value -> require(value <= Int.MAX_VALUE.toULong()) } }
    val records = records.readObservationRecords<FmChatRecord, ChatRecord>(count, readMemory) { record ->
        require(
            record.identity != 0uL && record.stream < 2u &&
                record.playerIndex <= UShort.MAX_VALUE.toUInt() && record.truncated <= 7u
        ) { "Invalid chat record" }
        ChatRecord(
            record.identity,
            record.tick,
            record.stream.toInt(),
            record.playerIndex.toInt(),
            record.text.readObservationText(readMemory),
            record.raw.readObservationText(readMemory),
            record.truncated and 1u != 0u,
            record.truncated and 2u != 0u,
            record.playerName.readObservationText(readMemory),
            record.truncated and 4u != 0u,
        )
    }
    require(records.map { it.identity }.distinct().size == records.size && (0..1).all { stream ->
        records.count { it.stream == stream }.toULong() == totals[stream]
    }) { "Incomplete chat observation" }
    return ChatSnapshot(consoleIdentity, totals, records)
}
