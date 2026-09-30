#pragma once
#include "world_objects.h"
#include "player_objects.h"

typedef struct FmLinuxChatAdmissionConfig {
    FmLinuxWorldLayout world;
    FmLinuxPlayerLayout player;
    uint64_t handlerVtable;
    uint64_t handlerTypeInfo;
    uint32_t mapSize;
    uint32_t handlerSize;
    uint32_t playerMap;
    uint32_t mapGame;
    uint32_t gameHandler;
    uint32_t handlerField;
    uint32_t excluded;
} FmLinuxChatAdmissionConfig;

#ifdef __cplusplus
// Fresh frontend references only. Requires matching loaded metadata and the original callback's guard proof.
// No Lua call, pause inference or stored player reference is needed for native chat submission.
class ChatAdmission {
public:
    ChatAdmission(const FmLinuxChatAdmissionConfig &config, const uint32_t *cancel);
    static int resolve(void *context, void *&player) noexcept;

private:
    const FmLinuxChatAdmissionConfig config_;
    const uint32_t *const cancel_;
    int read(void *&player) const;
};
#endif
