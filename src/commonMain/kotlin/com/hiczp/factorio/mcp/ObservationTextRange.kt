package com.hiczp.factorio.mcp

import kotlinx.serialization.json.*

/** Range metadata accompanies text already sliced in the resident process. */
internal data class ObservationTextRange(val offset: Int, val total: Long, val nextOffset: Int, val hasMore: Boolean) {
    init {
        require(offset >= 0 && total in 0..Int.MAX_VALUE.toLong() && nextOffset >= 0 && nextOffset <= total)
        require(nextOffset >= minOf(offset.toLong(), total) && hasMore == (nextOffset < total))
    }

    fun json(value: String) = buildJsonObject {
        require(value.encodeToByteArray().size.toLong() == nextOffset - minOf(offset.toLong(), total)) {
            "Native text slice length does not match its range"
        }
        put("value", value)
        put("value_type", "string")
        put("unit", "utf8_bytes")
        put("offset", offset)
        put("total", total)
        put("has_more", hasMore)
        put("truncated", hasMore)
        if (hasMore) put("next_offset", nextOffset)
    }
}
