#include "script_gui.h"
#include "../../nativeMain/native/selector_payload.h"
#include "protocol.h"
#include <cstring>

namespace {
void unavailable(FmResult &result, const char *message) {
    result.guiStatus = 2;
    strncpy_s(result.guiMessage, message, _TRUNCATE);
    result.worldSize = 0;
}
}

bool hasGuiPredicates(const FmAction &action) {
    require(action.count >= 0, "Invalid selector length");
    if (!action.count)
        return false;
    for (const auto &step : fm::ui::selectorPath(action.path, action.count)) {
        if (step.guiMatch >= 0)
            return true;
    }
    return false;
}

void prepareScriptGui(const Symbols &symbols, void *game, const FmWorldQuery &query, const FmAction &action,
                      const std::vector<void *> &widgets, FmResult &result, ObservationStorage &storage,
                      const volatile LONG *cancel) {
    const bool matching = hasGuiPredicates(action);
    try {
        require(game && symbols.world.supported, "Script GUI observations require a loaded local world");
        require(query.source.size, "Script GUI reader is unavailable");
        auto *player = reinterpret_cast<void *(*)(void *)>(symbols.address[LocalPlayer])(game);
        require(player, "The injected client has no local player");
        auto registry = fm::ui::guiRegistry(reinterpret_cast<uintptr_t>(player), symbols.guiRegistry,
            [](uintptr_t address, auto &value) {
                SIZE_T count = 0;
                return ReadProcessMemory(GetCurrentProcess(), reinterpret_cast<void *>(address), &value,
                                         sizeof(value), &count) && count == sizeof(value);
            }, [cancel] { return cancel && *cancel; });
        require(widgets.size() == storage.nodes.size(), "Invalid UI widget association size");
        std::vector<int64_t> indices;
        indices.reserve(widgets.size());
        for (size_t index = 0; index < widgets.size(); ++index) {
            const auto found = registry.find(reinterpret_cast<uintptr_t>(widgets[index]));
            storage.nodes[index].guiIndex = found == registry.end() ? -1 : int64_t(found->second);
            indices.push_back(storage.nodes[index].guiIndex);
        }
        if (matching) {
            const size_t matchSize = fm::ui::guiMatchSize(indices.size(), action.count);
            const auto payload = fm::ui::guiIndexPayload(1, indices);
            queryWorld(symbols, game, query, result, storage, {payload.data(), payload.size()});
            require(storage.worldJson.size() == matchSize,
                    "Invalid script GUI predicate result size");
            require(std::all_of(storage.worldJson.begin(), storage.worldJson.end(),
                                [](char value) { return value == '0' || value == '1'; }),
                    "Invalid script GUI predicate result");
            storage.guiMatches = std::move(storage.worldJson);
            result.worldSize = 0;
        }
        result.guiStatus = 1;
    } catch (const std::exception &error) {
        unavailable(result, error.what());
        if (matching)
            throw;
    }
}

void observeScriptGui(const Symbols &symbols, void *game, const FmWorldQuery &query, const std::vector<int> &observed,
                      FmResult &result, ObservationStorage &storage) {
    if (result.guiStatus != 1)
        return;
    try {
        std::vector<int64_t> indices;
        indices.reserve(observed.size());
        for (int index : observed)
            indices.push_back(storage.nodes[index].guiIndex);
        const auto payload = fm::ui::guiIndexPayload(2, indices);
        queryWorld(symbols, game, query, result, storage, {payload.data(), payload.size()});
    } catch (const std::exception &error) {
        unavailable(result, error.what());
    }
}
