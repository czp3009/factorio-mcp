package com.hiczp.factorio.mcp

import kotlinx.serialization.json.*

/** Optional observation paths. Structure, identity and each widget's own flags are always returned. */
internal enum class UiField(val path: String) {
    TEXT("text"),
    CHECK_STATE("properties.check_state"),
    TOGGLED("properties.toggled"),
    TOGGLE_MODE("properties.toggle_mode"),
    SELECTED_INDEX("properties.selected_index"),
    VALUE("properties.value"),
    MINIMUM("properties.minimum"),
    MAXIMUM("properties.maximum"),
    STEP("properties.step"),
    OPTIONS("properties.options"),
    PROTOTYPE("properties.prototype"),
    NUMBER("properties.number"),
    PROGRESS("properties.progress"),
    QUALITY("properties.quality"),
    ELEMENT("properties.element"),
    ICONS("properties.icons"),
    QUALITY_CONDITION("properties.quality_condition"),
    SWITCH("properties.switch"),
    TOOLTIP("properties.tooltip"),
    GUI_PROPERTIES("gui.properties"),
    GUI_CHILDREN("gui.children"),
    IMAGE("properties.image"),
    GRAPH("properties.graph"),
    ACHIEVEMENT("properties.achievement");

    val mask: UInt
        get() = 1u shl ordinal
}

internal val defaultUiFields = UiField.entries.toSet()

internal fun Set<UiField>.uiFieldMask(): UInt = fold(0u) { mask, field -> mask or field.mask }

internal fun parseUiFields(value: JsonElement?): Set<UiField> {
    if (value == null) return defaultUiFields
    val names = value.jsonArray.map { it.stringArgument() }
    require(names.distinct().size == names.size) { "fields requires distinct observation paths" }
    return names.map { path ->
        requireNotNull(UiField.entries.find { it.path == path }) { "Unknown UI field: $path" }
    }.toSet()
}

internal fun UiCollection.uiFields(): Set<UiField> = when (source) {
    "options" -> setOf(UiField.OPTIONS)
    "graph" -> setOf(UiField.GRAPH)
    "text" -> setOf(UiField.TEXT)
    "tooltip_title", "tooltip_text" -> setOf(UiField.TOOLTIP)
    else -> emptySet()
}
