package com.hiczp.factorio.mcp

import kotlinx.serialization.json.*

/** Existing image identity only; animated frames and their scripts are never expanded. */
internal data class WidgetImage(
    val present: Boolean,
    val nativeType: String? = null,
    val spriteObserved: Boolean = false,
    val sprite: WidgetIcon? = null,
)

internal fun WidgetImage.json(): JsonElement = if (!present) JsonNull else buildJsonObject {
    put("native_type", nativeType?.let(::JsonPrimitive) ?: JsonNull)
    if (spriteObserved) put("sprite", sprite?.json() ?: JsonNull)
}

internal fun widgetImage(
    status: UInt,
    spriteObserved: UInt,
    reference: Int,
    nameTruncated: UInt,
    type: () -> String,
    name: () -> String,
): WidgetImage? {
    require(status <= 2u && spriteObserved <= 1u && nameTruncated <= 1u) { "Invalid resident image metadata" }
    require(spriteObserved == 0u || status == 2u)
    require(nameTruncated == 0u || spriteObserved == 1u)
    return when (status) {
        0u -> null
        1u -> WidgetImage(false)
        else -> WidgetImage(
            true,
            type().removePrefix("class "),
            spriteObserved != 0u,
            if (spriteObserved != 0u) widgetIconReference(reference)?.copy(
                name = name().takeIf { it.isNotEmpty() },
                nameTruncated = nameTruncated != 0u,
            ) else null,
        )
    }
}
