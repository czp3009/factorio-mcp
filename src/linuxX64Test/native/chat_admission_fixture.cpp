#include "chat_fixture_world.h"
#include <cassert>
#include <cerrno>
#include <thread>

int main() {
    chat_fixture::World world;
    auto &[player, replacement, view, handler, context, game, map, scenario, global, root, config] = world;
    uint32_t cancel = 0;
    ChatAdmission admission(config, &cancel);
    void *found = nullptr;
    assert(ChatAdmission::resolve(&admission, found) == 0 && found == &player);
    for (const auto allowed : {0u, 16u, 18u, UINT32_MAX}) {
        handler.field = allowed;
        assert(ChatAdmission::resolve(&admission, found) == 0 && found == &player);
    }
    handler.field = config.excluded;
    assert(ChatAdmission::resolve(&admission, found) == EACCES && !found);
    handler.field = 0;
    game.player = &replacement;
    assert(ChatAdmission::resolve(&admission, found) == 0 && found == &replacement);
    game.player = nullptr;
    assert(ChatAdmission::resolve(&admission, found) == 0 && found == &player);
    cancel = 1;
    assert(ChatAdmission::resolve(&admission, found) == ECANCELED && !found);
    cancel = 0;
    map.game = nullptr;
    assert(ChatAdmission::resolve(&admission, found) == ESTALE && !found);
    map.game = &game;
    game.handler = nullptr;
    assert(ChatAdmission::resolve(&admission, found) == ENOENT && !found);
    game.handler = reinterpret_cast<chat_fixture::Handler *>(&context);
    assert(ChatAdmission::resolve(&admission, found) == ENOTSUP && !found);
    game.handler = &handler;
    std::thread foreign([&] {
        void *output = &player;
        assert(ChatAdmission::resolve(&admission, output) == EPERM && !output);
    });
    foreign.join();
    config.handlerField = config.handlerSize;
    ChatAdmission invalid(config, &cancel);
    assert(ChatAdmission::resolve(&invalid, found) == EINVAL && !found);
}
