#include "ui_snapshot.h"
#include "script_gui.h"
#include "ui_reference.h"
#include "ui_number.h"
#include "ui_icons.h"
#include "ui_tooltips.h"
#include "linux_ipc.h"
#include "memory_read.h"
#include "native_type.h"
#include "widget_text.h"
#include <algorithm>
#include "../../nativeMain/native/widget_selection.h"
#include "../../nativeMain/native/selector_payload.h"
#include "../../nativeMain/native/ui_fields.h"
#include <array>
#include <cerrno>
#include <cstdlib>
#include <cstring>
#include <cxxabi.h>
#include <memory>
#include <string>
#include <unordered_set>

namespace {
using fm::member;
using fm::read;
using fm::readBytes;

bool validFlag(const FmLinuxMemberFlag &flag, uint32_t size) {
    return (flag.width == 1 || flag.width == 2 || flag.width == 4 || flag.width == 8) &&
           member(flag.offset, flag.width, size) && flag.shift < flag.width * 8 &&
           flag.mask == (uint64_t{1} << flag.shift);
}

bool flag(uintptr_t object, const FmLinuxMemberFlag &layout, uint32_t &output) {
    uint64_t raw = 0;
    if (!readBytes(object + layout.offset, &raw, layout.width))
        return false;
    output = (raw & layout.mask) >> layout.shift;
    return true;
}

bool typeName(uintptr_t object, FmLinuxUiNode &node, UiSnapshot &output) {
    uint32_t size = 0;
    bool truncated = false;
    return fm::readTypeName(object, node.type, size, truncated, output.strings) == 0;
}

struct Frame {
    uintptr_t object;
    uint32_t node;
    uint32_t range = 0;
    uintptr_t next = 0;
    uintptr_t end = 0;
    bool loaded = false;
};

bool text(uintptr_t object, const FmLinuxTextLayout &layout, FmLinuxUiNode &node, UiSnapshot &output) {
    WidgetTextView view;
    if (!widgetTextView(object, layout, view))
        return false;
    if (!view.available)
        return true;
    if (view.length > INT32_MAX)
        return false;
    const auto count = view.length;
    auto &copied = output.strings.emplace_back(count);
    if (!copied.empty() && !readBytes(view.data, copied.data(), copied.size()))
        return false;
    node.text = observationBuffer(copied);
    node.textAvailable = 1;
    node.textSize = count;
    node.textTotal = view.length;
    node.textTruncated = count < view.length;
    return true;
}

int identity(uintptr_t object, const FmLinuxUiLayout &layout, FmLinuxUiNode &node, UiSnapshot &output, uint32_t fields) {
    if (const int error = collectIdentity(object, layout.identity, node.identity, output.strings, fields)) return error;
    if (node.identity.available || !(fields & FM_UI_PROTOTYPE)) return 0;
    const FmUiStoredPrototypeLayout *layouts[] = {&layout.connectionPrototype, &layout.achievementPrototype};
    for (const auto *stored : layouts) {
        if (!stored->supported) continue;
        const auto receiver = reinterpret_cast<uintptr_t>(abi::__dynamic_cast(reinterpret_cast<const void *>(object),
            reinterpret_cast<const abi::__class_type_info *>(layout.identity.widgetType),
            reinterpret_cast<const abi::__class_type_info *>(stored->type), -1));
        if (!receiver) continue;
        if (!fm::addressRange(receiver, stored->size)) return EFAULT;
        const auto prototype = fm::storedPrototype(receiver, *stored, readBytes);
        if (const int error = readPrototypeIdentity(prototype, false, layout.identity, node.identity.prototype, output.strings)) return error;
        // Provider observations include quality; retained prototype references have no such property.
        node.identity.available = 2;
        break;
    }
    return 0;
}

int dropdown(uintptr_t object, const FmLinuxUiLayout &layout, FmLinuxUiNode &node, UiSnapshot &output,
             uint32_t offset, uint32_t limit, uint32_t fields) {
    if (!layout.dropdownCount)
        return 0;
    uintptr_t table;
    if (!read(object, table))
        return EFAULT;
    for (uint32_t index = 0; index < layout.dropdownCount; ++index) {
        const auto &item = layout.dropdowns[index];
        if (item.table != table)
            continue;
        uintptr_t first, last;
        if (!fm::addressRange(object, item.objectSize))
            return EFAULT;
        if (fields & FM_UI_SELECTED_INDEX) {
            if (!read(object + item.selected, node.selectedIndex)) return EFAULT;
            node.dropdownAvailable = 1;
        }
        if (!(fields & FM_UI_OPTIONS)) return 0;
        if (!read(object + item.first, first) || !read(object + item.last, last)) return EFAULT;
        if (last < first || (last - first) % item.stride || (last - first) / item.stride > INT32_MAX ||
            (last != first && !fm::addressRange(first, last - first)))
            return EPROTO;
        node.optionTotal = (last - first) / item.stride;
        node.optionFirst = output.optionCount;
        node.optionOffset = offset;
        node.optionCount = std::min(limit, node.optionTotal - std::min(offset, node.optionTotal));
        if (node.optionCount > INT32_MAX - output.optionCount)
            return EOVERFLOW;
        output.options.resize(output.optionCount + node.optionCount);
        for (uint32_t optionIndex = 0; optionIndex < node.optionCount; ++optionIndex) {
            uintptr_t button;
            if (!read(first + size_t(optionIndex + offset) * item.stride + item.button, button) || !button)
                return EFAULT;
            WidgetTextView view;
            if (!widgetTextView(button, layout.text, view))
                return EFAULT;
            if (!view.available) {
                output.optionCount = node.optionFirst;
                output.options.resize(output.optionCount);
                node.optionCount = 0;
                return 0;
            }
            auto &option = output.options[output.optionCount];
            if (view.length > INT32_MAX)
                return EOVERFLOW;
            const auto count = view.length;
            auto &copied = output.strings.emplace_back(count);
            if (!copied.empty() && !readBytes(view.data, copied.data(), copied.size()))
                return EFAULT;
            option.text = observationBuffer(copied);
            option.size = count;
            option.truncated = count < view.length;
            ++output.optionCount;
        }
        node.optionsAvailable = 1;
        return 0;
    }
    return 0;
}

int rectangle(uintptr_t object, const FmLinuxUiLayout &layout, FmLinuxRectangle &output) {
    // Validate the complete parent chain before entering the original native getter's loop.
    std::unordered_set<uintptr_t> parents;
    auto current = object;
    while (current) {
        if (!parents.insert(current).second)
            return ELOOP;
        if (current > static_cast<uintptr_t>(INTPTR_MAX) - layout.widgetSize)
            return EFAULT;
        if (!read(current + layout.rectangle.parent, current))
            return EFAULT;
    }
    // Four int32 fields have INTEGER/INTEGER System V classification (RAX and RDX).
    using Getter = FmLinuxRectangle (*)(const void *);
    try {
        output = reinterpret_cast<Getter>(layout.rectangle.function)(reinterpret_cast<const void *>(object));
        return 0;
    } catch (...) {
        return EFAULT;
    }
}
} // namespace

bool validUiLayout(const FmLinuxUiLayout &layout) {
    if (!validNumberLayout(layout.number) || !validIconLayout(layout.icons) || !validUiImageLayout(layout.images) ||
        !fm::validGraphLayout(layout.graph) || !validIdentityLayout(layout.identity) ||
        !fm::validStoredPrototypeLayout(layout.connectionPrototype) || !fm::validStoredPrototypeLayout(layout.achievementPrototype) ||
        !fm::validAchievementLayout(layout.achievement) ||
        !validElementLayout(layout.elements) || !validQualityLayout(layout.quality) || !validTooltipLayout(layout.tooltips, layout.widgetSize))
        return false;
    if (layout.switchCount > 64)
        return false;
    for (uint32_t i = 0; i < layout.switchCount; ++i) {
        const auto &item = layout.switches[i];
        if (!item.table || item.objectSize < layout.widgetSize || item.objectSize > 16 * 1024 * 1024 ||
            !member(item.state, 1, item.objectSize) || !member(item.allowNone, 1, item.objectSize) ||
            item.state == item.allowNone)
            return false;
        for (uint32_t j = 0; j < i; ++j)
            if (layout.switches[j].table == item.table)
                return false;
    }
    if (layout.dropdownCount > 64)
        return false;
    for (uint32_t i = 0; i < layout.dropdownCount; ++i) {
        const auto &item = layout.dropdowns[i];
        if (!item.table || item.objectSize < layout.widgetSize || item.objectSize > 16 * 1024 * 1024 ||
            !member(item.selected, 4, item.objectSize) || !member(item.first, 8, item.objectSize) ||
            !member(item.last, 8, item.objectSize) || item.stride < 8 || item.stride > 4096 ||
            !member(item.button, 8, item.stride))
            return false;
        if ((uint64_t{item.first} < uint64_t{item.last} + 8 && uint64_t{item.last} < uint64_t{item.first} + 8))
            return false;
        for (auto pointer : {item.first, item.last})
            if (uint64_t{item.selected} < uint64_t{pointer} + 8 && uint64_t{pointer} < uint64_t{item.selected} + 4)
                return false;
        for (uint32_t j = 0; j < i; ++j)
            if (layout.dropdowns[j].table == item.table)
                return false;
    }
    if (layout.progressCount > 64)
        return false;
    for (uint32_t i = 0; i < layout.progressCount; ++i) {
        const auto &progress = layout.progress[i];
        if (!progress.table || progress.objectSize < layout.widgetSize || progress.objectSize > 16 * 1024 * 1024 ||
            !member(progress.value, 8, progress.objectSize) || !member(progress.direction, 1, progress.objectSize) ||
            !member(progress.hasText, 1, progress.objectSize) || progress.direction == progress.hasText)
            return false;
        for (const auto flag : {progress.direction, progress.hasText})
            if (flag >= progress.value && uint64_t{flag} < uint64_t{progress.value} + 8)
                return false;
        for (uint32_t j = 0; j < i; ++j)
            if (layout.progress[j].table == progress.table)
                return false;
    }
    if (layout.sliderCount > 64)
        return false;
    for (uint32_t i = 0; i < layout.sliderCount; ++i) {
        const auto &slider = layout.sliders[i];
        if (!slider.table || slider.objectSize < layout.widgetSize || slider.objectSize > 16 * 1024 * 1024)
            return false;
        const uint32_t fields[] = {slider.value, slider.minimum, slider.maximum, slider.step};
        for (uint32_t a = 0; a < 4; ++a) {
            if (!member(fields[a], 8, slider.objectSize))
                return false;
            for (uint32_t b = 0; b < a; ++b)
                if (uint64_t{fields[a]} < uint64_t{fields[b]} + 8 && uint64_t{fields[b]} < uint64_t{fields[a]} + 8)
                    return false;
        }
        for (uint32_t j = 0; j < i; ++j)
            if (layout.sliders[j].table == slider.table)
                return false;
    }
    if (layout.checkCount > 64)
        return false;
    for (uint32_t i = 0; i < layout.checkCount; ++i) {
        const auto &check = layout.checks[i];
        if (!check.table || check.objectSize < layout.widgetSize || check.objectSize > 16 * 1024 * 1024 ||
            !member(check.offset, 4, check.objectSize))
            return false;
        for (uint32_t j = 0; j < i; ++j)
            if (layout.checks[j].table == check.table)
                return false;
    }
    if (!validToggleLayout(layout.toggle) ||
        (layout.toggle.buttonType && layout.toggle.objectSize < layout.widgetSize))
        return false;
    if (!layout.guiSize || !layout.widgetSize || layout.guiSize > 16 * 1024 * 1024 ||
        layout.widgetSize > 16 * 1024 * 1024 || !member(layout.root, 8, layout.guiSize) || !layout.rangeCount ||
        layout.rangeCount > FM_LINUX_MAX_CHILD_RANGES || !validFlag(layout.enabled, layout.widgetSize) ||
        !validFlag(layout.destroying, layout.widgetSize) || !validFlag(layout.visible, layout.widgetSize) ||
        !validFlag(layout.hiddenBySearch, layout.widgetSize) || !validFlag(layout.renderEnabled, layout.widgetSize) ||
        !layout.rectangle.function || !member(layout.rectangle.parent, 8, layout.widgetSize))
        return false;
    for (uint32_t index = 0; index < layout.rangeCount; ++index) {
        const auto &range = layout.ranges[index];
        if (!member(range.begin, 8, layout.widgetSize) || !member(range.end, 8, layout.widgetSize) ||
            range.begin == range.end)
            return false;
    }
    const auto &text = layout.text;
    if (text.count > FM_LINUX_TEXT_GETTERS || text.slot > 4095 || text.data > 4096 || text.length > 4096)
        return false;
    if (text.count && !(text.data + 8 <= text.length || text.length + 8 <= text.data))
        return false;
    for (uint32_t index = 0; index < text.count; ++index) {
        const auto &getter = text.getters[index];
        if (!getter.function || getter.objectSize < layout.widgetSize || getter.objectSize > 16 * 1024 * 1024 ||
            !member(getter.offset, text.data + 8, getter.objectSize) ||
            !member(getter.offset, text.length + 8, getter.objectSize))
            return false;
        for (uint32_t previous = 0; previous < index; ++previous) {
            if (text.getters[previous].function == getter.function)
                return false;
        }
    }
    return true;
}

static int readUi(void *gui, const FmLinuxUiLayout &layout, uint32_t limit, const uint32_t *cancel,
                  UiSnapshot &output, const FmLinuxUiSelector *selector, UiTarget *target) {
    output.nodes.clear();
    output.options.clear();
    output.graphs.clear();
    output.strings.clear();
    output.count = 0;
    output.optionCount = 0;
    output.truncated = 0;
    output.treeTruncated = 0;
    output.textRange = {};
    if (!gui || !cancel || !validUiLayout(layout) || !limit || limit > INT32_MAX ||
        (selector && (selector->count > INT32_MAX || selector->collection > 1 ||
                      selector->optionOffset > INT32_MAX || selector->optionLimit > INT32_MAX ||
                      selector->maxDepth > INT32_MAX || (selector->fields & ~FM_UI_ALL_FIELDS))))
        return EINVAL;
    if (fm_ipc_load(cancel))
        return ECANCELED;
    std::vector<fm::ui::Step> path;
    bool selectorText = false, selectorPrototype = false;
    try {
        if (selector && selector->count) {
            path = fm::ui::selectorPath(selector->path, selector->count);
            for (const auto &step : path) {
                selectorText |= step.text.has_value();
                selectorPrototype |= step.prototypeName.has_value() || step.prototypeType.has_value();
            }
        }
    } catch (...) {
        return EINVAL;
    }
    const uint32_t fields = target ? 0u : selector ? selector->fields : FM_UI_ALL_FIELDS;
    uintptr_t root;
    const auto guiAddress = reinterpret_cast<uintptr_t>(gui);
    if (guiAddress > static_cast<uintptr_t>(INTPTR_MAX) - layout.guiSize || !read(guiAddress + layout.root, root) ||
        !root)
        return EFAULT;
    // No frame or widget pointer survives this callback; the request bounds the traversal.
    std::vector<Frame> stack;
    std::vector<uintptr_t> objects;
    std::unordered_set<uintptr_t> ancestors;
    size_t depth = 0;
    auto append = [&](uintptr_t object, int32_t parent) {
        if (!object || object > static_cast<uintptr_t>(INTPTR_MAX) - layout.widgetSize)
            return false;
        output.nodes.emplace_back();
        auto &node = output.nodes.back();
        node = {};
        node.guiIndex = -1;
        node.parent = parent;
        node.depth = depth;
        if (!flag(object, layout.enabled, node.enabled) || !flag(object, layout.destroying, node.destroying) ||
            !flag(object, layout.visible, node.visible) || !flag(object, layout.hiddenBySearch, node.hiddenBySearch) ||
            !flag(object, layout.renderEnabled, node.renderEnabled) || !typeName(object, node, output) ||
            (selectorText && !text(object, layout.text, node, output)) ||
            (selectorPrototype && identity(object, layout, node, output, FM_UI_PROTOTYPE)))
            return false;
        objects.push_back(object);
        ancestors.insert(object);
        stack.push_back(Frame{object, output.count++});
        ++depth;
        return true;
    };
    if (!append(root, -1))
        return EFAULT;
    while (depth) {
        if (fm_ipc_load(cancel))
            return ECANCELED;
        auto &frame = stack[depth - 1];
        if (frame.range == layout.rangeCount) {
            ancestors.erase(frame.object);
            --depth;
            stack.pop_back();
            continue;
        }
        if (!frame.loaded) {
            const auto &range = layout.ranges[frame.range];
            if (!read(frame.object + range.begin, frame.next) || !read(frame.object + range.end, frame.end) ||
                frame.next > frame.end || (frame.end - frame.next) % sizeof(uintptr_t) ||
                (frame.next && frame.next % alignof(uintptr_t)) || (!frame.next && frame.end))
                return EFAULT;
            frame.loaded = true;
        }
        if (frame.next == frame.end) {
            frame.loaded = false;
            ++frame.range;
            continue;
        }
        if (output.count == (selector && selector->count ? uint32_t(INT32_MAX) : limit)) {
            output.nodes[frame.node].truncated = 1;
            output.treeTruncated = 1;
            break;
        }
        uintptr_t child;
        if (!read(frame.next, child) || !child)
            return EFAULT;
        frame.next += sizeof(uintptr_t);
        if (ancestors.find(child) != ancestors.end())
            return ELOOP;
        if (!append(child, static_cast<int32_t>(frame.node)))
            return EFAULT;
    }
    // Selection and optional properties share this safe point, without retaining pointers or admitting another command.
    if (const int error = prepareScriptGui(objects, selector, cancel, output))
        return error;
    std::vector<int> observed;
    int targetIndex = -1;
    try {
        if (selector && selector->count) {
            std::vector<fm::ui::Node> nodes(output.count);
            for (uint32_t index = 0; index < output.count; ++index) {
                const auto &source = output.nodes[index];
                auto &node = nodes[index];
                node.parent = source.parent;
                node.depth = source.depth;
                node.enabled = source.enabled != 0;
                node.visible = source.visible != 0;
                node.type = {{reinterpret_cast<const char *>(source.type.address), source.type.size}, true, source.typeTruncated != 0};
                node.text = {{reinterpret_cast<const char *>(source.text.address), source.textSize}, source.textAvailable != 0, source.textTruncated != 0};
                const auto &prototype = source.identity.prototype;
                node.prototypeName = {
                    {prototype.name, prototype.nameSize}, (prototype.flags & 1) != 0, (prototype.flags & 2) != 0};
                node.prototypeType = {
                    observationText(prototype.type), (prototype.flags & 1) != 0, false};
                if (!output.guiMatches.empty())
                    node.guiMatches = {output.guiMatches.data() + size_t(index) * selector->count, selector->count};
            }
            const fm::ui::Tree tree(nodes, !output.treeTruncated);
            const auto selected = tree.select(path, [&] { return fm_ipc_load(cancel) != 0; });
            if (target) {
                if (selected.empty())
                    return ENOENT;
                if (selected.size() != 1)
                    return ENOTUNIQ;
                targetIndex = selected.front();
                for (int index = targetIndex; index >= 0; index = output.nodes[index].parent) {
                    if (!output.nodes[index].enabled || output.nodes[index].destroying)
                        return EACCES;
                }
            }
            for (int index : selected)
                output.nodes[index].selected = 1;
            if (selector->collection && selected.size() != 1)
                return selected.empty() ? ENOENT : ENOTUNIQ;
            observed = target ? std::vector<int>{targetIndex} : selector->collection ? selected :
                tree.observation(selected, static_cast<int>(selector->maxDepth));
        } else {
            for (uint32_t index = 0; index < output.count; ++index)
                if (!selector || output.nodes[index].depth <= selector->maxDepth)
                    observed.push_back(index);
        }
    } catch (const fm::ui::Error &error) {
        switch (error.reason) {
        case fm::ui::Failure::incomplete:
            return EOVERFLOW;
        case fm::ui::Failure::canceled:
            return ECANCELED;
        default:
            return EINVAL;
        }
    } catch (...) {
        return ENOMEM;
    }
    output.truncated = output.treeTruncated;
    if (!target && selector && selector->count) {
        std::vector<bool> included(output.nodes.size());
        for (int index : observed)
            included[index] = true;
        observed.clear();
        std::vector<int> pending;
        auto emit = [&] {
            const int index = pending.back();
            pending.pop_back();
            if (included[index])
                observed.push_back(index);
        };
        for (uint32_t index = 0; index < output.count; ++index) {
            while (!pending.empty() && output.nodes[pending.back()].depth >= output.nodes[index].depth)
                emit();
            pending.push_back(index);
        }
        while (!pending.empty())
            emit();
        if (observed.size() > limit) {
            observed.resize(limit);
            output.truncated = 1;
        }
    }
    UiIcons icons(layout.icons);
    for (int index : observed) {
        if (fm_ipc_load(cancel))
            return ECANCELED;
        auto &node = output.nodes[index];
        if ((fields & FM_UI_TEXT) && !selectorText && !text(objects[index], layout.text, node, output)) return EFAULT;
        if (fields & (FM_UI_PROTOTYPE | FM_UI_QUALITY)) {
            if (const int error = identity(objects[index], layout, node, output, fields)) return error;
        }
        if (fields & FM_UI_NUMBER) {
            if (const int error = collectNumber(objects[index], layout.number, node)) return error;
        }
        if (fields & FM_UI_ELEMENT) {
            if (const int error = collectElement(objects[index], layout.elements, node.element, output.strings)) return error;
        }
        if (fields & FM_UI_QUALITY_CONDITION) {
            if (const int error = collectQuality(objects[index], layout.quality, layout.identity, node.quality)) return error;
        }
        if (fields & FM_UI_ICONS) {
            if (const int error = icons.collect(objects[index], node, &layout.iconNames, &output)) return error;
        }
        if (fields & FM_UI_IMAGE) {
            if (const int error = icons.collectImage(objects[index], layout.identity.widgetType, layout.images, node,
                                                    layout.iconNames, output)) return error;
        }
        if (fields & FM_UI_TOOLTIP) collectTooltip(objects[index], layout.tooltips, node, output);
        if ((fields & FM_UI_ACHIEVEMENT) && layout.achievement.supported) {
            const auto receiver = reinterpret_cast<uintptr_t>(abi::__dynamic_cast(reinterpret_cast<const void *>(objects[index]),
                reinterpret_cast<const abi::__class_type_info *>(layout.identity.widgetType),
                reinterpret_cast<const abi::__class_type_info *>(layout.achievement.type), -1));
            if (receiver) {
                uint8_t sidebar;
                if (!fm::addressRange(receiver, layout.achievement.size) ||
                    !read(receiver + layout.achievement.state, node.achievement.state) ||
                    !read(receiver + layout.achievement.shownState, node.achievement.shownState) ||
                    !read(receiver + layout.achievement.inSidebar, sidebar)) return EFAULT;
                if (sidebar > 1) return EPROTO;
                node.achievement.available = 1;
                node.achievement.inSidebar = sidebar;
            }
        }
        if ((fields & FM_UI_GRAPH) && layout.graph.supported) {
            const auto receiver = reinterpret_cast<uintptr_t>(abi::__dynamic_cast(reinterpret_cast<const void *>(objects[index]),
                reinterpret_cast<const abi::__class_type_info *>(layout.identity.widgetType),
                reinterpret_cast<const abi::__class_type_info *>(layout.graph.type), -1));
            if (receiver) {
                if (!fm::addressRange(receiver, layout.graph.size)) return EFAULT;
                auto stored = [&](uintptr_t address) {
                    uintptr_t data;
                    uint64_t length;
                    if (!read(address + layout.text.data, data) || !read(address + layout.text.length, length) || length > INT32_MAX)
                        throw std::runtime_error("Graph text storage is invalid");
                    std::string text(length, '\0');
                    if (length && !readBytes(data, text.data(), length))
                        throw std::runtime_error("Graph text storage is unreadable");
                    return text;
                };
                node.graph = output.graphs.emplace_back().collect(receiver, layout.graph,
                    selector ? selector->graph : FmUiGraphRequest{}, readBytes, stored,
                    [&] {
                        if (fm_ipc_load(cancel)) throw std::runtime_error("Tool cancelled during graph observation");
                    });
            }
        }
        if (const int error = rectangle(objects[index], layout, node.bounds))
            return error;
        if (fields & (FM_UI_TOGGLED | FM_UI_TOGGLE_MODE)) {
            if (const int error = readToggle(objects[index], layout.toggle, node.toggledAvailable, node.toggled, node.toggleMode))
                return error;
        }
        if ((fields & FM_UI_CHECK_STATE) && layout.checkCount) {
            uintptr_t table;
            if (!read(objects[index], table))
                return EFAULT;
            for (uint32_t i = 0; i < layout.checkCount; ++i) {
                const auto &check = layout.checks[i];
                if (table != check.table)
                    continue;
                if (!fm::addressRange(objects[index], check.objectSize) ||
                    !read(objects[index] + check.offset, node.checkState))
                    return EFAULT;
                node.checkAvailable = 1;
                break;
            }
        }
        if ((fields & FM_UI_SLIDER) && layout.sliderCount) {
            uintptr_t table;
            if (!read(objects[index], table))
                return EFAULT;
            for (uint32_t i = 0; i < layout.sliderCount; ++i) {
                const auto &slider = layout.sliders[i];
                if (table != slider.table)
                    continue;
                if (!fm::addressRange(objects[index], slider.objectSize) ||
                    !read(objects[index] + slider.value, node.value) ||
                    !read(objects[index] + slider.minimum, node.minimum) ||
                    !read(objects[index] + slider.maximum, node.maximum) ||
                    !read(objects[index] + slider.step, node.step))
                    return EFAULT;
                node.sliderAvailable = 1;
                break;
            }
        }
        if ((fields & FM_UI_PROGRESS) && layout.progressCount) {
            uintptr_t table;
            if (!read(objects[index], table))
                return EFAULT;
            for (uint32_t i = 0; i < layout.progressCount; ++i) {
                const auto &progress = layout.progress[i];
                if (table != progress.table)
                    continue;
                if (!fm::addressRange(objects[index], progress.objectSize) ||
                    !read(objects[index] + progress.value, node.progressValue) ||
                    !read(objects[index] + progress.direction, node.progressDirection) ||
                    !read(objects[index] + progress.hasText, node.progressHasText))
                    return EFAULT;
                if (node.progressHasText > 1)
                    return EPROTO;
                node.progressAvailable = 1;
                break;
            }
        }
        for (uint32_t i = 0; (fields & FM_UI_SWITCH) && i < layout.switchCount; ++i) {
            const auto &item = layout.switches[i];
            uintptr_t table;
            if (!read(objects[index], table))
                return EFAULT;
            if (table == item.table) {
                if (!fm::addressRange(objects[index], item.objectSize) ||
                    !read(objects[index] + item.state, node.switchState) ||
                    !read(objects[index] + item.allowNone, node.switchAllowNone))
                    return EFAULT;
                if (node.switchAllowNone > 1)
                    return EPROTO;
                node.switchAvailable = 1;
                break;
            }
        }
        if (fields & (FM_UI_SELECTED_INDEX | FM_UI_OPTIONS)) {
            const auto error = dropdown(objects[index], layout, node, output,
                selector && selector->collection ? selector->optionOffset : 0,
                selector && selector->collection ? selector->optionLimit : INT32_MAX, fields);
            if (error) return error;
        }
        output.truncated |= node.textTruncated | node.typeTruncated;
    }
    if (!target)
        observeScriptGui(observed, cancel, output);
    if (!target && selector && selector->textSlice.source) {
        if (!selector->collection || observed.size() != 1) return EINVAL;
        auto &node = output.nodes[observed.front()];
        const auto &request = selector->textSlice;
        try {
            output.textRange = sliceUiText(node, output.options, request, node.textAvailable != 0,
                node.optionsAvailable != 0, output.strings);
            if (output.textRange.available) {
                if (request.source == 1) {
                    node.textSize = node.text.size;
                    node.textTotal = node.text.size;
                } else if (request.source == 4) output.options[node.optionFirst].size = output.options[node.optionFirst].text.size;
            }
        } catch (const std::invalid_argument &) {
            return EINVAL;
        }
    }
    if (fm_ipc_load(cancel))
        return ECANCELED;
    if (target) {
        if (targetIndex < 0 || output.treeTruncated)
            return EOVERFLOW;
        *target = {guiAddress, root, objects[targetIndex], output.nodes[targetIndex].bounds};
    } else if (!output.treeTruncated) {
        std::vector<fm::ui::Node> nodes(output.count);
        for (uint32_t index = 0; index < output.count; ++index) {
            nodes[index].parent = output.nodes[index].parent;
            nodes[index].depth = output.nodes[index].depth;
        }
        const auto retained = fm::ui::Tree(nodes, true).retain(observed);
        std::vector<FmLinuxUiNode> copied;
        copied.reserve(retained.size());
        for (const auto &visit : retained) {
            auto node = output.nodes[visit.source];
            node.parent = visit.parent;
            node.depth = visit.depth;
            node.siblingIndex = visit.siblingIndex;
            node.childrenTruncated = visit.childrenTruncated;
            if (!(fields & FM_UI_TEXT)) {
                node.text = {};
                node.textSize = 0;
                node.textTotal = 0;
                node.textAvailable = 0;
            }
            if (!(fields & FM_UI_PROTOTYPE)) node.identity.prototype = {};
            copied.push_back(node);
        }
        output.nodes = std::move(copied);
        output.count = static_cast<uint32_t>(output.nodes.size());
    }
    return 0;
}

int snapshotUi(void *gui, const FmLinuxUiLayout &layout, uint32_t limit, const uint32_t *cancel,
               UiSnapshot &output, const FmLinuxUiSelector *selector) {
    try {
        return readUi(gui, layout, limit, cancel, output, selector, nullptr);
    } catch (const std::bad_alloc &) {
        return ENOMEM;
    } catch (const std::length_error &) {
        return EOVERFLOW;
    } catch (const std::out_of_range &) {
        return EINVAL;
    } catch (const std::invalid_argument &) {
        return EINVAL;
    } catch (...) {
        return cancel && fm_ipc_load(cancel) ? ECANCELED : EFAULT;
    }
}

int selectUiTarget(void *gui, const FmLinuxUiLayout &layout, const FmLinuxUiSelector &selector, const uint32_t *cancel,
                   UiSnapshot &snapshot, UiTarget &output) {
    output = {};
    if (!selector.count)
        return EINVAL;
    try {
        return readUi(gui, layout, INT32_MAX, cancel, snapshot, &selector, &output);
    } catch (const std::bad_alloc &) {
        return ENOMEM;
    } catch (const std::length_error &) {
        return EOVERFLOW;
    } catch (const std::out_of_range &) {
        return EINVAL;
    } catch (const std::invalid_argument &) {
        return EINVAL;
    } catch (...) {
        return cancel && fm_ipc_load(cancel) ? ECANCELED : EFAULT;
    }
}

static int findLiveUiTarget(uintptr_t guiInstance, const FmLinuxUiLayout &layout, const UiTarget &target) {
    if (target.reference) {
        if (const int error = target.reference->validateIdentity(target.gui, target.root, target.widget))
            return error;
    }
    if (!guiInstance || !target.gui || !target.root || !target.widget || !validUiLayout(layout))
        return EINVAL;
    uintptr_t gui, root;
    if (!read(guiInstance, gui))
        return EFAULT;
    if (gui != target.gui)
        return ESTALE;
    if (!fm::addressRange(gui, layout.guiSize) || !read(gui + layout.root, root))
        return EFAULT;
    if (root != target.root)
        return ESTALE;
    std::vector<Frame> stack;
    size_t depth = 1;
    uint32_t count = 1;
    bool found = false;
    stack.push_back(Frame{root, 0});
    std::unordered_set<uintptr_t> ancestors{root};
    auto inspect = [&](uintptr_t widget) {
        if (!widget || !fm::addressRange(widget, layout.widgetSize))
            return EFAULT;
        if (widget == target.widget) {
            uint32_t destroying;
            if (!flag(widget, layout.destroying, destroying))
                return EFAULT;
            found = !destroying;
        }
        return 0;
    };
    if (const int error = inspect(root))
        return error;
    // No text, geometry or widget callback is needed to revalidate membership. Cleanup must ignore cancellation.
    while (depth) {
        auto &frame = stack[depth - 1];
        if (frame.range == layout.rangeCount) {
            ancestors.erase(frame.object);
            --depth;
            stack.pop_back();
            continue;
        }
        if (!frame.loaded) {
            const auto &range = layout.ranges[frame.range];
            if (!read(frame.object + range.begin, frame.next) || !read(frame.object + range.end, frame.end) ||
                frame.next > frame.end || (frame.end - frame.next) % sizeof(uintptr_t) ||
                (frame.next && frame.next % alignof(uintptr_t)) || (!frame.next && frame.end))
                return EFAULT;
            frame.loaded = true;
        }
        if (frame.next == frame.end) {
            frame.loaded = false;
            ++frame.range;
            continue;
        }
        if (count == INT32_MAX)
            return E2BIG;
        uintptr_t child;
        if (!read(frame.next, child))
            return EFAULT;
        frame.next += sizeof(uintptr_t);
        if (!ancestors.insert(child).second)
            return ELOOP;
        if (const int error = inspect(child))
            return error;
        ++count;
        stack.push_back(Frame{child, 0});
        ++depth;
    }
    return found ? 0 : ENOENT;
}

int liveUiTarget(uintptr_t guiInstance, const FmLinuxUiLayout &layout, const UiTarget &target) {
    try {
        return findLiveUiTarget(guiInstance, layout, target);
    } catch (const std::bad_alloc &) {
        return ENOMEM;
    } catch (const std::length_error &) {
        return EOVERFLOW;
    }
}

int refreshUiTarget(uintptr_t guiInstance, const FmLinuxUiLayout &layout, UiTarget &target) {
    if (const int error = liveUiTarget(guiInstance, layout, target))
        return error;
    FmLinuxRectangle bounds{};
    if (const int error = rectangle(target.widget, layout, bounds))
        return error;
    if (const int error = liveUiTarget(guiInstance, layout, target))
        return error;
    target.bounds = bounds;
    return 0;
}
