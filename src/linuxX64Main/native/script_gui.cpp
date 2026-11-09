#include "script_gui.h"
#include "linux_ipc.h"
#include "memory_read.h"
#include "../../nativeMain/native/selector_payload.h"
#include <cerrno>
#include <cstring>

namespace {
void require(bool value, const char *message) {
    if (!value)
        throw std::runtime_error(message);
}

void unavailable(UiSnapshot &snapshot, const char *message) {
    snapshot.guiStatus = 2;
    const size_t length = std::min(std::strlen(message), sizeof(snapshot.guiMessage) - 1);
    std::memcpy(snapshot.guiMessage, message, length);
    snapshot.guiMessage[length] = 0;
    snapshot.guiResult.size = 0;
    snapshot.guiResult.text.clear();
}
}

int prepareScriptGui(const std::vector<uintptr_t> &widgets, const FmLinuxUiSelector *selector,
                      const uint32_t *cancel, UiSnapshot &snapshot) {
    bool matching = false;
    if (selector && selector->count) {
        try {
            for (const auto &step : fm::ui::selectorPath(selector->path, selector->count))
                matching |= step.guiMatch >= 0;
        } catch (...) {
            return EINVAL;
        }
    }
    snapshot.guiStatus = 0;
    snapshot.guiMatches.clear();
    snapshot.guiResult = {};
    snapshot.guiMessage[0] = 0;
    try {
        require(snapshot.guiWorld && snapshot.guiQuery && snapshot.guiRegistry,
                "Script GUI reader is unavailable");
        WorldObjects world;
        int error = readWorldObjects(snapshot.guiWorld->world, cancel, world);
        require(!error, "Script GUI observations require a loaded local world");
        PlayerObjects player;
        error = readLocalPlayer(world.game, snapshot.guiWorld->player, cancel, player);
        require(!error, "The injected client has no local player");
        const auto registry = fm::ui::guiRegistry(player.player, *snapshot.guiRegistry,
            [](uintptr_t address, auto &value) { return fm::read(address, value); },
            [cancel] { return fm_ipc_load(cancel) != 0; });
        require(widgets.size() == snapshot.nodes.size(), "Invalid UI widget association size");
        std::vector<int64_t> indices;
        indices.reserve(widgets.size());
        for (size_t index = 0; index < widgets.size(); ++index) {
            const auto found = registry.find(widgets[index]);
            snapshot.nodes[index].guiIndex = found == registry.end() ? -1 : int64_t(found->second);
            indices.push_back(snapshot.nodes[index].guiIndex);
        }
        if (matching) {
            const size_t matchSize = fm::ui::guiMatchSize(indices.size(), selector->count);
            const auto payload = fm::ui::guiIndexPayload(1, indices);
            error = queryWorld(*snapshot.guiWorld, *snapshot.guiQuery, cancel, snapshot.guiResult,
                               {payload.data(), payload.size()});
            require(!error, snapshot.guiResult.errorSize ? snapshot.guiResult.error : "Script GUI predicate read failed");
            require(snapshot.guiResult.text.size() == matchSize &&
                    std::all_of(snapshot.guiResult.text.begin(), snapshot.guiResult.text.end(),
                                [](char value) { return value == '0' || value == '1'; }),
                    "Invalid script GUI predicate result");
            snapshot.guiMatches = std::move(snapshot.guiResult.text);
            snapshot.guiResult.size = 0;
        }
        snapshot.guiStatus = 1;
    } catch (const std::exception &error) {
        unavailable(snapshot, error.what());
        if (fm_ipc_load(cancel))
            return ECANCELED;
        if (matching)
            return EPROTO;
    }
    return 0;
}

void observeScriptGui(const std::vector<int> &observed, const uint32_t *cancel, UiSnapshot &snapshot) {
    if (snapshot.guiStatus != 1)
        return;
    try {
        std::vector<int64_t> indices;
        indices.reserve(observed.size());
        for (int index : observed)
            indices.push_back(snapshot.nodes[index].guiIndex);
        const auto payload = fm::ui::guiIndexPayload(2, indices);
        const int error = queryWorld(*snapshot.guiWorld, *snapshot.guiQuery, cancel, snapshot.guiResult,
                                     {payload.data(), payload.size()});
        require(!error, snapshot.guiResult.errorSize ? snapshot.guiResult.error : "Script GUI property read failed");
    } catch (const std::exception &error) {
        unavailable(snapshot, error.what());
    }
}
