#include "ui_snapshot.h"
#include "ui_reference.h"
#include "ui_number.h"
#include "ui_icons.h"
#include "linux_ipc.h"
#include "memory_read.h"
#include "widget_text.h"
#include <algorithm>
#include "../../nativeMain/native/widget_selection.h"
#include <array>
#include <cerrno>
#include <cstdlib>
#include <cstring>
#include <cxxabi.h>

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

bool typeName(uintptr_t object, FmLinuxUiNode &node) {
    uintptr_t table, type, name;
    // These are primary Itanium ABI table/RTTI fields, not game member locations.
    if (!read(object, table) || table < sizeof(uintptr_t) || !read(table - sizeof(uintptr_t), type) || !type ||
        type > INTPTR_MAX - sizeof(uintptr_t) || !read(type + sizeof(uintptr_t), name) || !name)
        return false;
    if (name > INTPTR_MAX - FM_LINUX_TYPE_NAME)
        return false;
    char encoded[FM_LINUX_TYPE_NAME];
    size_t length = 0;
    for (; length + 1 < sizeof(encoded); ++length) {
        if (!read(name + length, encoded[length]))
            return false;
        if (!encoded[length])
            break;
    }
    encoded[length] = 0;
    node.typeTruncated = length + 1 == sizeof(encoded);
    int status = -1;
    char *demangled = node.typeTruncated ? nullptr : abi::__cxa_demangle(encoded, nullptr, nullptr, &status);
    const char *text = status == 0 && demangled ? demangled : encoded;
    const size_t size = strnlen(text, sizeof(node.type));
    const size_t copied = size < sizeof(node.type) ? size : sizeof(node.type) - 1;
    std::memcpy(node.type, text, copied);
    node.type[copied] = 0;
    node.typeTruncated |= size >= sizeof(node.type);
    std::free(demangled);
    return true;
}

struct Frame {
    uintptr_t object;
    uint32_t node;
    uint32_t range = 0;
    uintptr_t next = 0;
    uintptr_t end = 0;
    bool loaded = false;
};

bool text(uintptr_t object, const FmLinuxTextLayout &layout, FmLinuxUiNode &node) {
    WidgetTextView view;
    if (!widgetTextView(object, layout, view))
        return false;
    if (!view.available)
        return true;
    const auto count = view.length < sizeof(node.text) ? static_cast<size_t>(view.length) : sizeof(node.text);
    if (count && !readBytes(view.data, node.text, count))
        return false;
    node.textAvailable = 1;
    node.textSize = count;
    node.textTotal = view.length;
    node.textTruncated = count < view.length;
    return true;
}

int dropdown(uintptr_t object, const FmLinuxUiLayout &layout, FmLinuxUiNode &node, FmLinuxUiSnapshot &output) {
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
        if (!fm::addressRange(object, item.objectSize) || !read(object + item.selected, node.selectedIndex) ||
            !read(object + item.first, first) || !read(object + item.last, last))
            return EFAULT;
        if (last < first || (last - first) % item.stride || (last - first) / item.stride > 65536 ||
            (last != first && !fm::addressRange(first, last - first)))
            return EPROTO;
        node.dropdownAvailable = 1;
        node.optionTotal = (last - first) / item.stride;
        node.optionFirst = output.optionCount;
        node.optionCount =
            std::min<uint32_t>({node.optionTotal, FM_LINUX_WIDGET_OPTIONS, FM_LINUX_MAX_OPTIONS - output.optionCount});
        for (uint32_t optionIndex = 0; optionIndex < node.optionCount; ++optionIndex) {
            uintptr_t button;
            if (!read(first + optionIndex * item.stride + item.button, button) || !button)
                return EFAULT;
            WidgetTextView view;
            if (!widgetTextView(button, layout.text, view))
                return EFAULT;
            if (!view.available) {
                output.optionCount = node.optionFirst;
                node.optionCount = 0;
                return 0;
            }
            auto &option = output.options[output.optionCount];
            auto count = std::min<uint64_t>(view.length, FM_LINUX_OPTION_TEXT - 1);
            const auto readCount = count + (count < view.length ? 1 : 0);
            if (readCount && !readBytes(view.data, option.text, readCount))
                return EFAULT;
            if (count < view.length)
                while (count && (static_cast<unsigned char>(option.text[count]) & 0xc0) == 0x80)
                    --count;
            option.text[count] = 0;
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
    std::array<uintptr_t, 256> parents{};
    size_t count = 0;
    auto current = object;
    while (current) {
        if (count == parents.size())
            return E2BIG;
        for (size_t index = 0; index < count; ++index) {
            if (parents[index] == current)
                return ELOOP;
        }
        if (current > static_cast<uintptr_t>(INTPTR_MAX) - layout.widgetSize)
            return EFAULT;
        parents[count++] = current;
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
    if (!validNumberLayout(layout.number) || !validIconLayout(layout.icons) || !validIdentityLayout(layout.identity) ||
        !validElementLayout(layout.elements) || !validQualityLayout(layout.quality))
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
    if (layout.toggle.function &&
        (!layout.toggle.tableCount || layout.toggle.tableCount > 1024 || layout.toggle.slot > 4095 ||
         layout.toggle.objectSize < layout.widgetSize || layout.toggle.objectSize > 16 * 1024 * 1024 ||
         !member(layout.toggle.offset, 1, layout.toggle.objectSize) ||
         !member(layout.toggle.modeOffset, 1, layout.toggle.objectSize)))
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
                  FmLinuxUiSnapshot &output, const FmLinuxUiSelector *selector, UiTarget *target) {
    output.count = 0;
    output.optionCount = 0;
    output.truncated = 0;
    output.treeTruncated = 0;
    if (!gui || !cancel || !validUiLayout(layout) || !limit || limit > FM_LINUX_MAX_NODES ||
        (selector && selector->count > FM_LINUX_UI_PATH))
        return EINVAL;
    if (fm_ipc_load(cancel))
        return ECANCELED;
    uintptr_t root;
    const auto guiAddress = reinterpret_cast<uintptr_t>(gui);
    if (guiAddress > static_cast<uintptr_t>(INTPTR_MAX) - layout.guiSize || !read(guiAddress + layout.root, root) ||
        !root)
        return EFAULT;
    // No frame or widget pointer survives this callback; both depth and total work are bounded.
    std::array<Frame, 256> stack{};
    std::array<uintptr_t, FM_LINUX_MAX_NODES> objects{};
    size_t depth = 0;
    uint32_t visited = 0;
    auto append = [&](uintptr_t object, int32_t parent) {
        if (!object || object > static_cast<uintptr_t>(INTPTR_MAX) - layout.widgetSize)
            return false;
        auto &node = output.nodes[output.count];
        node = {};
        node.parent = parent;
        node.depth = depth;
        if (!flag(object, layout.enabled, node.enabled) || !flag(object, layout.destroying, node.destroying) ||
            !flag(object, layout.visible, node.visible) || !flag(object, layout.hiddenBySearch, node.hiddenBySearch) ||
            !flag(object, layout.renderEnabled, node.renderEnabled) || !typeName(object, node) ||
            !text(object, layout.text, node) || collectIdentity(object, layout.identity, node.identity))
            return false;
        objects[output.count] = object;
        stack[depth++] = Frame{object, output.count++};
        return true;
    };
    if (!append(root, -1))
        return EFAULT;
    while (depth) {
        if (fm_ipc_load(cancel))
            return ECANCELED;
        auto &frame = stack[depth - 1];
        if (frame.range == layout.rangeCount) {
            --depth;
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
        if (output.count == limit || visited++ == FM_LINUX_MAX_NODES) {
            output.nodes[frame.node].truncated = 1;
            output.treeTruncated = 1;
            break;
        }
        uintptr_t child;
        if (!read(frame.next, child) || !child)
            return EFAULT;
        frame.next += sizeof(uintptr_t);
        for (size_t ancestor = 0; ancestor < depth; ++ancestor) {
            if (stack[ancestor].object == child)
                return ELOOP;
        }
        if (depth == stack.size()) {
            output.nodes[frame.node].truncated = 1;
            output.treeTruncated = 1;
            continue;
        }
        if (!append(child, static_cast<int32_t>(frame.node)))
            return EFAULT;
    }
    // Selection and optional properties share this safe point, without retaining pointers or admitting another command.
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
                node.type = {fm::ui::terminated(source.type), true, source.typeTruncated != 0};
                node.text = {{source.text, source.textSize}, source.textAvailable != 0, source.textTruncated != 0};
                const auto &prototype = source.identity.prototype;
                node.prototypeName = {
                    {prototype.name, prototype.nameSize}, (prototype.flags & 1) != 0, (prototype.flags & 2) != 0};
                node.prototypeType = {
                    {prototype.type, prototype.typeSize}, (prototype.flags & 1) != 0, (prototype.flags & 4) != 0};
            }
            const fm::ui::Tree tree(nodes, !output.treeTruncated);
            std::vector<fm::ui::Step> path;
            for (uint32_t index = 0; index < selector->count; ++index) {
                const auto &source = selector->path[index];
                fm::ui::require(source.enabled >= -1 && source.enabled <= 1, "Invalid enabled predicate");
                fm::ui::Step step;
                step.child = source.child != 0;
                step.position = source.position;
                if (source.enabled >= 0)
                    step.enabled = source.enabled != 0;
                if (source.hasVisible)
                    step.visible = source.visible != 0;
                if (source.type[0])
                    step.type = fm::ui::terminated(source.type);
                if (source.hasText)
                    step.text = fm::ui::terminated(source.text);
                if (source.prototypeName[0])
                    step.prototypeName = fm::ui::terminated(source.prototypeName);
                if (source.prototypeType[0])
                    step.prototypeType = fm::ui::terminated(source.prototypeType);
                path.push_back(step);
            }
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
            observed = target ? std::vector<int>{targetIndex} : tree.observation(selected);
        } else {
            for (uint32_t index = 0; index < output.count; ++index)
                observed.push_back(index);
        }
    } catch (const fm::ui::Error &error) {
        switch (error.reason) {
        case fm::ui::Failure::incomplete:
            return EOVERFLOW;
        case fm::ui::Failure::workLimit:
            return E2BIG;
        case fm::ui::Failure::canceled:
            return ECANCELED;
        default:
            return EINVAL;
        }
    } catch (...) {
        return ENOMEM;
    }
    output.truncated = output.treeTruncated;
    UiIcons icons(layout.icons);
    for (int index : observed) {
        if (fm_ipc_load(cancel))
            return ECANCELED;
        auto &node = output.nodes[index];
        if (const int error = collectNumber(objects[index], layout.number, node))
            return error;
        if (const int error = collectElement(objects[index], layout.elements, node.element))
            return error;
        if (const int error = collectQuality(objects[index], layout.quality, layout.identity, node.quality))
            return error;
        if (const int error = icons.collect(objects[index], node))
            return error;
        if (const int error = rectangle(objects[index], layout, node.bounds))
            return error;
        if (layout.toggle.function) {
            uintptr_t table, entry;
            const uintptr_t offset = uintptr_t{layout.toggle.slot} * sizeof(uintptr_t);
            if (!read(objects[index], table))
                return EFAULT;
            bool known = false;
            for (uint32_t i = 0; i < layout.toggle.tableCount; ++i)
                known |= layout.toggle.tables[i] == table;
            if (known) {
                if (!fm::addressRange(table, offset + sizeof(uintptr_t)) || !read(table + offset, entry))
                    return EFAULT;
            }
            if (known && entry == layout.toggle.function) {
                unsigned char mode, value;
                if (!fm::addressRange(objects[index], layout.toggle.objectSize) ||
                    !read(objects[index] + layout.toggle.modeOffset, mode))
                    return EFAULT;
                if (mode > 1)
                    return EPROTO;
                if (mode) {
                    if (!read(objects[index] + layout.toggle.offset, value))
                        return EFAULT;
                    if (value > 1)
                        return EPROTO;
                    node.toggledAvailable = 1;
                    node.toggled = value;
                }
            }
        }
        if (layout.checkCount) {
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
        if (layout.sliderCount) {
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
        if (layout.progressCount) {
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
        for (uint32_t i = 0; i < layout.switchCount; ++i) {
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
        const auto dropdownResult = dropdown(objects[index], layout, node, output);
        if (dropdownResult)
            return dropdownResult;
        output.truncated |= node.textTruncated | node.typeTruncated;
    }
    if (target) {
        if (targetIndex < 0 || output.treeTruncated)
            return EOVERFLOW;
        *target = {guiAddress, root, objects[targetIndex], output.nodes[targetIndex].bounds};
    }
    return 0;
}

int snapshotUi(void *gui, const FmLinuxUiLayout &layout, uint32_t limit, const uint32_t *cancel,
               FmLinuxUiSnapshot &output, const FmLinuxUiSelector *selector) {
    return readUi(gui, layout, limit, cancel, output, selector, nullptr);
}

int selectUiTarget(void *gui, const FmLinuxUiLayout &layout, const FmLinuxUiSelector &selector, const uint32_t *cancel,
                   FmLinuxUiSnapshot &snapshot, UiTarget &output) {
    output = {};
    if (!selector.count)
        return EINVAL;
    return readUi(gui, layout, FM_LINUX_MAX_NODES, cancel, snapshot, &selector, &output);
}

int liveUiTarget(uintptr_t guiInstance, const FmLinuxUiLayout &layout, const UiTarget &target) {
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
    std::array<Frame, 256> stack{};
    size_t depth = 1;
    uint32_t count = 1;
    bool found = false;
    stack[0] = Frame{root, 0};
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
            --depth;
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
        if (count == FM_LINUX_MAX_NODES || depth == stack.size())
            return E2BIG;
        uintptr_t child;
        if (!read(frame.next, child))
            return EFAULT;
        frame.next += sizeof(uintptr_t);
        for (size_t ancestor = 0; ancestor < depth; ++ancestor)
            if (stack[ancestor].object == child)
                return ELOOP;
        if (const int error = inspect(child))
            return error;
        ++count;
        stack[depth++] = Frame{child, 0};
    }
    return found ? 0 : ENOENT;
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
