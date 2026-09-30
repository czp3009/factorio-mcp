#include <atomic>
#include <cassert>
#include <cstddef>
#include <cstdint>

extern "C" bool fixture_lifetime_remaining();
extern "C" void fixture_lifetime_work();
extern "C" unsigned fixture_lifetime_calls;

struct GarbageCollectable {
    __attribute__((noinline)) virtual void flagForDelete() { ++fixture_lifetime_calls; }
    __attribute__((noinline)) virtual void flagForDeleteOnMainThread() { ++fixture_lifetime_calls; }
    virtual ~GarbageCollectable() = default;
};

struct GameView : GarbageCollectable {
    std::uint64_t padding[FIXTURE_PADDING]{};
    __attribute__((noinline)) void unloadGui() { fixture_lifetime_work(); }
};

struct Game {
    std::uint64_t padding[FIXTURE_PADDING + 2]{};
    GameView *view = nullptr;
    std::atomic<unsigned> references{1};
    __attribute__((noinline)) ~Game() {
        auto *captured = view;
        if (captured) {
            captured->unloadGui();
            while (fixture_lifetime_remaining()) fixture_lifetime_work();
            // Force the captured view to spill without exposing its storage to a callee.
            asm volatile("" ::: "rbx", "r12", "r13", "r14", "r15");
            captured->flagForDelete();
            view = nullptr;
        }
        if (references.fetch_sub(1) != 1) fixture_lifetime_work();
    }
};

extern "C" {
extern const std::size_t fixture_game_size = sizeof(Game);
extern const std::size_t fixture_view_size = sizeof(GameView);
extern const std::size_t fixture_game_view = offsetof(Game, view);
}

int main() {
    GameView view;
    {
        Game game;
        game.view = &view;
    }
    assert(fixture_lifetime_calls == 1);
    { Game empty; }
    assert(fixture_lifetime_calls == 1);
}
