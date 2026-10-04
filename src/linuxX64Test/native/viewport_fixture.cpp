#include "viewport.h"
#include <cassert>
#include <cerrno>
#include <cstring>

#ifndef FIXTURE_PADDING
#define FIXTURE_PADDING 1
#endif

namespace {
int dimensionCalls = 0, positionCalls = 0;

struct Bitmap {
    uintptr_t marker = 7;
    int32_t width = 640, height = 480;
};

struct Framebuffer {
    virtual ~Framebuffer() = default;

    virtual int32_t width() {
        ++dimensionCalls;
        assert(primary || fallback);
        return (primary ? primary : fallback)->width;
    }

    virtual int32_t height() {
        ++dimensionCalls;
        assert(primary || fallback);
        return (primary ? primary : fallback)->height;
    }

    char padding[FIXTURE_PADDING]{};
    Bitmap *primary = nullptr, *fallback = nullptr;
};

struct Renderer {
    char padding[FIXTURE_PADDING]{};
    Framebuffer **reference = nullptr;
};

struct Player {
    virtual ~Player() = default;
    uint16_t index = 6;
};

struct View {
    virtual ~View() = default;
    char padding[FIXTURE_PADDING]{};
    Player *player = nullptr;
    Renderer *renderer = nullptr;
    uint32_t surface = 4;
    int32_t position[2]{-2560, 5120};
};

struct Game {
    Player *player;
    View *view;
};

uint32_t offset(const void *object, const void *member) {
    return static_cast<const char *>(member) - static_cast<const char *>(object);
}

void identity(const void *object, uint64_t &table, uint64_t &type) {
    std::memcpy(&table, object, sizeof(table));
    std::memcpy(&type, reinterpret_cast<const void *>(table - 8), sizeof(type));
}

uint64_t position(void *object, uint64_t pixel) {
    ++positionCalls;
    const auto &view = *static_cast<View *>(object);
    int32_t input[2];
    std::memcpy(input, &pixel, sizeof(input));
    const int32_t result[2]{view.position[0] + (input[0] - 320) * 256, view.position[1] + (input[1] - 240) * 256};
    uint64_t packed;
    std::memcpy(&packed, result, sizeof(packed));
    return packed;
}
} // namespace

int main() {
    Player player;
    Bitmap bitmap, fallback;
    fallback.width = 800;
    fallback.height = 600;
    Framebuffer framebuffer;
    framebuffer.primary = &bitmap;
    Framebuffer *pointer = &framebuffer;
    Renderer renderer;
    renderer.reference = &pointer;
    View view;
    view.player = &player;
    view.renderer = &renderer;
    Game game{&player, &view};
    FmLinuxPlayerLayout selection{};
    selection.gameSize = sizeof(game);
    selection.playerSize = sizeof(player);
    selection.viewSize = sizeof(view);
    selection.gamePlayer = offset(&game, &game.player);
    selection.gameView = offset(&game, &game.view);
    selection.viewPlayer = offset(&view, &view.player);
    selection.index = offset(&player, &player.index);
    selection.indexWidth = sizeof(player.index);
    identity(&view, selection.viewVtable, selection.viewTypeInfo);
    identity(&player, selection.playerVtable, selection.playerTypeInfo);
    FmLinuxViewportLayout layout{};
    identity(&framebuffer, layout.framebufferVtable, layout.framebufferTypeInfo);
    // The synthetic fixture declares two destructor slots followed by its two dimension methods.
    layout.widthSlot = 2;
    layout.heightSlot = 3;
    const auto table = reinterpret_cast<const uintptr_t *>(layout.framebufferVtable);
    layout.width = table[layout.widthSlot];
    layout.height = table[layout.heightSlot];
    layout.mapPosition = reinterpret_cast<uintptr_t>(&position);
    layout.renderer = offset(&view, &view.renderer);
    layout.rendererSize = sizeof(renderer);
    layout.framebufferReference = offset(&renderer, &renderer.reference);
    layout.framebufferSize = sizeof(framebuffer);
    layout.primary = offset(&framebuffer, &framebuffer.primary);
    layout.fallback = offset(&framebuffer, &framebuffer.fallback);
    layout.surface = offset(&view, &view.surface);
    layout.position = offset(&view, &view.position);
    layout.fractionBits = 8;
    uint32_t cancel = 0;
    QueryViewport result;
    auto run = [&] {
        return readViewport(reinterpret_cast<uintptr_t>(&game), reinterpret_cast<uintptr_t>(&player), selection, layout,
                            &cancel, result);
    };
    assert(run() == 0 && dimensionCalls == 2 && positionCalls == 2 && result.surface == 5 && result.width == 640 &&
           result.height == 480 && result.left == -330 && result.top == -220 && result.right == 310 &&
           result.bottom == 260);
    cancel = 1;
    assert(run() == ECANCELED && dimensionCalls == 2 && positionCalls == 2 && result.width == 0);
    cancel = 0;
    framebuffer.primary = nullptr;
    assert(run() == ENOENT && dimensionCalls == 2 && positionCalls == 2);
    framebuffer.fallback = &fallback;
    assert(run() == 0 && result.width == 800 && result.height == 600);
    fallback.width = 1;
    assert(run() == ERANGE && positionCalls == 4);
    fallback.width = 800;
    layout.width += 1;
    assert(run() == ESTALE && positionCalls == 4);
    layout.width -= 1;
    view.surface = UINT32_MAX;
    assert(run() == ENOENT && positionCalls == 4);
    view.surface = 4;
    view.position[0] = INT32_MAX;
    assert(run() == ENOENT && positionCalls == 4);
    view.position[0] = -2560;
    view.player = nullptr;
    assert(run() == ENOENT && positionCalls == 4);
    view.player = &player;
    renderer.reference = reinterpret_cast<Framebuffer **>(1);
    assert(run() == EFAULT && positionCalls == 4);
    renderer.reference = &pointer;
    layout.position = selection.viewSize - 4;
    assert(run() == EINVAL && positionCalls == 4);
    layout.position = offset(&view, &view.position);
    InputPosition pixel{};
    assert(projectWorldInput(reinterpret_cast<uintptr_t>(&game), reinterpret_cast<uintptr_t>(&player), selection,
                             layout, {-9.5, 20.5}, pixel) == 0);
    assert(pixel.x == 320 && pixel.y == 240);
}
