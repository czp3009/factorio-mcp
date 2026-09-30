#pragma once
#include "chat_admission.h"
#include <stdint.h>

#define FM_LINUX_CHAT_TEXT_BYTES 4096

typedef struct FmLinuxChatConfig {
    FmLinuxChatAdmissionConfig admission;
    uint64_t constructString;
    uint64_t assignString;
    uint64_t destroyString;
    uint64_t constructAction;
    uint64_t destroyAction;
    uint64_t submit;
    uint32_t stringSize;
    uint32_t actionSize;
    uint32_t actionType;
} FmLinuxChatConfig;

typedef struct FmLinuxChatRequest {
    uint32_t size;
    uint8_t text[FM_LINUX_CHAT_TEXT_BYTES];
} FmLinuxChatRequest;

typedef struct FmLinuxChatProgress {
    uint32_t entered;
    uint32_t returned;
} FmLinuxChatProgress;
