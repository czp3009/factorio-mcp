#pragma once
#include "world_objects.h"
#include "player_objects.h"
#include <stddef.h>
#include <stdint.h>
#include "observation_buffer.h"

#define FM_LINUX_CHAT_RECORD_BYTES (16 * 1024 * 1024)

typedef struct FmLinuxChatReadLayout {
    uint32_t consoleSize, consolePlayer;
    uint32_t sentinel[2], count[2];
    uint32_t nodeSize, next, previous, value;
    uint32_t tick, playerIndex, indexWidth, text, textSize, cached, playerName;
    uint32_t stringSize, stringData, stringLength;
} FmLinuxChatReadLayout;

typedef struct FmLinuxChatReadConfig {
    FmLinuxWorldLayout world;
    FmLinuxPlayerLayout player;
    FmLinuxChatReadLayout layout;
    uint32_t console;
    uint64_t raw, destroyString;
} FmLinuxChatReadConfig;

typedef struct FmLinuxChatRecord {
    uint64_t identity, tick;
    uint32_t stream, playerIndex, truncated, textSize, rawSize, playerNameSize;
    FmLinuxObservationBuffer text, raw, playerName;
} FmLinuxChatRecord;

typedef struct FmLinuxChatSnapshot {
    uint64_t consoleIdentity, totals[2];
    uint32_t count;
    FmLinuxObservationBuffer records;
} FmLinuxChatSnapshot;

#ifdef __cplusplus
struct ChatSnapshot {
    uint64_t consoleIdentity = 0, totals[2]{};
    uint32_t count = 0;
    std::vector<FmLinuxChatRecord> records;
    std::vector<std::vector<char>> strings;

    FmLinuxChatSnapshot wire() const {
        return {consoleIdentity, {totals[0], totals[1]}, count, observationBuffer(records)};
    }
};

struct ChatReadConfig {
    FmLinuxChatReadLayout layout;
    void (*raw)(void *, const void *);
    void (*destroyString)(void *);
};

// One verified frontend callback. No console/node/text pointer is retained across callbacks.
// Keep this command until finished(), including failure: an uncertain destructor must not be replayed.
class ChatReadCommand {
  public:
    int start(const ChatReadConfig &, uintptr_t console, uintptr_t player, const uint32_t *cancel,
              ChatSnapshot &);
    int finish();

    bool finished() const {
        return finished_;
    }

  private:
    alignas(16) unsigned char string_[256]{};
    void (*destroy_)(void *) = nullptr;
    bool started_ = false, busy_ = false, finished_ = false, owned_ = false, destroyEntered_ = false;
    int failure_ = 0;
    int record(int);
    int cleanup();
};
#endif
