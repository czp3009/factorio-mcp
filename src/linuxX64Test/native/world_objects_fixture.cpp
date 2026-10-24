#include "world_objects.h"
#include <array>
#include <cerrno>
#include <cstddef>
#include <cstdio>
#include <cstdlib>
#include <sys/mman.h>
#include <unistd.h>

static void require(bool value, const char *message) {
    if (!value) {
        std::fprintf(stderr, "factorio-mcp world objects fixture: %s\n", message);
        std::abort();
    }
}

struct alignas(void *) Context {
    std::array<char, 41> data{};
};

struct alignas(void *) Game {
    std::array<char, 77> data{};
};

struct Scenario {
    std::array<char, 29> padding{};
    Game *game = nullptr;
    Context *context = nullptr;
    void *unrelated = nullptr;
};

struct Global {
    std::array<char, 13> padding{};
    Scenario *scenario = nullptr;
};

int main() {
    alarm(30);
    Context context, anotherContext;
    Game game;
    Scenario scenario{{}, &game, &context, reinterpret_cast<void *>(1)};
    Global global{{}, &scenario};
    Global *root = &global;
    FmLinuxWorldLayout layout{};
    layout.global = reinterpret_cast<uintptr_t>(&root);
    layout.globalSize = sizeof(Global);
    layout.scenarioSize = sizeof(Scenario);
    layout.gameSize = sizeof(Game);
    layout.contextSize = sizeof(Context);
    layout.scenario = offsetof(Global, scenario);
    layout.game = offsetof(Scenario, game);
    layout.context = offsetof(Scenario, context);
    uint32_t cancel = 0;
    WorldObjects output;
    auto read = [&] { return readWorldObjects(layout, &cancel, output); };
    auto rejected = [&](int error, const char *message) {
        // A failed read must not expose a previous frame's references or a partially resolved object graph.
        output = {1, 2, 3, 4};
        require(read() == error, message);
        require(!output.scenario && !output.game && !output.context && !output.global,
                "failure retained object pointers");
    };
    require(read() == 0 && output.scenario == reinterpret_cast<uintptr_t>(&scenario) &&
                output.game == reinterpret_cast<uintptr_t>(&game) &&
                output.context == reinterpret_cast<uintptr_t>(&context) &&
                output.global == reinterpret_cast<uintptr_t>(&global),
            "typed nonpolymorphic context was not resolved");
    scenario.context = &anotherContext;
    require(read() == 0 && output.context == reinterpret_cast<uintptr_t>(&anotherContext),
            "context cached across calls");
    scenario.context = nullptr;
    rejected(EAGAIN, "absent context was accepted");
    scenario.context = &context;
    root = nullptr;
    rejected(ENOENT, "absent global was not distinguished");
    root = &global;
    global.scenario = nullptr;
    rejected(ENOENT, "absent scenario was not distinguished");
    global.scenario = &scenario;
    scenario.game = nullptr;
    rejected(EAGAIN, "absent game was accepted");
    scenario.game = &game;
    cancel = 1;
    rejected(ECANCELED, "cancellation ignored");
    cancel = 0;
    const size_t pageSize = sysconf(_SC_PAGESIZE);
    void *guard = mmap(nullptr, pageSize, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    require(guard != MAP_FAILED, "guard mapping failed");
    root = static_cast<Global *>(guard);
    rejected(EFAULT, "unreadable global was accepted");
    root = &global;
    global.scenario = static_cast<Scenario *>(guard);
    rejected(EFAULT, "unreadable scenario was accepted");
    global.scenario = &scenario;
    const auto original = layout;
    layout.context = layout.scenarioSize;
    rejected(EINVAL, "out-of-bounds context member was accepted");
    layout = original;
    layout.context += 1;
    rejected(EINVAL, "unaligned context member was accepted");
    layout = original;
    layout.context = layout.game;
    rejected(EINVAL, "game/context alias was accepted");
    layout = original;
    layout.scenario = layout.globalSize;
    rejected(EINVAL, "out-of-bounds global member was accepted");
    layout = original;
    scenario.context = reinterpret_cast<Context *>(UINTPTR_MAX - 7);
    rejected(EFAULT, "wrapping context pointer was accepted");
    scenario.context = reinterpret_cast<Context *>(reinterpret_cast<uintptr_t>(&context) + 1);
    rejected(EFAULT, "unaligned context pointer was accepted");
    scenario.context = &context;
    require(munmap(guard, pageSize) == 0, "guard mapping cleanup failed");
    require(read() == 0, "rejected read prevented later valid read");
    std::puts("factorio-mcp world objects fixture: passed");
}
