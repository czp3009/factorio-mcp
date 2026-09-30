#include "poll_site.h"
#include "keyboard_event.h"
#include "memory_read.h"
#include <sys/mman.h>

bool validPollSite(const FmLinuxPollHookConfig &site) {
    return fm::addressRange(site.table, 8) && site.table % 8 == 0 && fm::addressRange(site.entry, 8) &&
        site.entry >= site.table && site.entry - site.table <= 32768 && site.entry % 8 == 0 &&
        fm::addressRange(site.original, 1) && fm::addressRange(site.caller, 1) &&
        fm::addressRange(site.pumpCaller, 1) && site.frameReturn <= 16384 && site.frameReturn % 8 == 0 &&
        site.eventFromFrame >= -16384 && site.eventFromFrame <= 16384 &&
        site.eventExtent > 0 && site.eventExtent <= FM_LINUX_EVENT_BYTES &&
        (site.protection == PROT_READ || site.protection == (PROT_READ | PROT_WRITE));
}

bool samePollSite(const FmLinuxPollHookConfig &a, const FmLinuxPollHookConfig &b) {
    return a.entry == b.entry && a.original == b.original && a.table == b.table && a.caller == b.caller &&
        a.pumpCaller == b.pumpCaller && a.frameReturn == b.frameReturn && a.eventFromFrame == b.eventFromFrame &&
        a.eventExtent == b.eventExtent && a.protection == b.protection;
}

bool matchesPollSite(const FmLinuxPollHookConfig &site, void *receiver, void *event, uintptr_t caller, uintptr_t frame) {
    if (caller != site.caller || !validPollSite(site) || !receiver || !event || frame % 8 ||
        !fm::addressRange(frame, static_cast<size_t>(site.frameReturn) + 8))
        return false;
    uintptr_t outer, table;
    if (!fm::read(frame + site.frameReturn, outer) || outer != site.pumpCaller ||
        !fm::read(reinterpret_cast<uintptr_t>(receiver), table) || table != site.table)
        return false;
    const int64_t relative = site.eventFromFrame;
    if (relative < 0 && frame < static_cast<uint64_t>(-relative))
        return false;
    if (relative >= 0 && frame > static_cast<uintptr_t>(INTPTR_MAX) - relative)
        return false;
    const auto expected = relative < 0 ? frame - static_cast<uintptr_t>(-relative) : frame + relative;
    return reinterpret_cast<uintptr_t>(event) == expected && fm::addressRange(expected, site.eventExtent);
}
