#include "targeter.h"
#include "memory_read.h"
#include <cerrno>

namespace {
bool record(uintptr_t address, const FmLinuxTargeterLayout &layout, TargeterLinks &output) {
    return address % alignof(uintptr_t) == 0 && fm::addressRange(address, layout.targeterExtent) &&
        fm::read(address + layout.target, output.target) &&
        fm::read(address + layout.previous, output.previous) &&
        fm::read(address + layout.next, output.next);
}

bool head(uintptr_t address, const FmLinuxTargeterLayout &layout, uintptr_t &output) {
    return address % alignof(uintptr_t) == 0 && fm::addressRange(address, layout.targetableExtent) &&
        fm::read(address + layout.head, output);
}

bool separate(uintptr_t first, uint32_t firstSize, uintptr_t second, uint32_t secondSize) {
    return fm::addressRange(first, firstSize) && fm::addressRange(second, secondSize) &&
        (first + firstSize <= second || second + secondSize <= first);
}
} // namespace

bool validTargeterLayout(const FmLinuxTargeterLayout &layout) {
    if (!layout.release || !layout.targeterExtent || layout.targeterExtent > 256 ||
        !layout.targetableExtent || layout.targetableExtent > 256)
        return false;
    const uint32_t members[] = {layout.target, layout.previous, layout.next};
    for (const auto member : members) {
        if (member % alignof(uintptr_t) || !fm::member(member, sizeof(uintptr_t), layout.targeterExtent))
            return false;
    }
    return layout.target != layout.previous && layout.target != layout.next && layout.previous != layout.next &&
        layout.head % alignof(uintptr_t) == 0 && fm::member(layout.head, sizeof(uintptr_t), layout.targetableExtent);
}

int readTargeter(uintptr_t address, const FmLinuxTargeterLayout &layout, TargeterLinks &output) {
    output = {};
    if (!validTargeterLayout(layout))
        return EINVAL;
    TargeterLinks links;
    if (!record(address, layout, links))
        return EFAULT;
    if (!links.target) {
        if (links.previous || links.next)
            return EPROTO;
        return 0;
    }
    if (links.previous == address || links.next == address ||
        (links.previous && links.previous == links.next))
        return ELOOP;
    uintptr_t first;
    if (!head(links.target, layout, first))
        return EFAULT;
    if (!separate(address, layout.targeterExtent, links.target, layout.targetableExtent))
        return EPROTO;
    if (!first || (links.previous ? first == address : first != address))
        return EPROTO;
    TargeterLinks neighbor;
    if (links.previous) {
        if (!record(links.previous, layout, neighbor))
            return EFAULT;
        if (!separate(address, layout.targeterExtent, links.previous, layout.targeterExtent) ||
            !separate(links.target, layout.targetableExtent, links.previous, layout.targeterExtent))
            return EPROTO;
        if (neighbor.target != links.target || neighbor.next != address)
            return EPROTO;
    }
    if (links.next) {
        if (!record(links.next, layout, neighbor))
            return EFAULT;
        if (!separate(address, layout.targeterExtent, links.next, layout.targeterExtent) ||
            !separate(links.target, layout.targetableExtent, links.next, layout.targeterExtent) ||
            (links.previous && !separate(links.previous, layout.targeterExtent, links.next, layout.targeterExtent)))
            return EPROTO;
        if (neighbor.target != links.target || neighbor.previous != address)
            return EPROTO;
    }
    links.first = first;
    output = links;
    return 0;
}

int releaseTargeter(uintptr_t address, const FmLinuxTargeterLayout &layout) {
    TargeterLinks before;
    if (const int error = readTargeter(address, layout, before))
        return error;
    if (!before.target)
        return 0;
    // The metadata proof admits only this null-target call and excludes callbacks or dispatch.
    using Release = void (*)(void *, void *);
    try {
        reinterpret_cast<Release>(layout.release)(reinterpret_cast<void *>(address), nullptr);
    } catch (...) {
        return EFAULT;
    }
    TargeterLinks after;
    if (!record(address, layout, after))
        return EFAULT;
    if (after.target || after.previous || after.next)
        return EPROTO;
    uintptr_t first;
    if (!head(before.target, layout, first))
        return EFAULT;
    if (first != (before.previous ? before.first : before.next))
        return EPROTO;
    uintptr_t forward = first;
    if (before.previous) {
        if (!record(before.previous, layout, after))
            return EFAULT;
        if (after.target != before.target)
            return EPROTO;
        forward = after.next;
    }
    if (forward != before.next)
        return EPROTO;
    if (before.next) {
        if (!record(before.next, layout, after))
            return EFAULT;
        if (after.target != before.target || after.previous != before.previous)
            return EPROTO;
    }
    return 0;
}

int attachFreshTargeter(uintptr_t address, uintptr_t target, const FmLinuxTargeterLayout &layout) {
    TargeterLinks before;
    if (const int error = readTargeter(address, layout, before))
        return error;
    if (before.target)
        return EALREADY;
    uintptr_t first;
    if (!head(target, layout, first))
        return EFAULT;
    if (!separate(address, layout.targeterExtent, target, layout.targetableExtent))
        return EPROTO;
    if (first) {
        TargeterLinks neighbor;
        if (const int error = readTargeter(first, layout, neighbor))
            return error;
        if (neighbor.target != target || neighbor.previous ||
            !separate(address, layout.targeterExtent, first, layout.targeterExtent))
            return EPROTO;
    }
    using Assign = void (*)(void *, void *);
    try {
        reinterpret_cast<Assign>(layout.release)(reinterpret_cast<void *>(address), reinterpret_cast<void *>(target));
    } catch (...) {
        return EFAULT;
    }
    TargeterLinks after;
    if (const int error = readTargeter(address, layout, after))
        return error;
    if (after.target != target || after.previous || after.next != first || after.first != address)
        return EPROTO;
    return 0;
}

int TargetReference::attach(uintptr_t target, const FmLinuxTargeterLayout &layout) {
    if (owned_)
        return EALREADY;
    if (!validTargeterLayout(layout) || layout.targeterExtent > storage_.size())
        return EINVAL;
    storage_.fill(0);
    layout_ = layout;
    // Even a throwing assignment may already have linked this storage into the target's list.
    owned_ = true;
    return attachFreshTargeter(reinterpret_cast<uintptr_t>(storage_.data()), target, layout_);
}

int TargetReference::borrow(uintptr_t &target) const {
    target = 0;
    if (!owned_)
        return 0;
    TargeterLinks links;
    if (const int error = readTargeter(reinterpret_cast<uintptr_t>(storage_.data()), layout_, links))
        return error;
    target = links.target;
    return 0;
}

int TargetReference::release() {
    if (!owned_)
        return 0;
    if (const int error = releaseTargeter(reinterpret_cast<uintptr_t>(storage_.data()), layout_))
        return error;
    owned_ = false;
    layout_ = {};
    storage_.fill(0);
    return 0;
}
