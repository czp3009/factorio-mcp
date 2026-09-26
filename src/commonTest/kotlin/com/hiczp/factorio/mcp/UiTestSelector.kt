package com.hiczp.factorio.mcp

import kotlinx.serialization.json.*

internal fun uiSelector(text: String? = null, type: String? = null) = buildJsonObject {
    putJsonArray("path") {
        addJsonObject {
            put("axis", "descendant")
            putJsonObject("match") {
                text?.let { put("text", it) }
                type?.let { put("native_type", it) }
            }
        }
    }
}
