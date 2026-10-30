@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.nativebridge.FmTooltipLayout

/** Existing plain providers, including subclasses, without invoking their creation callbacks. */
internal class TooltipLayout(types: DebugTypes, baseTypes: List<ULong>, plainTypes: List<ULong>) {
    private val base = baseTypes.single()
    private val plain = plainTypes.single()
    private val creator =
        types.pointerMember("agui::Widget", "toolTipCreator", "agui::ToolTipCreatorBase")
    private val string = types.memberTypeName("agui::PlainToolTipCreator", "title")
    private val size =
        types.aggregateSize(string).also {
            require(
                string == "std::basic_string<char,std::char_traits<char>,std::allocator<char> >"
            )
        }
    private val title = types.namedMember("agui::PlainToolTipCreator", "title", string, size)
    private val text = types.namedMember("agui::PlainToolTipCreator", "text", string, size)

    fun write(target: FmTooltipLayout) {
        target.baseType = base
        target.plainType = plain
        target.creator = creator
        target.title = title
        target.text = text
    }
}
