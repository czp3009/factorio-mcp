#include "world_objects.h"
#include <array>
#include <cerrno>
#include <cstddef>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <sys/mman.h>
#include <unistd.h>

static void require(bool value, const char *message) {
    if (!value) {
        std::fprintf(stderr, "factorio-mcp world objects fixture: %s\n", message);
        std::abort();
    }
}

struct Context {
    virtual ~Context() = default;
    std::array<char, 41> data{};
};

struct Other {
    virtual ~Other() = default;
};

struct alignas(void *) Game { std::array<char, 77> data{}; };
struct Scenario {
    std::array<char, 29> padding{};
    Game *game = nullptr;
    void *children[3]{};
};
struct Global {
    std::array<char, 13> padding{};
    Scenario *scenario = nullptr;
};

int main() {
    alarm(30);
    Context context, anotherContext;
    Other other;
    Game game;
    Scenario scenario{{}, &game, {&other, &context, nullptr}};
    Global global{{}, &scenario};
    Global *root = &global;
    uintptr_t table;
    std::memcpy(&table, static_cast<const void *>(&context), sizeof(table));
    uintptr_t typeInfo;
    std::memcpy(&typeInfo, reinterpret_cast<void *>(table - sizeof(uintptr_t)), sizeof(typeInfo));
    FmLinuxWorldLayout layout{};
    layout.global = reinterpret_cast<uintptr_t>(&root);
    layout.contextVtable = table;
    layout.contextTypeInfo = typeInfo;
    layout.globalSize = sizeof(Global);
    layout.scenarioSize = sizeof(Scenario);
    layout.gameSize = sizeof(Game);
    layout.contextSize = sizeof(Context);
    layout.scenario = offsetof(Global, scenario);
    layout.game = offsetof(Scenario, game);
    layout.contextCount = 3;
    for (uint32_t index = 0; index < layout.contextCount; ++index)
        layout.contexts[index] = offsetof(Scenario, children) + index * sizeof(void *);
    uint32_t cancel = 0;
    WorldObjects output;
    auto read = [&] { return readWorldObjects(layout, &cancel, output); };
    auto rejected = [&](int error, const char *message) {
        // A failed read must not expose a previous frame's references or a partially resolved object graph.
        output = {1, 2, 3, 4};
        require(read() == error, message);
        require(!output.scenario && !output.game && !output.context && !output.global, "failure retained object pointers");
    };
    require(read() == 0 && output.scenario == reinterpret_cast<uintptr_t>(&scenario) &&
            output.game == reinterpret_cast<uintptr_t>(&game) && output.context == reinterpret_cast<uintptr_t>(&context) &&
            output.global == reinterpret_cast<uintptr_t>(&global),
            "unique typed context was not resolved");
    scenario.children[1] = &other;
    scenario.children[2] = &anotherContext;
    require(read() == 0 && output.context == reinterpret_cast<uintptr_t>(&anotherContext), "context cached across calls");
    scenario.children[1] = &context;
    rejected(ENOTUNIQ, "two typed contexts were accepted");
    scenario.children[2] = &context;
    rejected(ENOTUNIQ, "two members aliasing one context were accepted");
    scenario.children[1] = nullptr;
    scenario.children[2] = nullptr;
    rejected(ENOTSUP, "unrelated polymorphic child was treated as a context");
    scenario.children[1] = &context;
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
    scenario.children[2] = guard;
    rejected(EFAULT, "unreadable candidate was skipped despite possible ambiguity");
    scenario.children[2] = nullptr;
    const auto original = layout;
    layout.contextVtable = reinterpret_cast<uintptr_t>(guard) + 2 * sizeof(uintptr_t);
    rejected(EFAULT, "unreadable vtable header was accepted");
    layout = original;
    layout.contextTypeInfo += sizeof(uintptr_t);
    rejected(ESTALE, "changed RTTI identity was accepted");
    layout = original;
    // A forged secondary address point is rejected even when its type-info word matches.
    uintptr_t secondary[] = {8, typeInfo, 0};
    layout.contextVtable = reinterpret_cast<uintptr_t>(secondary + 2);
    rejected(ESTALE, "nonprimary table adjustment was accepted");
    layout = original;
    layout.contextCount = 0;
    rejected(EINVAL, "empty candidate set was accepted");
    layout = original;
    layout.contextCount = FM_LINUX_CONTEXT_CANDIDATES + 1;
    rejected(EINVAL, "unbounded candidate set was accepted");
    layout = original;
    layout.contexts[1] = layout.contexts[0] + 1;
    rejected(EINVAL, "overlapping candidate members were accepted");
    layout = original;
    layout.contexts[0] = layout.game;
    rejected(EINVAL, "game/context alias was accepted");
    layout = original;
    layout.scenario = layout.globalSize;
    rejected(EINVAL, "out-of-bounds global member was accepted");
    layout = original;
    scenario.children[2] = reinterpret_cast<void *>(UINTPTR_MAX - 7);
    rejected(EFAULT, "wrapping context pointer was accepted");
    scenario.children[2] = nullptr;
    require(munmap(guard, pageSize) == 0, "guard mapping cleanup failed");
    require(read() == 0, "rejected read prevented later valid read");
    std::puts("factorio-mcp world objects fixture: passed");
}
