package com.hiczp.factorio.mcp

import kotlinx.serialization.json.*

internal data class InputTransferSnapshot(
    val available: Boolean,
    val clientPresent: Boolean,
    val queuedBatches: Long,
    val segmentIndex: Long? = null,
    val totalSegments: Long? = null,
    val reason: String? = null,
) {
    fun toJson() = buildJsonObject {
        put("source", "local_input_segment_queue")
        put("available", available)
        if (!available) {
            put("reason", reason ?: "Input transfer observation unavailable")
        } else {
            put("client_present", clientPresent)
            put("queued_batches", queuedBatches)
            put(
                "front_batch_blueprint_import",
                if (segmentIndex == null) JsonNull
                else
                    buildJsonObject {
                        put("segment_index", segmentIndex)
                        put("total_segments", totalSegments)
                    },
            )
        }
    }
}
