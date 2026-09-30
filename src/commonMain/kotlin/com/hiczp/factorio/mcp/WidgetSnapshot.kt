package com.hiczp.factorio.mcp

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put

internal data class WidgetSnapshot(
    val depth: Int,
    val type: String,
    val text: String?,
    val enabled: Boolean,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val textTruncated: Boolean,
    val selected: Boolean = false,
    val typeTruncated: Boolean = false,
    val properties: WidgetProperties? = null,
    val visible: Boolean? = null,
    val renderEnabled: Boolean? = null,
    val hiddenBySearch: Boolean? = null,
    val textUnavailableReason: String? = null,
    val flaggedForDestruction: Boolean? = null,
)

/** Convert a bounded preorder forest without inventing missing parents or reordering siblings. */
internal fun widgetsInPostorder(nodes: List<WidgetSnapshot>, parents: List<Int>): List<WidgetSnapshot> {
    require(nodes.size == parents.size) { "Widget parent count differs from node count" }
    val pending = ArrayDeque<Int>()
    return buildList(nodes.size) {
        nodes.forEachIndexed { index, node ->
            require(node.depth >= 0) { "Widget depth is negative" }
            while (pending.isNotEmpty() && nodes[pending.last()].depth >= node.depth) add(nodes[pending.removeLast()])
            val parent = pending.lastOrNull() ?: -1
            require(parents[index] == parent && node.depth == if (parent < 0) 0 else nodes[parent].depth + 1) {
                "Widget parent/depth does not describe a preorder forest"
            }
            pending.addLast(index)
        }
        while (pending.isNotEmpty()) add(nodes[pending.removeLast()])
    }
}

internal data class WidgetProperties(
    val checkState: String? = null,
    val toggled: Boolean? = null,
    val selectedIndex: Int? = null,
    val slider: SliderProperties? = null,
    val options: WidgetOptions? = null,
    val optionsUnavailableReason: String? = null,
    val prototype: WidgetPrototype? = null,
    val number: WidgetNumber? = null,
    val progress: WidgetProgress? = null,
    val quality: WidgetPrototype? = null,
    val element: WidgetElement? = null,
    val icons: WidgetIcons? = null,
    val qualityCondition: WidgetQualityCondition? = null,
    val switch: WidgetSwitch? = null,
)

internal data class WidgetSwitch(val stateValue: Int, val state: String, val allowNone: Boolean)

internal data class WidgetQualityCondition(
    val qualityIndex: Int,
    val comparisonValue: Int,
    val comparison: String,
    val qualityName: String? = null,
    val qualityNameTruncated: Boolean = false,
    val qualityLookup: String = "null",
)

/** References use snapshot-local sprite indices; -1 is null and -2 is truncated. */
internal data class WidgetIcons(val normal: Int, val hovered: Int, val disabled: Int)

internal data class WidgetSprite(
    val filename: String?,
    val filenameTruncated: Boolean,
    val intentionallyEmpty: Boolean,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val scale: Double,
    val shiftX: Double,
    val shiftY: Double,
    val tint: List<Double>,
    val next: Int,
    val extra: Int,
)

internal data class WidgetElement(
    val present: Boolean,
    val stackCount: Long?,
    val item: WidgetItem?,
)

internal data class WidgetItem(
    val nativeType: String,
    val typeTruncated: Boolean,
    val health: Double,
    val durabilityLeft: Double? = null,
    val magazineLeft: Double? = null,
)

internal data class WidgetProgress(val value: Double, val direction: String, val hasText: Boolean)

internal data class WidgetNumber(
    val drawRequested: Boolean,
    val value: Double?,
    val showZero: Boolean?,
    val unknown: Boolean?,
    val infinite: Boolean?,
)

internal data class WidgetPrototype(
    val name: String?,
    val nativeType: String?,
    val nameTruncated: Boolean = false,
    val typeTruncated: Boolean = false,
)

internal data class WidgetOption(val text: String, val truncated: Boolean = false)

internal data class WidgetOptions(val values: List<WidgetOption>, val total: Int)

internal data class SliderProperties(
    val value: Double,
    val minimum: Double,
    val maximum: Double,
    val step: Double,
)

/** An observed empty string is different from an unsupported native text accessor. */
internal fun JsonObjectBuilder.widgetText(node: WidgetSnapshot) {
    val text = node.text
    if (text == null) {
        put("text", JsonNull)
        node.textUnavailableReason?.let { put("text_unavailable_reason", it) }
    } else if (text.isNotEmpty()) {
        put("text", text)
    }
    if (node.textTruncated) put("text_truncated", true)
}
