#pragma once
#include "ui_stored_text.h"
#include <cxxabi.h>

inline bool validTooltipLayout(const FmLinuxTooltipLayout &layout, uint32_t widgetSize) {
    if (!layout.plainType)
        return !layout.baseType && !layout.plainSize && !layout.creator && !layout.title && !layout.text &&
            !layout.stringSize && !layout.stringData && !layout.stringLength;
    return layout.baseType && layout.plainType != layout.baseType && layout.plainType % 8 == 0 && layout.baseType % 8 == 0 &&
        layout.plainSize <= 65536 && layout.stringSize && layout.stringSize <= 256 &&
        fm::member(layout.creator, 8, widgetSize) && fm::member(layout.title, layout.stringSize, layout.plainSize) &&
        fm::member(layout.text, layout.stringSize, layout.plainSize) &&
        (layout.title + layout.stringSize <= layout.text || layout.text + layout.stringSize <= layout.title) &&
        fm::member(layout.stringData, 8, layout.stringSize) && fm::member(layout.stringLength, 8, layout.stringSize);
}

inline void collectTooltip(uintptr_t widget, const FmLinuxTooltipLayout &layout,
                           FmLinuxUiNode &node, UiSnapshot &storage) {
    if (!layout.plainType)
        return;
    uintptr_t creator;
    node.tooltipStatus = 4;
    if (!fm::read(widget + layout.creator, creator))
        return;
    if (!creator) {
        node.tooltipStatus = 0;
        return;
    }
    const auto *plain = abi::__dynamic_cast(reinterpret_cast<const void *>(creator),
        reinterpret_cast<const abi::__class_type_info *>(layout.baseType),
        reinterpret_cast<const abi::__class_type_info *>(layout.plainType), -1);
    if (!plain) {
        node.tooltipStatus = 2;
        return;
    }
    const auto object = reinterpret_cast<uintptr_t>(plain);
    bool titleTruncated = false, textTruncated = false;
    if (!fm::addressRange(object, layout.plainSize) ||
        !copyStoredText(object + layout.title, layout.stringData, layout.stringLength, storage, node.tooltipTitle, titleTruncated) ||
        !copyStoredText(object + layout.text, layout.stringData, layout.stringLength, storage, node.tooltipText, textTruncated))
        return;
    node.tooltipTruncated = (titleTruncated ? 1u : 0u) | (textTruncated ? 2u : 0u);
    node.tooltipStatus = 3;
}
