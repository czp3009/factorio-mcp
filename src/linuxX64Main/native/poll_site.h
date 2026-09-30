#pragma once
#include <stdint.h>

typedef struct FmLinuxPollHookConfig {
    uint64_t entry;
    uint64_t original;
    uint64_t table;
    uint64_t caller;
    uint64_t pumpCaller;
    uint32_t frameReturn;
    int32_t eventFromFrame;
    uint32_t eventExtent;
    uint32_t protection;
} FmLinuxPollHookConfig;

#ifdef __cplusplus
bool validPollSite(const FmLinuxPollHookConfig &site);
bool matchesPollSite(const FmLinuxPollHookConfig &site, void *receiver, void *event,
                     uintptr_t caller, uintptr_t frame);
bool samePollSite(const FmLinuxPollHookConfig &left, const FmLinuxPollHookConfig &right);
#endif
