#include "lua_query.h"
#include "linux_ipc.h"
#include <algorithm>
#include <cerrno>
#include <cmath>
#include <cstring>

namespace {
using Reader = const char *(*)(void *, void *, size_t *);
using Callback = void (*)(void *, void *);

template <class Function> Function function(uint64_t address) {
    return reinterpret_cast<Function>(static_cast<uintptr_t>(address));
}

struct Execution {
    const FmLinuxLuaApi *api;
    const FmLinuxLuaStateLayout *layout;
    const FmLinuxLuaQuery *query;
    const QueryViewport *viewport;
    const uint32_t *cancel;
    FmLinuxLuaResult *result;
    double playerIndex;
    bool read = false;
    int error = 0;
    uintptr_t handler = 0;
};

bool progress(void *state, Execution &execution, uint32_t elements, bool pushing = false) {
    LuaStateObservation observed;
    execution.error = readLuaState(reinterpret_cast<uintptr_t>(state), *execution.layout, execution.cancel, observed);
    if (execution.error)
        return false;
    if (observed.elements != elements || !observed.handler || (execution.handler && execution.handler != observed.handler)) {
        execution.error = EPROTO;
        return false;
    }
    execution.handler = observed.handler;
    // The verified number-push path requires strictly more than one free value slot.
    if (pushing && (observed.capacity <= 1 || !observed.frameCapacity)) {
        execution.error = ENOBUFS;
        return false;
    }
    return true;
}

const char *readSource(void *, void *userdata, size_t *size) {
    auto &execution = *static_cast<Execution *>(userdata);
    *size = execution.read ? 0 : execution.query->sourceSize;
    execution.read = true;
    return *size ? execution.query->source : nullptr;
}

// Keep only trivial objects across game calls: some supported Lua runtimes unwind with longjmp.
void execute(void *state, void *userdata) {
    auto &execution = *static_cast<Execution *>(userdata);
    const auto &api = *execution.api;
    const auto &query = *execution.query;
    auto &result = *execution.result;
    if (!progress(state, execution, 0, true))
        return;
    int status = function<int (*)(void *, Reader, void *, const char *, const char *)>(api.load)(
        state, readSource, &execution, "=factorio-mcp-world-query", "t");
    if (!status && !fm_ipc_load(execution.cancel)) {
        auto number = function<void (*)(void *, double)>(api.pushNumber);
        auto string = function<void (*)(void *, const char *, size_t)>(api.pushString);
        if (!progress(state, execution, 1, true))
            return;
        number(state, execution.playerIndex);
        if (!progress(state, execution, 2, true))
            return;
        string(state, query.arguments, query.argumentsSize);
        uint32_t elements = 3;
        if (query.includeViewport) {
            const auto &view = *execution.viewport;
            const double values[] = {view.surface, view.width, view.height, view.left, view.top, view.right, view.bottom};
            for (double value : values) {
                if (!progress(state, execution, elements, true))
                    return;
                number(state, value);
                ++elements;
            }
            if (!progress(state, execution, elements, true))
                return;
            string(state, view.error ? view.error : "", view.errorSize);
            ++elements;
        }
        if (!progress(state, execution, elements))
            return;
        if (!fm_ipc_load(execution.cancel)) {
            const int arguments = query.includeViewport ? 10 : 2;
            if (api.protectedCallArguments == 5) {
                status = function<int (*)(void *, int, int, int, void *)>(api.protectedCall)(
                    state, arguments, 1, 0, nullptr);
            } else {
                status = function<int (*)(void *, int, int, int, int, void *)>(api.protectedCall)(
                    state, arguments, 1, 0, 0, nullptr);
            }
        }
    }
    if (!progress(state, execution, 1))
        return;
    size_t size = 0;
    const char *text = function<const char *(*)(void *, int, size_t *)>(api.toString)(state, -1, &size);
    if (status) {
        execution.error = EPROTO;
        if (text) {
            result.errorSize = static_cast<uint32_t>(std::min(size, sizeof(result.error) - 1));
            std::memcpy(result.error, text, result.errorSize);
            result.error[result.errorSize] = 0;
        }
        return;
    }
    if (!text || !size || size >= sizeof(result.text)) {
        execution.error = text && size ? EOVERFLOW : EPROTO;
        return;
    }
    std::memcpy(result.text, text, size);
    result.text[size] = 0;
    result.size = static_cast<uint32_t>(size);
}
} // namespace

int queryLua(void *state, const FmLinuxLuaApi &api, const FmLinuxLuaStateLayout &layout, const FmLinuxLuaQuery &query, double playerIndex,
             const QueryViewport &viewport, const uint32_t *cancel, FmLinuxLuaResult &result) {
    result.size = 0;
    result.errorSize = 0;
    result.text[0] = 0;
    result.error[0] = 0;
    if (!state || !cancel || !api.absIndex || !api.setTop || !api.load || !api.pushNumber || !api.pushString ||
        !api.protectedCall || !api.toString || !api.rawProtected ||
        (api.protectedCallArguments != 5 && api.protectedCallArguments != 6) ||
        !query.sourceSize || query.sourceSize >= sizeof(query.source) ||
        !query.argumentsSize || query.argumentsSize >= sizeof(query.arguments) || query.includeViewport > 1 ||
        !std::isfinite(playerIndex) || playerIndex < 1 || playerIndex != std::floor(playerIndex) ||
        viewport.errorSize >= FM_LINUX_QUERY_ERROR || (!viewport.error && viewport.errorSize) || !validLuaStateLayout(layout))
        return EINVAL;
    if (fm_ipc_load(cancel))
        return ECANCELED;
    LuaStateObservation observed;
    int checked = readLuaState(reinterpret_cast<uintptr_t>(state), layout, cancel, observed);
    if (checked)
        return checked;
    if (observed.handler || observed.elements)
        return EBUSY;
    if (observed.capacity <= 1 || !observed.frameCapacity)
        return ENOBUFS;
    // Only enter even the leaf accessor after validating its state and frame reads.
    if (function<int (*)(void *, int)>(api.absIndex)(state, -1) != 0)
        return EBUSY;
    Execution execution{&api, &layout, &query, &viewport, cancel, &result, playerIndex};
    const int status = function<int (*)(void *, Callback, void *)>(api.rawProtected)(state, execute, &execution);
    if (!status && !execution.error && !result.size)
        execution.error = EPROTO;
    // Cancellation must not skip cleanup. Revalidate pointers after all game calls, including reallocations/errors.
    const uint32_t cleanupCancel = 0;
    checked = readLuaState(reinterpret_cast<uintptr_t>(state), layout, &cleanupCancel, observed);
    if (!checked && observed.handler)
        checked = EPROTO;
    if (!checked) {
        function<void (*)(void *, int)>(api.setTop)(state, 0);
        checked = readLuaState(reinterpret_cast<uintptr_t>(state), layout, &cleanupCancel, observed);
        if (!checked && (observed.elements || observed.handler))
            checked = EPROTO;
    }
    if (checked || status || execution.error || fm_ipc_load(cancel)) {
        result.size = 0;
        result.text[0] = 0;
        if (checked)
            return checked;
        if (fm_ipc_load(cancel)) {
            result.errorSize = 0;
            result.error[0] = 0;
            return ECANCELED;
        }
        return status ? EPROTO : execution.error;
    }
    return 0;
}
