#include "chat_admission.h"
#include "linux_ipc.h"
#include "memory_read.h"
#include <cerrno>
#include <sys/syscall.h>
#include <unistd.h>

namespace {
bool object(uintptr_t address, uint32_t size) {
    return size >= 8 && size <= 64 * 1024 * 1024 && address % 8 == 0 && fm::addressRange(address, size);
}

bool member(uint32_t offset, uint32_t size) {
    return offset % 8 == 0 && fm::member(offset, 8, size);
}
}

ChatAdmission::ChatAdmission(const FmLinuxChatAdmissionConfig &config, const uint32_t *cancel)
    : config_(config), cancel_(cancel) {}

int ChatAdmission::resolve(void *context, void *&player) noexcept {
    player = nullptr;
    if (syscall(SYS_gettid) != getpid())
        return EPERM;
    if (!context)
        return EINVAL;
    return static_cast<ChatAdmission *>(context)->read(player);
}

int ChatAdmission::read(void *&player) const {
    const auto &c = config_;
    if (!cancel_ || !validWorldLayout(c.world) || !validPlayerLayout(c.player) ||
        c.world.gameSize != c.player.gameSize || c.mapSize < 8 || c.mapSize > 64 * 1024 * 1024 ||
        c.handlerSize < 8 || c.handlerSize > 64 * 1024 * 1024 ||
        !member(c.playerMap, c.player.playerSize) || c.playerMap < 8 ||
        !member(c.mapGame, c.mapSize) || !member(c.gameHandler, c.world.gameSize) ||
        c.gameHandler == c.player.gamePlayer || c.gameHandler == c.player.gameView ||
        c.handlerField < 8 || !fm::member(c.handlerField, 4, c.handlerSize) ||
        c.handlerVtable < 16 || !object(c.handlerVtable, 8) || !object(c.handlerTypeInfo, 16))
        return EINVAL;
    WorldObjects world;
    if (const int error = readWorldObjects(c.world, cancel_, world))
        return error;
    PlayerObjects current;
    if (const int error = readLocalPlayer(world.game, c.player, cancel_, current))
        return error;
    uintptr_t map, game, handler, table, adjustment, type;
    if (!fm::read(current.player + c.playerMap, map) || !object(map, c.mapSize) ||
        !fm::read(map + c.mapGame, game))
        return EFAULT;
    if (game != world.game)
        return ESTALE;
    if (!fm::read(game + c.gameHandler, handler))
        return EFAULT;
    if (!handler)
        return ENOENT;
    if (!object(handler, c.handlerSize) || !fm::read(handler, table))
        return EFAULT;
    if (table != c.handlerVtable)
        return ENOTSUP;
    if (!fm::read(table - 16, adjustment) || !fm::read(table - 8, type))
        return EFAULT;
    if (adjustment || type != c.handlerTypeInfo)
        return ESTALE;
    uint32_t value;
    if (!fm::read(handler + c.handlerField, value))
        return EFAULT;
    if (value == c.excluded)
        return EACCES;
    if (fm_ipc_load(cancel_))
        return ECANCELED;
    player = reinterpret_cast<void *>(current.player);
    return 0;
}
