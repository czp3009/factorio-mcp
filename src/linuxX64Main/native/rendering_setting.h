#pragma once
#include "../../nativeMain/native/rendering_state.h"

typedef struct FmLinuxRenderingSetting {
    uint64_t table;
    uint32_t size, member, nameData, nameLength;
} FmLinuxRenderingSetting;

#ifdef __cplusplus
#include <cstring>

// Validate the live named bool member before the guard consumes its value.
template <class Read>
bool validRenderingSetting(const FmRenderingState &state, const FmLinuxRenderingSetting &config, Read read) {
    const auto range = [](uint64_t address, uint32_t size) {
        return address >= 4096 && size && address <= UINTPTR_MAX - size;
    };
    if (state.globalSize < 8 || state.globalSize > 64 * 1024 * 1024 || state.settingsSize < 8 ||
        state.settingsSize > 64 * 1024 * 1024 || state.settings > state.globalSize - 8 || state.settings % 8 ||
        !range(state.global, 8) || state.global % 8 || !range(config.table, 8) || config.table % 8 ||
        config.size < 8 || config.size > state.settingsSize || config.member < 8 || config.member % 8 ||
        config.member > state.settingsSize - config.size || config.nameData > config.size - 8 ||
        config.nameLength > config.size - 8 || config.nameData == config.nameLength)
        return false;
    uint64_t global, settings, table, data, length;
    if (!read(state.global, global) || !range(global, state.globalSize) || global % 8 ||
        !read(global + state.settings, settings))
        return false;
    if (!settings)
        return true;
    if (!range(settings, state.settingsSize) || settings % 8 ||
        !read(settings, table) || table != state.settingsTable)
        return false;
    const auto item = settings + config.member;
    static constexpr char key[] = "halt-rendering-when-minimized";
    char name[sizeof(key)];
    return read(item, table) && table == config.table &&
        read(item + config.nameLength, length) && length == sizeof(key) - 1 &&
        read(item + config.nameData, data) && range(data, sizeof(key)) &&
        read(data, name) && std::memcmp(name, key, sizeof(key)) == 0;
}
#endif
