#pragma once
#include "chat_wire.h"
#include <cstdint>
#include <cstddef>

#define FM_LINUX_CHAT_BYTES FM_LINUX_CHAT_TEXT_BYTES
#define FM_LINUX_CHAT_STRING_BYTES 256
#define FM_LINUX_CHAT_ACTION_BYTES 4096

struct ChatSubmitConfig {
    uint32_t stringSize;
    uint32_t actionSize;
    uint32_t actionType;
    void (*constructString)(void *);
    void *(*assignString)(void *, const char *, size_t);
    void (*destroyString)(void *);
    void (*constructAction)(void *, uint32_t, const void *);
    void (*destroyAction)(void *);
    void (*submit)(const void *, void *);
};

// The caller supplies a plain UTF-8 message validated by the common tool contract, without rewriting it.
// One frontend submission. The caller proves all entry ABIs, storage bounds and the native submission gate.
// Resolve reacquires a current permitted player and checks cancellation; no borrowed pointer survives a call.
// Keep this object until finished(), including after start() fails. Entered submission/destruction never replays.
class ChatSubmitCommand {
public:
    using Resolve = int (*)(void *, void *&) noexcept;
    int start(const ChatSubmitConfig &config, const char *text, size_t size, Resolve resolve, void *context);
    int finish();
    bool finished() const { return finished_; }
    bool submitted() const { return submitReturned_; }
    bool submissionEntered() const { return submitEntered_; }

private:
    alignas(16) unsigned char string_[FM_LINUX_CHAT_STRING_BYTES]{};
    alignas(16) unsigned char action_[FM_LINUX_CHAT_ACTION_BYTES]{};
    ChatSubmitConfig config_{};
    bool started_ = false, busy_ = false, finished_ = false;
    bool stringOwned_ = false, actionOwned_ = false;
    bool stringDestroyEntered_ = false, actionDestroyEntered_ = false;
    bool submitEntered_ = false, submitReturned_ = false;
    int failure_ = 0;
    int record(int error);
    int cleanup();
};
