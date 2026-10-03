#pragma once
#include <stdint.h>
#include "ui_snapshot.h"
#include "world_query.h"
#include "ui_click.h"
#include "ui_text.h"
#include "ui_key.h"
#include "poll_site.h"
#include "controls.h"
#include "input_context.h"
#include "event_route_context.h"
#include "chat_wire.h"
#include "chat_read.h"
#include "frame_context.h"
#include "frame_api.h"
#include "frame_hook_owner.h"
#include "worker_completion.h"
#include "input_task_wire.h"
#include "mouse_input_event.h"
#include "input_dispatch.h"
#include "game_state.h"

enum FmLinuxCommand {
    FM_LINUX_IDLE = 0,
    FM_LINUX_PENDING = 1,
    FM_LINUX_RUNNING = 2,
    FM_LINUX_COMPLETE = 3
};

enum FmLinuxOperation {
    FM_LINUX_FRAME = 1,
    FM_LINUX_DETACH = 2,
    FM_LINUX_UI = 3,
    FM_LINUX_QUERY = 4,
    FM_LINUX_CLICK = 5,
    FM_LINUX_CLEANUP = 6,
    FM_LINUX_TEXT = 7,
    FM_LINUX_KEY = 8,
    FM_LINUX_CONTROLS = 9,
    FM_LINUX_INPUT_CONTEXT = 10,
    FM_LINUX_CHAT = 11,
    FM_LINUX_CHAT_READ = 12,
    FM_LINUX_SCREENSHOT = 13,
    FM_LINUX_WORKER = 14,
    FM_LINUX_INPUT = 15,
    FM_LINUX_INPUT_CANCEL = 16
};

// Raw same-callback scalar observations only. Borrowed game pointers never cross the native wire.
typedef struct FmLinuxInputContextSnapshot {
    uint64_t tick;
    uint32_t paused;
    uint32_t stopped;
} FmLinuxInputContextSnapshot;

typedef struct FmLinuxRetirementConfig {
    uint64_t entry;
    uint64_t original;
    uint32_t protection;
} FmLinuxRetirementConfig;

typedef struct FmLinuxInputEvaluationConfig {
    uint64_t entry;
    uint64_t original;
    uint64_t caller;
    uint32_t protection;
    uint32_t thread;
} FmLinuxInputEvaluationConfig;

// Supplied only after the selected executable and live dispatch have been validated by Kotlin.
typedef struct FmLinuxHookConfig {
    uint64_t entry;
    uint64_t original;
    uint64_t guiInstance;
    uint64_t caller;
    uint64_t callerStart;
    uint64_t callerEnd;
    uint32_t protection;
} FmLinuxHookConfig;

typedef struct FmLinuxShared {
    uint32_t initialized;
    uint32_t process;
    uint32_t attached;
    uint32_t command;
    uint32_t cancel;
    uint64_t frame;
    uint32_t operation;
    int32_t result;
    uint32_t pointerOwned;
    uint32_t protectionOwned;
    uint32_t actionOwned;
    uint64_t resultFrame;
    FmLinuxGameStateConfig gameStateConfig;
    FmLinuxGameState gameState;
    int32_t gameStateError;
    FmLinuxHookConfig config;
    FmLinuxUiLayout ui;
    uint32_t nodeLimit;
    FmLinuxUiSelector selector;
    FmLinuxUiSnapshot snapshot;
    FmLinuxWorldQueryConfig worldConfig;
    FmLinuxLuaQuery query;
    FmLinuxLuaResult queryResult;
    FmLinuxUiClickConfig clickConfig;
    FmLinuxUiClickRequest clickRequest;
    FmLinuxUiTextConfig textConfig;
    FmLinuxUiTextRequest textRequest;
    FmLinuxPollHookConfig pollConfig;
    FmLinuxUiKeyConfig keyConfig;
    FmLinuxUiKeyRequest keyRequest;
    FmLinuxControlsLayout controlsLayout;
    FmLinuxControlsSnapshot controlsSnapshot;
    FmLinuxInputContextConfig inputContextConfig;
    FmLinuxInputContextSnapshot inputContextSnapshot;
    FmLinuxRetirementConfig retirementConfig;
    FmLinuxWorkerCompletionConfig workerConfig;
    uint32_t workerThread;
    uint32_t workerFailure;
    FmLinuxInputDispatchConfig inputDispatch;
    FmLinuxInputEvaluationConfig inputEvaluation;
    uint32_t inputOwner;
    int32_t inputDescriptor;
    uint32_t inputOwned;
    FmLinuxChatConfig chatConfig;
    FmLinuxChatRequest chatRequest;
    FmLinuxChatProgress chatProgress;
    FmLinuxChatReadConfig chatReadConfig;
    FmLinuxChatSnapshot chatSnapshot;
    FmLinuxFrameContextConfig frameContext;
    FmLinuxFrameApiConfig frameApi;
    FmLinuxFrameHookSite frameSite;
    FmLinuxFrameSize frameSize;
    uint32_t frameBytes;
    uint32_t frameGlError;
    unsigned char framePixels[16777216 * 3];
} FmLinuxShared;

// Typed addresses for Kotlin's atomic wire operations; admission policy stays in Kotlin.
static inline uint32_t *fm_linux_command_word(FmLinuxShared *shared) { return &shared->command; }
static inline uint32_t *fm_linux_cancel_word(FmLinuxShared *shared) { return &shared->cancel; }
static inline uint32_t *fm_linux_attached_word(FmLinuxShared *shared) { return &shared->attached; }
static inline uint32_t *fm_linux_pointer_word(FmLinuxShared *shared) { return &shared->pointerOwned; }
static inline uint32_t *fm_linux_action_word(FmLinuxShared *shared) { return &shared->actionOwned; }
static inline uint32_t *fm_linux_worker_thread_word(FmLinuxShared *shared) { return &shared->workerThread; }
static inline uint32_t *fm_linux_worker_failure_word(FmLinuxShared *shared) { return &shared->workerFailure; }
static inline uint32_t *fm_linux_input_owned_word(FmLinuxShared *shared) { return &shared->inputOwned; }

typedef struct FmLinuxResidentInfo {
    uint64_t mapping;
    int32_t descriptor;
    uint32_t initialized;
} FmLinuxResidentInfo;

#ifdef __cplusplus
extern "C" {
#endif

// Data export allows validating an existing initialized IPC mapping without invoking resident code.
extern FmLinuxResidentInfo fm_linux_resident;
int64_t fm_linux_initialize(void);
int64_t fm_linux_attach(void);
// Also permits cleanup when pointer removal succeeded but restoring its page protection failed.
int64_t fm_linux_cleanup(void);

#ifdef __cplusplus
}
#endif
