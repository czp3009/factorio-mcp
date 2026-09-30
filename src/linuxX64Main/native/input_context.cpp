#include "input_context.h"
#include "linux_ipc.h"
#include "memory_read.h"
#include <cerrno>
#include <initializer_list>

namespace {
bool object(uintptr_t address, uint32_t size) {
    return fm::addressRange(address, size) && address % alignof(uintptr_t) == 0;
}

bool pointerMember(uint32_t offset, uint32_t size) {
    return offset % sizeof(uintptr_t) == 0 && fm::member(offset, sizeof(uintptr_t), size);
}

bool separate(uint32_t first, uint32_t width, uint32_t second, uint32_t otherWidth) {
    return static_cast<uint64_t>(first) + width <= second || static_cast<uint64_t>(second) + otherWidth <= first;
}

bool table(uintptr_t address, uintptr_t type) {
    return address >= 2 * sizeof(uintptr_t) && object(address, sizeof(uintptr_t)) && object(type, 16);
}

int header(uintptr_t table, uintptr_t type) {
    uintptr_t adjustment, actual;
    if (!fm::read(table - 16, adjustment) || !fm::read(table - 8, actual))
        return EFAULT;
    return adjustment || actual != type ? ESTALE : 0;
}

int identity(uintptr_t pointer, uint32_t size, uintptr_t expected, uintptr_t type) {
    uintptr_t actual;
    if (!pointer)
        return ENOENT;
    if (!object(pointer, size) || !fm::read(pointer, actual))
        return EFAULT;
    if (actual != expected)
        return ENOTSUP;
    return header(actual, type);
}
} // namespace

bool validInputContextConfig(const FmLinuxInputContextConfig &config) {
    const auto &world = config.world;
    const auto &script = config.script;
    const auto &input = config.input;
    if (!validWorldLayout(world) || !validScriptLayout(script) || world.contextSize != script.contextSize)
        return false;
    for (auto size : {input.sourceSize, input.playerSize, input.mapSize, input.viewSize, input.handlerSize}) {
        if (size < 8 || size > 64 * 1024 * 1024)
            return false;
    }
    if (!table(input.sourceVtable, input.sourceTypeInfo) || !table(input.playerVtable, input.playerTypeInfo) ||
        !table(input.viewVtable, input.viewTypeInfo) || !table(input.handlerVtable, input.handlerTypeInfo) ||
        !pointerMember(input.globalSource, world.globalSize) ||
        !separate(input.globalSource, 8, world.scenario, 8) ||
        input.sourcePlayer < 8 || !pointerMember(input.sourcePlayer, input.sourceSize) ||
        input.playerMap < 8 || !pointerMember(input.playerMap, input.playerSize) ||
        !pointerMember(input.mapGame, input.mapSize) || !pointerMember(input.gameView, world.gameSize) ||
        !pointerMember(input.gameSource, world.gameSize) || input.gameSource == input.gameView ||
        input.viewPlayer < 8 || !pointerMember(input.viewPlayer, input.viewSize) ||
        input.scriptMap < 8 || !pointerMember(input.scriptMap, script.scriptSize) ||
        !separate(input.scriptMap, 8, script.name + script.stringData, 8) ||
        !separate(input.scriptMap, 8, script.name + script.stringLength, 8) ||
        !separate(input.scriptMap, 8, script.state, 8) ||
        !separate(input.scriptMap, 8, script.loading, 1) || !separate(input.scriptMap, 8, script.enabled, 1) ||
        !pointerMember(input.mapTick, input.mapSize) ||
        !fm::member(input.mapStop, 1, input.mapSize) || !fm::member(input.mapPaused, 1, input.mapSize) ||
        !separate(input.mapTick, 8, input.mapGame, 8) ||
        !separate(input.mapStop, 1, input.mapGame, 8) || !separate(input.mapStop, 1, input.mapTick, 8) ||
        !separate(input.mapPaused, 1, input.mapGame, 8) || !separate(input.mapPaused, 1, input.mapTick, 8) ||
        input.mapStop == input.mapPaused || input.handlerMap < 8 || input.handlerSource < 8 ||
        !pointerMember(input.handlerMap, input.handlerSize) || !pointerMember(input.handlerSource, input.handlerSize) ||
        input.handlerMap == input.handlerSource || !input.handlerCount ||
        input.handlerCount > FM_LINUX_INPUT_HANDLER_CANDIDATES)
        return false;
    for (uint32_t index = 0; index < input.handlerCount; ++index) {
        if (!pointerMember(input.handlers[index], world.gameSize) || input.handlers[index] == input.gameView ||
            input.handlers[index] == input.gameSource)
            return false;
        for (uint32_t previous = 0; previous < index; ++previous) {
            if (input.handlers[previous] == input.handlers[index])
                return false;
        }
    }
    return true;
}

int readInputContext(const FmLinuxInputContextConfig &config, const uint32_t *cancel, InputContext &output) {
    output = {};
    if (!cancel || !validInputContextConfig(config))
        return EINVAL;
    WorldObjects world;
    if (const int error = readWorldObjects(config.world, cancel, world))
        return error;
    ScriptObjects script;
    if (const int error = readDefaultScript(world.context, config.script, cancel, script))
        return error;
    const auto &layout = config.input;
    InputContext found;
    found.game = world.game;
    if (!fm::read(world.global + layout.globalSource, found.source))
        return EFAULT;
    if (const int error = identity(found.source, layout.sourceSize, layout.sourceVtable, layout.sourceTypeInfo))
        return error;
    uintptr_t gameSource;
    if (!fm::read(found.game + layout.gameSource, gameSource))
        return EFAULT;
    if (gameSource != found.source)
        return ESTALE;
    if (!fm::read(found.source + layout.sourcePlayer, found.player))
        return EFAULT;
    if (const int error = identity(found.player, layout.playerSize, layout.playerVtable, layout.playerTypeInfo))
        return error;
    uintptr_t viewPlayer;
    if (!fm::read(found.game + layout.gameView, found.view))
        return EFAULT;
    if (const int error = identity(found.view, layout.viewSize, layout.viewVtable, layout.viewTypeInfo))
        return error;
    if (!fm::read(found.view + layout.viewPlayer, viewPlayer))
        return EFAULT;
    if (viewPlayer != found.player)
        return ESTALE;
    if (!fm::read(found.player + layout.playerMap, found.map))
        return EFAULT;
    if (!found.map)
        return ENOENT;
    uintptr_t game, scriptMap;
    if (!object(found.map, layout.mapSize) || !fm::read(found.map + layout.mapGame, game) ||
        !fm::read(script.script + layout.scriptMap, scriptMap))
        return EFAULT;
    if (game != found.game || scriptMap != found.map)
        return ESTALE;
    if (const int error = header(layout.handlerVtable, layout.handlerTypeInfo))
        return error;
    uintptr_t handler = 0;
    for (uint32_t index = 0; index < layout.handlerCount; ++index) {
        if (fm_ipc_load(cancel))
            return ECANCELED;
        uintptr_t candidate, table;
        if (!fm::read(found.game + layout.handlers[index], candidate))
            return EFAULT;
        if (!candidate)
            continue;
        if (!object(candidate, 8) || !fm::read(candidate, table))
            return EFAULT;
        if (table != layout.handlerVtable)
            continue;
        if (!object(candidate, layout.handlerSize))
            return EFAULT;
        if (handler)
            return ENOTUNIQ;
        handler = candidate;
    }
    if (!handler)
        return ENOTSUP;
    uintptr_t handlerMap, handlerSource;
    if (!fm::read(handler + layout.handlerMap, handlerMap) || !fm::read(handler + layout.handlerSource, handlerSource))
        return EFAULT;
    if (handlerMap != found.map || handlerSource != found.source)
        return ESTALE;
    uint8_t paused;
    if (!fm::read(found.map + layout.mapTick, found.tick) || !fm::read(found.map + layout.mapStop, found.stopped) ||
        !fm::read(found.map + layout.mapPaused, paused))
        return EFAULT;
    if (paused > 1)
        return EPROTO;
    found.paused = paused != 0;
    if (fm_ipc_load(cancel))
        return ECANCELED;
    output = found;
    return 0;
}
