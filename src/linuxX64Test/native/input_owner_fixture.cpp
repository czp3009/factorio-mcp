#include <cassert>
#include <cstddef>
#include <cstdint>

extern "C" void fixture_input_owner(const void*);
extern "C" void fixture_player_initialize(void*);
extern "C" unsigned fixture_input_owner_calls;

struct Map;
struct Game;

struct Player {
    std::uint64_t padding[FIXTURE_PADDING];
    Map* map;
    __attribute__((noinline)) explicit Player(Map& value) {
        fixture_player_initialize(this);
        map = &value;
    }
};

struct View {
    std::uint64_t padding[FIXTURE_PADDING + 4];
    Player* player;
};

struct Game {
    std::uint64_t padding[FIXTURE_PADDING + 1];
    Player* player;
    View* view;
};

struct Map {
    std::uint64_t padding[FIXTURE_PADDING + 2];
    Game* game;
};

struct Source {
    std::uint64_t padding[FIXTURE_PADDING + 3];
    Player* player;
    __attribute__((noinline)) void connect(Player* value) { player = value; }
    __attribute__((noinline)) void evaluate() {
        if (!player) return;
        auto* game = player->map->game;
        if (!game || game->player != player) return;
        fixture_input_owner(this);
    }
    __attribute__((noinline)) void evaluateView() {
        if (!player) return;
        auto* game = player->map->game;
        if (!game || !game->view || game->view->player != player) return;
        fixture_input_owner(this);
    }
};

extern "C" {
extern const std::size_t fixture_source_size = sizeof(Source);
extern const std::size_t fixture_player_size = sizeof(Player);
extern const std::size_t fixture_map_size = sizeof(Map);
extern const std::size_t fixture_game_size = sizeof(Game);
extern const std::size_t fixture_view_size = sizeof(View);
extern const std::size_t fixture_source_player = offsetof(Source, player);
extern const std::size_t fixture_player_map = offsetof(Player, map);
extern const std::size_t fixture_map_game = offsetof(Map, game);
extern const std::size_t fixture_game_player = offsetof(Game, player);
extern const std::size_t fixture_game_view = offsetof(Game, view);
extern const std::size_t fixture_view_player = offsetof(View, player);
}

int main() {
    Game game{};
    Map map{};
    map.game = &game;
    Player player(map);
    Source source{};
    source.connect(&player);
    game.player = &player;
    source.evaluate();
    assert(fixture_input_owner_calls == 1);
    game.player = nullptr;
    source.evaluate();
    map.game = nullptr;
    source.evaluate();
    source.connect(nullptr);
    source.evaluate();
    assert(fixture_input_owner_calls == 1);
    View view{};
    source.connect(&player);
    map.game = &game;
    game.view = &view;
    source.evaluateView();
    assert(fixture_input_owner_calls == 1);
    view.player = &player;
    source.evaluateView();
    assert(fixture_input_owner_calls == 2);
    game.view = nullptr;
    source.evaluateView();
    map.game = nullptr;
    source.evaluateView();
    source.connect(nullptr);
    source.evaluateView();
    assert(fixture_input_owner_calls == 2);
}
