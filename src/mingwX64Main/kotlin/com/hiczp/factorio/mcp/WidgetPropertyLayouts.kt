@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.nativebridge.WidgetPropertyLayout

internal class WidgetPropertyLayouts(types: DebugTypes) {
    private val checkState =
        types.namedMember(
            "agui::ToggleButton",
            "checkedState",
            "agui::ToggleButton::CheckedState",
            4uL,
        )
    private val toggleMode = types.byteMember("agui::Button", "isButtonToggleButton", true)
    private val toggled = types.byteMember("agui::Button", "toggled", true)
    private val selectedIndex = types.scalarMember("agui::DropDown", "selectedIndex", 4uL, 6u)
    private val value = types.scalarMember("agui::Slider", "value", 8uL, 8u)
    private val minimum = types.scalarMember("agui::Slider", "min", 8uL, 8u)
    private val maximum = types.scalarMember("agui::Slider", "max", 8uL, 8u)
    private val valueStep = types.scalarMember("agui::Slider", "valueStep", 8uL, 8u)
    val options = runCatching { DropdownOptionLayout(types) }

    fun write(target: WidgetPropertyLayout) {
        target.supported = 1u
        target.checkState = checkState
        target.toggleMode = toggleMode
        target.toggled = toggled
        target.selectedIndex = selectedIndex
        target.value = value
        target.minimum = minimum
        target.maximum = maximum
        target.valueStep = valueStep
        options.getOrNull()?.write(target)
    }
}

/** Resolves the dropdown's owned list entries without opening or changing the widget. */
internal class DropdownOptionLayout(types: DebugTypes) {
    private val item = "agui::ListBoxItem"
    private val vector = "std::vector<agui::ListBoxItem,std::allocator<agui::ListBoxItem> >"
    private val string = "std::basic_string<char,std::char_traits<char>,std::allocator<char> >"
    private val list =
        types.namedMember(
            "agui::DropDown",
            "listBox",
            "agui::ListBox",
            types.aggregateSize("agui::ListBox"),
        )
    private val items =
        types.namedMember("agui::ListBox", "items", vector, types.aggregateSize(vector))
    private val first =
        types.pointerPath(vector, "_Mypair", "_Myval2", "_Myfirst", target = item, indirections = 1)
    private val last =
        types.pointerPath(vector, "_Mypair", "_Myval2", "_Mylast", target = item, indirections = 1)
    private val stride = types.aggregateSize(item).also { check(it in 1uL..4096uL) }.toUInt()
    private val button =
        types.pointerPath(
            item,
            "button",
            "_Mypair",
            "_Myval2",
            target = "agui::TextButton",
            indirections = 1,
        )
    private val text =
        types.namedMember("agui::TextButton", "text", string, types.aggregateSize(string))

    fun write(target: WidgetPropertyLayout) {
        target.optionsSupported = 1u
        target.optionFirst = list + items + first
        target.optionLast = list + items + last
        target.optionStride = stride
        target.optionButton = button
        target.optionText = text
    }
}
