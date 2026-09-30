#include "player_objects.h"
#include <array>
#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <sys/mman.h>
#include <unistd.h>

static void require(bool value, const char *message) {
    if (!value) {
        std::fprintf(stderr, "factorio-mcp player objects fixture: %s\n", message);
        std::abort();
    }
}

struct Player {
    virtual ~Player() = default;
    std::array<char, 27> padding{};
    uint16_t index = 0;
};

struct View {
    virtual ~View() = default;
    std::array<char, 43> padding{};
    Player *player = nullptr;
};

struct Game {
    std::array<char, 13> padding{};
    Player *player = nullptr;
    std::array<char, 7> middle{};
    View *view = nullptr;
};

static uint32_t offset(const void *object, const void *member) {
    return static_cast<const char *>(member) - static_cast<const char *>(object);
}

static uintptr_t pointer(const void *address) {
    uintptr_t value;
    std::memcpy(&value, address, sizeof(value));
    return value;
}

int main() {
    alarm(30);
    Player direct, indirect;
    direct.index = 65535;
    indirect.index = 237;
    View view;
    view.player = &indirect;
    Game game;
    game.player = &direct;
    game.view = &view;
    FmLinuxPlayerLayout layout{};
    layout.playerVtable = pointer(&direct);
    layout.playerTypeInfo = pointer(reinterpret_cast<void *>(layout.playerVtable - sizeof(uintptr_t)));
    layout.viewVtable = pointer(&view);
    layout.viewTypeInfo = pointer(reinterpret_cast<void *>(layout.viewVtable - sizeof(uintptr_t)));
    layout.gameSize = sizeof(game);
    layout.playerSize = sizeof(direct);
    layout.viewSize = sizeof(view);
    layout.gamePlayer = offset(&game, &game.player);
    layout.gameView = offset(&game, &game.view);
    layout.viewPlayer = offset(&view, &view.player);
    layout.index = offset(&direct, &direct.index);
    layout.indexWidth = sizeof(direct.index);
    uint32_t cancel = 0;
    PlayerObjects output;
    auto read = [&] { return readLocalPlayer(reinterpret_cast<uintptr_t>(&game), layout, &cancel, output); };
    auto rejected = [&](int error, const char *message) {
        output = {1, 1};
        require(read() == error, message);
        require(!output.player && !output.index, "failed read retained a previous frame's player");
    };
    require(read() == 0 && output.player == reinterpret_cast<uintptr_t>(&direct) && output.index == 65535,
            "direct player or unsigned index was lost");
    game.player = nullptr;
    require(read() == 0 && output.player == reinterpret_cast<uintptr_t>(&indirect) && output.index == 237,
            "GameView fallback was not read afresh");
    indirect.index = 0;
    require(read() == 0 && output.index == 0, "native index zero was treated as absent");
    view.player = nullptr;
    rejected(ENOENT, "empty view reported a player");
    game.view = nullptr;
    rejected(ENOENT, "empty game reported a player");
    game.view = &view;
    view.player = &indirect;
    cancel = 1;
    rejected(ECANCELED, "cancellation ignored");
    cancel = 0;
    const size_t pageSize = sysconf(_SC_PAGESIZE);
    void *guard = mmap(nullptr, pageSize, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    require(guard != MAP_FAILED, "guard mapping failed");
    game.view = static_cast<View *>(guard);
    rejected(EFAULT, "unreadable view accepted");
    game.player = &direct;
    require(read() == 0 && output.index == 65535, "unused view was dereferenced despite direct-player priority");
    game.player = static_cast<Player *>(guard);
    rejected(EFAULT, "unreadable direct player fell back to the view");
    game.view = &view;
    game.player = reinterpret_cast<Player *>(&view);
    rejected(ENOTSUP, "unrelated object accepted as Player");
    game.player = nullptr;
    game.view = reinterpret_cast<View *>(&direct);
    rejected(ENOTSUP, "unrelated object accepted as GameView");
    game.view = &view;
    game.player = &direct;
    const auto original = layout;
    layout.playerTypeInfo += sizeof(uintptr_t);
    rejected(ESTALE, "mismatched player RTTI accepted");
    layout = original;
    layout.index = layout.playerSize - 1;
    rejected(EINVAL, "index crossing the object bound accepted");
    layout = original;
    layout.gameView = layout.gamePlayer;
    rejected(EINVAL, "overlapping Game members accepted");
    layout = original;
    layout.indexWidth = 4;
    rejected(EINVAL, "unverified index width accepted");
    layout = original;
    uintptr_t secondary[] = {8, layout.playerTypeInfo, 0};
    uintptr_t forged[] = {reinterpret_cast<uintptr_t>(secondary + 2), 0, 0, 0, 0, 0};
    game.player = reinterpret_cast<Player *>(forged);
    layout.playerVtable = forged[0];
    rejected(ESTALE, "secondary vtable accepted as primary");
    require(munmap(guard, pageSize) == 0, "guard cleanup failed");
}
