#include "world_objects.h"
#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <sys/mman.h>
#include <unistd.h>

static void require(bool value, const char *message) {
    if (!value) {
        std::fprintf(stderr, "factorio-mcp script objects fixture: %s\n", message);
        std::abort();
    }
}

struct Text { uintptr_t padding{}; const char *data; uint64_t length; };
struct Script {
    virtual ~Script() = default;
    uintptr_t padding[3]{};
    Text name{};
    void *state{};
    uint8_t loading = 0;
    uint8_t enabled = 1;
};
struct Other : Script { ~Other() override = default; };
struct Context { uintptr_t padding[3]{}; Script **begin; Script **end; };

int main() {
    alarm(30);
    uint64_t state = 0;
    Script first, second;
    Other other;
    const char expected[] = {'a', 0, 'b'};
    first.name = {0, expected, sizeof(expected)};
    second.name = first.name;
    first.state = second.state = &state;
    Script *scripts[] = {&first, &second};
    Context context{{}, scripts, scripts + 2};
    auto offset = [](const void *base, const void *member) {
        return static_cast<uint32_t>(static_cast<const char *>(member) - static_cast<const char *>(base));
    };
    FmLinuxScriptLayout layout{};
    std::memcpy(&layout.vtable, static_cast<const void *>(&first), sizeof(uintptr_t));
    std::memcpy(&layout.typeInfo, reinterpret_cast<const void *>(layout.vtable - sizeof(uintptr_t)), sizeof(uintptr_t));
    layout.contextSize = sizeof(context);
    layout.scriptSize = sizeof(first);
    layout.begin = offset(&context, &context.begin);
    layout.end = offset(&context, &context.end);
    layout.name = offset(&first, &first.name);
    layout.stringData = offset(&first.name, &first.name.data);
    layout.stringLength = offset(&first.name, &first.name.length);
    layout.state = offset(&first, &first.state);
    layout.loading = offset(&first, &first.loading);
    layout.enabled = offset(&first, &first.enabled);
    layout.expectedSize = sizeof(expected);
    std::memcpy(layout.expected, expected, sizeof(expected));
    ScriptObjects output;
    uint32_t cancel = 0;
    auto read = [&] { return readDefaultScript(reinterpret_cast<uintptr_t>(&context), layout, &cancel, output); };
    auto rejected = [&](int error, const char *message) {
        output = {1, 2};
        require(read() == error, message);
        require(!output.script && !output.state, "failure retained partial or previous references");
    };
    require(read() == 0 && output.script == reinterpret_cast<uintptr_t>(&first) && output.state == reinterpret_cast<uintptr_t>(&state),
            "exact bounded name with embedded NUL did not select the first script");
    first.name.data = "abc";
    rejected(ENOENT, "reader chose a later matching script or ignored a byte after NUL");
    first.name.data = expected;
    context.begin = scripts + 1;
    require(read() == 0 && output.script == reinterpret_cast<uintptr_t>(&second), "range retained across reads");
    context.end = context.begin;
    rejected(ENOENT, "empty range selected an element");
    context.begin = scripts;
    context.end = scripts + 2;
    scripts[0] = &other;
    rejected(ENOTSUP, "unverified script type was accepted");
    scripts[0] = &first;
    first.state = nullptr;
    rejected(EAGAIN, "absent state was accepted");
    first.state = &state;
    first.loading = 1;
    rejected(EAGAIN, "load-handler state was accepted");
    first.loading = 0;
    first.enabled = 0;
    rejected(EAGAIN, "disabled script state was accepted");
    first.enabled = 2;
    rejected(EFAULT, "non-boolean enabled flag was accepted");
    first.enabled = 1;
    first.loading = 2;
    rejected(EFAULT, "non-boolean loading flag was accepted");
    first.loading = 0;
    cancel = 1;
    rejected(ECANCELED, "cancellation ignored");
    cancel = 0;
    const size_t pageSize = sysconf(_SC_PAGESIZE);
    void *guard = mmap(nullptr, pageSize, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    require(guard != MAP_FAILED, "guard mapping failed");
    first.name.data = static_cast<const char *>(guard);
    first.name.length = 1;
    rejected(ENOENT, "length mismatch unnecessarily read string data");
    first.name.length = sizeof(expected);
    rejected(EFAULT, "inaccessible string data was accepted");
    first.name.data = expected;
    scripts[0] = static_cast<Script *>(guard);
    rejected(EFAULT, "inaccessible script was accepted");
    scripts[0] = &first;
    context.begin = static_cast<Script **>(guard);
    context.end = context.begin + 1;
    rejected(EFAULT, "inaccessible range was accepted");
    context.begin = scripts;
    context.end = reinterpret_cast<Script **>(reinterpret_cast<uintptr_t>(scripts) + 1);
    rejected(EFAULT, "partial pointer range was accepted");
    context.end = scripts + 2;
    const auto saved = layout;
    layout.typeInfo += sizeof(uintptr_t);
    rejected(ESTALE, "changed RTTI identity was accepted");
    layout = saved;
    layout.state = layout.name + layout.stringData;
    rejected(EINVAL, "overlapping name/state members were accepted");
    layout = saved;
    layout.name = layout.scriptSize;
    rejected(EINVAL, "out-of-bounds name was accepted");
    layout = saved;
    layout.expectedSize = FM_LINUX_SCRIPT_NAME + 1;
    rejected(EINVAL, "unbounded name literal was accepted");
    layout = saved;
    layout.loading = layout.state;
    rejected(EINVAL, "loading flag overlaps the state pointer");
    layout = saved;
    layout.enabled = layout.loading;
    rejected(EINVAL, "script flags overlap");
    layout = saved;
    layout.enabled = layout.scriptSize;
    rejected(EINVAL, "script flag exceeds object bounds");
    layout = saved;
    require(munmap(guard, pageSize) == 0, "guard mapping cleanup failed");
    require(read() == 0, "cannot recover after rejected reads");
    std::puts("factorio-mcp script objects fixture: passed");
}
