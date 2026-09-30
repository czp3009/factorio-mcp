@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.linuxbridge.FM_LINUX_MAX_NODES
import com.hiczp.factorio.mcp.linuxbridge.FM_LINUX_MAX_OPTIONS
import com.hiczp.factorio.mcp.linuxbridge.FM_LINUX_OPTION_TEXT
import com.hiczp.factorio.mcp.linuxbridge.FM_LINUX_TEXT_SIZE
import com.hiczp.factorio.mcp.linuxbridge.FM_LINUX_TYPE_NAME
import com.hiczp.factorio.mcp.linuxbridge.FM_LINUX_WIDGET_OPTIONS
import com.hiczp.factorio.mcp.linuxbridge.FmLinuxUiSnapshot
import kotlinx.cinterop.get
import kotlinx.cinterop.readBytes

/** Copy bounded wire storage before releasing command ownership; no native view escapes. */
internal fun FmLinuxUiSnapshot.readWidgets(): List<WidgetSnapshot> {
    require(count <= FM_LINUX_MAX_NODES.toUInt()) { "Resident widget count exceeds wire storage" }
    require(optionCount <= FM_LINUX_MAX_OPTIONS.toUInt()) { "Resident option count exceeds wire storage" }
    val parents = ArrayList<Int>(count.toInt())
    val widgets = List(count.toInt()) { index ->
        val node = nodes[index]
        require(
            node.depth < 256u && node.textSize <= FM_LINUX_TEXT_SIZE.toUInt() &&
                    node.textSize.toULong() <= node.textTotal
        ) { "Resident widget fields exceed their bounds" }
        val typeBytes = node.type.readBytes(FM_LINUX_TYPE_NAME)
        val typeEnd = typeBytes.indexOf(0)
        require(typeEnd >= 0) { "Resident widget type is not terminated" }
        parents += node.parent
        val widgetOptions = if (node.optionsAvailable != 0u) {
            require(
                node.dropdownAvailable != 0u && node.optionFirst <= optionCount &&
                        node.optionCount <= optionCount - node.optionFirst && node.optionCount <= FM_LINUX_WIDGET_OPTIONS.toUInt() &&
                        node.optionCount <= node.optionTotal && node.optionTotal <= 65536u
            )
            WidgetOptions(List(node.optionCount.toInt()) { offset ->
                val option = options[node.optionFirst.toInt() + offset]
                require(option.size < FM_LINUX_OPTION_TEXT.toUInt())
                WidgetOption(option.text.readBytes(option.size.toInt()).decodeToString(), option.truncated != 0u)
            }, node.optionTotal.toInt())
        } else null
        WidgetSnapshot(
            depth = node.depth.toInt(),
            type = typeBytes.decodeToString(0, typeEnd),
            text = if (node.textAvailable != 0u) node.text.readBytes(node.textSize.toInt()).decodeToString() else null,
            enabled = node.enabled != 0u,
            selected = node.selected != 0u,
            x = node.bounds.x,
            y = node.bounds.y,
            width = node.bounds.width,
            height = node.bounds.height,
            textTruncated = node.textTruncated != 0u,
            typeTruncated = node.typeTruncated != 0u,
            textUnavailableReason = if (node.textAvailable == 0u) "Unsupported native text accessor" else null,
            flaggedForDestruction = node.destroying != 0u,
            visible = node.visible != 0u,
            hiddenBySearch = node.hiddenBySearch != 0u,
            renderEnabled = node.renderEnabled != 0u,
            properties = if (node.toggledAvailable != 0u || node.checkAvailable != 0u || node.sliderAvailable != 0u ||
                node.progressAvailable != 0u || node.dropdownAvailable != 0u
            ) WidgetProperties(
                selectedIndex = if (node.dropdownAvailable != 0u) node.selectedIndex else null,
                options = widgetOptions,
                optionsUnavailableReason = if (node.dropdownAvailable != 0u && node.optionsAvailable == 0u)
                    "Unsupported dropdown option text accessor" else null,
                toggled = if (node.toggledAvailable != 0u) node.toggled != 0u else null,
                slider = if (node.sliderAvailable != 0u) SliderProperties(
                    node.value,
                    node.minimum,
                    node.maximum,
                    node.step
                ) else null,
                checkState = if (node.checkAvailable != 0u) "unknown_${node.checkState}" else null,
                progress = if (node.progressAvailable != 0u) WidgetProgress(
                    node.progressValue,
                    "unknown_${node.progressDirection}", node.progressHasText != 0.toUByte()
                ) else null,
            ) else null,
        )
    }
    return widgetsInPostorder(widgets, parents)
}
