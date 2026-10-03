#include "game_state.h"
#include "linux_ipc.h"
#include "memory_read.h"
#include <cerrno>

namespace {
bool object(uintptr_t address, uint32_t size = sizeof(uintptr_t)) {
    return address % alignof(uintptr_t) == 0 && fm::addressRange(address, size);
}

bool size(uint32_t value) {
    return value >= sizeof(uintptr_t) && value <= 64 * 1024 * 1024;
}

bool predicate(const FmLinuxVirtualBoolean &value) {
    return object(value.table) && value.table >= 2 * sizeof(uintptr_t) && object(value.typeInfo) &&
        fm::addressRange(value.function, 1) && value.adjustment <= 16 * 1024 * 1024 && value.slot < 256 &&
        fm::addressRange(value.table, (value.slot + 1) * sizeof(uintptr_t));
}

int observe(const FmLinuxVirtualBoolean &entry, uintptr_t receiver, uint8_t &value) {
    uintptr_t table, type, function;
    intptr_t adjustment;
    if (!object(receiver) || !fm::read(receiver, table))
        return EFAULT;
    if (table != entry.table)
        return ESTALE;
    if (!fm::read(table - 2 * sizeof(uintptr_t), adjustment) ||
        !fm::read(table - sizeof(uintptr_t), type) || !fm::read(table + entry.slot * sizeof(uintptr_t), function))
        return EFAULT;
    if (adjustment != -static_cast<intptr_t>(entry.adjustment) || type != entry.typeInfo || function != entry.function)
        return ESTALE;
    try {
        // The game's own verified interface dispatch supplies RDI and consumes the raw AL boolean.
        value = reinterpret_cast<uint8_t (*)(const void *)>(function)(reinterpret_cast<const void *>(receiver));
    } catch (...) {
        return EIO;
    }
    return value <= 1 ? 0 : ERANGE;
}

int loading(const FmLinuxGameStateConfig &config, uintptr_t global, const uint32_t *cancel, bool &value) {
    value = false;
    uintptr_t manager;
    if (!fm::read(global + config.appManager, manager))
        return EFAULT;
    if (manager) {
        uintptr_t begin, end;
        if (!object(manager, config.appManagerSize) || !fm::read(manager + config.statesBegin, begin) ||
            !fm::read(manager + config.statesEnd, end))
            return EFAULT;
        if (end < begin || (end - begin) % sizeof(uintptr_t) ||
            (end - begin) / sizeof(uintptr_t) > FM_LINUX_LOADING_STATES || (begin != end && !object(begin)))
            return ERANGE;
        for (uintptr_t cursor = begin; cursor < end; cursor += sizeof(uintptr_t)) {
            if (fm_ipc_load(cancel))
                return ECANCELED;
            uintptr_t state, table;
            if (!fm::read(cursor, state) || !object(state) || !fm::read(state, table))
                return EFAULT;
            const FmLinuxVirtualBoolean *selected = nullptr;
            for (uint32_t index = 0; index < config.stateCount; ++index) {
                if (config.states[index].table == table) {
                    selected = &config.states[index];
                    break;
                }
            }
            if (!selected)
                return ENOTSUP;
            uint8_t observed;
            const int result = observe(*selected, state, observed);
            if (result)
                return result;
            if (observed) {
                value = true;
                return 0;
            }
        }
    }
    // Preserve the native first-nonnull manager selection, including a false result from that manager.
    for (uint32_t index = 0; index < config.managerCount;) {
        const uint32_t member = config.managers[index].member;
        uint32_t end = index + 1;
        while (end < config.managerCount && config.managers[end].member == member)
            ++end;
        uintptr_t receiver;
        if (!fm::read(global + member, receiver))
            return EFAULT;
        if (receiver) {
            uintptr_t table;
            if (!object(receiver) || !fm::read(receiver, table))
                return EFAULT;
            for (uint32_t candidate = index; candidate < end; ++candidate) {
                const auto &binding = config.managers[candidate];
                if (binding.primary != table)
                    continue;
                if (!object(receiver, binding.size))
                    return EFAULT;
                uint8_t observed;
                const int result = observe(binding.predicate, receiver + binding.predicate.adjustment, observed);
                if (result)
                    return result;
                value = observed != 0;
                return 0;
            }
            return ENOTSUP;
        }
        index = end;
    }
    return 0;
}
} // namespace

bool validGameStateConfig(const FmLinuxGameStateConfig &config) {
    if (!object(config.global) || !size(config.globalSize) || !size(config.scenarioSize) ||
        !size(config.gameSize) || !size(config.mapSize) || !size(config.appManagerSize) ||
        !fm::member(config.scenario, sizeof(uintptr_t), config.globalSize) ||
        !fm::member(config.game, sizeof(uintptr_t), config.scenarioSize) ||
        !fm::member(config.map, sizeof(uintptr_t), config.gameSize) || !fm::member(config.paused, 1, config.mapSize) ||
        !fm::member(config.stopped, 1, config.mapSize) || config.paused == config.stopped ||
        !fm::member(config.appManager, sizeof(uintptr_t), config.globalSize) ||
        !fm::member(config.statesBegin, sizeof(uintptr_t), config.appManagerSize) ||
        !fm::member(config.statesEnd, sizeof(uintptr_t), config.appManagerSize) || config.statesBegin == config.statesEnd ||
        !config.stateCount || config.stateCount > FM_LINUX_LOADING_STATES ||
        !config.managerCount || config.managerCount > FM_LINUX_MULTIPLAYER_BINDINGS)
        return false;
    for (uint32_t index = 0; index < config.stateCount; ++index) {
        if (!predicate(config.states[index]))
            return false;
        for (uint32_t previous = 0; previous < index; ++previous)
            if (config.states[previous].table == config.states[index].table)
                return false;
    }
    for (uint32_t index = 0; index < config.managerCount; ++index) {
        const auto &binding = config.managers[index];
        if (!predicate(binding.predicate) || !object(binding.primary) || !size(binding.size) ||
            !fm::member(binding.member, sizeof(uintptr_t), config.globalSize) ||
            !fm::member(binding.predicate.adjustment, sizeof(uintptr_t), binding.size))
            return false;
        for (uint32_t previous = 0; previous < index; ++previous) {
            if (config.managers[previous].member == binding.member &&
                (config.managers[previous].primary == binding.primary || config.managers[index - 1].member != binding.member))
                return false;
        }
    }
    return true;
}

int readGameState(const FmLinuxGameStateConfig &config, const uint32_t *cancel, FmLinuxGameState &output) {
    output = {0, -1};
    if (!cancel || !validGameStateConfig(config))
        return EINVAL;
    if (fm_ipc_load(cancel))
        return ECANCELED;
    uintptr_t global;
    if (!fm::read(config.global, global) || !object(global, config.globalSize))
        return EFAULT;
    bool isLoading;
    const int result = loading(config, global, cancel, isLoading);
    if (result)
        return result;
    if (isLoading) {
        output = {3, 0};
        return 0;
    }
    uintptr_t scenario, game = 0;
    if (!fm::read(global + config.scenario, scenario))
        return EFAULT;
    if (scenario && (!object(scenario, config.scenarioSize) || !fm::read(scenario + config.game, game)))
        return EFAULT;
    if (!game) {
        output = {1, 0};
        return 0;
    }
    uintptr_t map;
    if (!object(game, config.gameSize) || !fm::read(game + config.map, map) || !object(map, config.mapSize))
        return EFAULT;
    uint8_t paused, stopped;
    if (!fm::read(map + config.paused, paused) || !fm::read(map + config.stopped, stopped))
        return EFAULT;
    if (paused > 1)
        return ERANGE;
    output.paused = paused || stopped;
    output.state = output.paused ? 4 : 2;
    return 0;
}
