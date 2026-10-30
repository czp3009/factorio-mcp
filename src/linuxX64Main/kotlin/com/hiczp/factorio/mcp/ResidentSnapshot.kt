@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.linuxbridge.FM_LINUX_ELEMENT_TEXT
import com.hiczp.factorio.mcp.linuxbridge.FM_LINUX_IDENTITY_TEXT
import com.hiczp.factorio.mcp.linuxbridge.FM_LINUX_OPTION_TEXT
import com.hiczp.factorio.mcp.linuxbridge.FM_LINUX_TEXT_SIZE
import com.hiczp.factorio.mcp.linuxbridge.FM_LINUX_TYPE_NAME
import com.hiczp.factorio.mcp.linuxbridge.FmLinuxPrototypeValue
import com.hiczp.factorio.mcp.linuxbridge.FmLinuxUiElement
import com.hiczp.factorio.mcp.linuxbridge.FmLinuxUiNode
import com.hiczp.factorio.mcp.linuxbridge.FmLinuxUiOption
import com.hiczp.factorio.mcp.linuxbridge.FmLinuxUiSnapshot
import kotlinx.cinterop.get
import kotlinx.cinterop.readBytes

/** Copy bounded wire storage before releasing command ownership; no native view escapes. */
internal fun FmLinuxUiSnapshot.readWidgets(
    readMemory: (Long, Int) -> ByteArray,
    comparisons: List<String> = emptyList(),
    switchNames: List<String> = emptyList(),
    progressNames: Map<Int, String> = emptyMap(),
): List<WidgetSnapshot> {
    val copiedOptions =
        options.readObservationRecords<FmLinuxUiOption, WidgetOption>(optionCount, readMemory) {
            option ->
            require(option.size <= FM_LINUX_OPTION_TEXT.toUInt())
            WidgetOption(
                option.text
                    .also { it.validateObservationBuffer(option.size.toLong()) }
                    .readObservationBytes(readMemory)
                    .decodeToString(),
                option.truncated != 0u,
            )
        }
    val parents = ArrayList<Int>(count.toInt())
    val widgets =
        nodes.readObservationRecords<FmLinuxUiNode, WidgetSnapshot>(count, readMemory) { node ->
            fun icon(reference: Int, index: Int) =
                widgetIconReference(reference)
                    ?.copy(
                        name =
                            node.iconNames[index]
                                .readObservationBytes(readMemory)
                                .decodeToString()
                                .takeIf { it.isNotEmpty() },
                        nameTruncated = node.iconNameTruncated and (1u shl index) != 0u,
                    )
            require(
                node.depth < count &&
                    node.textSize <= FM_LINUX_TEXT_SIZE.toUInt() &&
                    node.textSize.toULong() <= node.textTotal
            ) {
                "Resident widget fields exceed their bounds"
            }
            val typeBytes = node.type.readBytes(FM_LINUX_TYPE_NAME)
            val typeEnd = typeBytes.indexOf(0)
            require(typeEnd >= 0) { "Resident widget type is not terminated" }
            parents += node.parent
            val widgetOptions =
                if (node.optionsAvailable != 0u) {
                    require(
                        node.dropdownAvailable != 0u &&
                            node.optionFirst <= optionCount &&
                            node.optionCount <= optionCount - node.optionFirst &&
                            node.optionCount <= node.optionTotal &&
                            node.optionTotal <= Int.MAX_VALUE.toUInt()
                    )
                    WidgetOptions(
                        copiedOptions.subList(
                            node.optionFirst.toInt(),
                            (node.optionFirst + node.optionCount).toInt(),
                        ),
                        node.optionTotal.toInt(),
                    )
                } else null
            WidgetSnapshot(
                depth = node.depth.toInt(),
                type = typeBytes.decodeToString(0, typeEnd),
                text =
                    if (node.textAvailable != 0u)
                        node.text
                            .also { it.validateObservationBuffer(node.textSize.toLong()) }
                            .readObservationBytes(readMemory)
                            .decodeToString()
                    else null,
                enabled = node.enabled != 0u,
                selected = node.selected != 0u,
                x = node.bounds.x,
                y = node.bounds.y,
                width = node.bounds.width,
                height = node.bounds.height,
                textTruncated = node.textTruncated != 0u,
                typeTruncated = node.typeTruncated != 0u,
                textUnavailableReason =
                    if (node.textAvailable == 0u) "Unsupported native text accessor" else null,
                flaggedForDestruction = node.destroying != 0u,
                visible = node.visible != 0u,
                hiddenBySearch = node.hiddenBySearch != 0u,
                renderEnabled = node.renderEnabled != 0u,
                properties =
                    if (
                        node.toggledAvailable != 0u ||
                            node.checkAvailable != 0u ||
                            node.sliderAvailable != 0u ||
                            node.progressAvailable != 0u ||
                            node.dropdownAvailable != 0u ||
                            node.switchAvailable != 0u ||
                            node.numberAvailable != 0u ||
                            node.iconsAvailable != 0u ||
                            node.tooltipStatus != 0u ||
                            node.identity.available != 0u ||
                            node.element.available != 0u ||
                            node.quality.available != 0u
                    )
                        WidgetProperties(
                            qualityCondition =
                                if (node.quality.available != 0u)
                                    node.quality.let { condition ->
                                        require(
                                            condition.quality <= 255u &&
                                                condition.comparison <= 255u &&
                                                condition.lookup <= 2u &&
                                                condition.nameSize <
                                                    FM_LINUX_IDENTITY_TEXT.toUInt() &&
                                                condition.truncated <= 1u
                                        )
                                        require(
                                            condition.lookup == 1u ||
                                                condition.nameSize == 0u &&
                                                    condition.truncated == 0u
                                        )
                                        WidgetQualityCondition(
                                            condition.quality.toInt(),
                                            condition.comparison.toInt(),
                                            comparisons.getOrNull(condition.comparison.toInt())
                                                ?: "unknown_${condition.comparison}",
                                            condition.name
                                                .readBytes(condition.nameSize.toInt())
                                                .decodeToString()
                                                .takeIf { condition.lookup == 1u },
                                            condition.truncated != 0u,
                                            when (condition.lookup) {
                                                1u -> "present"
                                                2u -> "index_out_of_range"
                                                else -> "null"
                                            },
                                        )
                                    }
                                else null,
                            prototype =
                                if (node.identity.available != 0u)
                                    node.identity.prototype.readPrototype()
                                else null,
                            quality =
                                if (node.identity.available != 0u)
                                    node.identity.quality.readPrototype()
                                else null,
                            element =
                                if (node.element.available != 0u) node.element.readElement()
                                else null,
                            icons =
                                if (node.iconsAvailable != 0u)
                                    WidgetIcons(
                                        icon(node.iconNormal, 0),
                                        icon(node.iconHovered, 1),
                                        icon(node.iconDisabled, 2),
                                    )
                                else null,
                            tooltip =
                                widgetTooltip(
                                    node.tooltipStatus,
                                    node.tooltipTruncated,
                                    {
                                        node.tooltipTitle
                                            .readObservationBytes(readMemory)
                                            .decodeToString()
                                    },
                                    {
                                        node.tooltipText
                                            .readObservationBytes(readMemory)
                                            .decodeToString()
                                    },
                                ),
                            selectedIndex =
                                if (node.dropdownAvailable != 0u) node.selectedIndex else null,
                            number =
                                if (node.numberAvailable != 0u)
                                    WidgetNumber(
                                        node.numberFlags and 1u != 0u,
                                        node.numberValue.takeIf { node.numberFlags and 16u != 0u },
                                        (node.numberFlags and 2u != 0u).takeIf {
                                            node.numberFlags and 1u != 0u
                                        },
                                        (node.numberFlags and 4u != 0u).takeIf {
                                            node.numberFlags and 1u != 0u
                                        },
                                        (node.numberFlags and 8u != 0u).takeIf {
                                            node.numberFlags and 1u != 0u
                                        },
                                    )
                                else null,
                            options = widgetOptions,
                            optionsUnavailableReason =
                                if (node.dropdownAvailable != 0u && node.optionsAvailable == 0u)
                                    "Unsupported dropdown option text accessor"
                                else null,
                            toggled = if (node.toggledAvailable != 0u) node.toggled != 0u else null,
                            slider =
                                if (node.sliderAvailable != 0u)
                                    SliderProperties(
                                        node.value,
                                        node.minimum,
                                        node.maximum,
                                        node.step,
                                    )
                                else null,
                            checkState = if (node.checkAvailable != 0u) node.checkState else null,
                            progress =
                                if (node.progressAvailable != 0u)
                                    WidgetProgress(
                                        node.progressValue,
                                        progressNames[node.progressDirection.toInt()]
                                            ?: "unknown_${node.progressDirection}",
                                        node.progressHasText != 0.toUByte(),
                                    )
                                else null,
                            switch =
                                if (node.switchAvailable != 0u)
                                    WidgetSwitch(
                                        node.switchState.toInt(),
                                        switchNames.getOrNull(node.switchState.toInt())
                                            ?: "unknown_${node.switchState}",
                                        node.switchAllowNone != 0.toUByte(),
                                    )
                                else null,
                        )
                    else null,
            )
        }
    return widgetsInPostorder(widgets, parents)
}

private fun FmLinuxPrototypeValue.readPrototype(): WidgetPrototype {
    require(
        flags and 7u == flags &&
            nameSize < FM_LINUX_IDENTITY_TEXT.toUInt() &&
            typeSize < FM_LINUX_IDENTITY_TEXT.toUInt()
    ) {
        "Resident prototype identity exceeds wire storage"
    }
    require(flags and 1u != 0u || flags == 0u && nameSize == 0u && typeSize == 0u)
    return WidgetPrototype(
        if (flags and 1u != 0u) name.readBytes(nameSize.toInt()).decodeToString() else null,
        if (flags and 1u != 0u) type.readBytes(typeSize.toInt()).decodeToString() else null,
        flags and 2u != 0u,
        flags and 4u != 0u,
    )
}

private fun FmLinuxUiElement.readElement(): WidgetElement {
    require(flags and 63u == flags && typeSize < FM_LINUX_ELEMENT_TEXT.toUInt()) {
        "Resident element exceeds wire storage"
    }
    require(flags and 4u == 0u || flags and 2u != 0u)
    require(flags and 4u != 0u || flags and 56u == 0u && typeSize == 0u)
    return WidgetElement(
        flags and 2u != 0u,
        count.toLong().takeIf { flags and 3u == 3u },
        if (flags and 4u != 0u)
            WidgetItem(
                type.readBytes(typeSize.toInt()).decodeToString(),
                flags and 32u != 0u,
                health.toDouble(),
                durability.takeIf { flags and 8u != 0u },
                magazine.toDouble().takeIf { flags and 16u != 0u },
            )
        else null,
    )
}
