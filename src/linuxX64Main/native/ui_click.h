#pragma once
#include "input_events.h"
#include "key_state.h"
#include "ui_gesture.h"

typedef struct FmLinuxUiClickConfig {
    FmLinuxMouseGestureConfig gesture;
    FmLinuxMouseStateConfig state;
    // Resolved input codes in public button order: left, right, middle.
    uint32_t codes[3];
    FmLinuxKeyboardStateConfig keyboard;
} FmLinuxUiClickConfig;

typedef struct FmLinuxUiClickRequest {
    uint32_t button;
    double x;
    double y;
    uint32_t control;
    uint32_t shift;
    uint32_t alt;
} FmLinuxUiClickRequest;

#ifdef __cplusplus
#include "ui_command.h"
// One admitted finite click, including both GUI capture/reference and InputState ownership. Configuration is copied
// before admission so a later IPC request cannot replace metadata used by unfinished cleanup. Retain until finished().
class UiClickCommand {
public:
    bool started() const { return started_; }
    bool finished() const { return finished_; }
    int failure() const { return failure_; }
    int start(uintptr_t guiInstance, void *gui, const FmLinuxUiLayout &ui, const FmLinuxUiClickConfig &config,
              const FmLinuxUiSelector &selector, const FmLinuxUiClickRequest &request, const uint32_t *cancel,
              FmLinuxUiSnapshot &scratch);
    int finish();

private:
    int record(int error);
    int releaseInput();
    int time(double &output) const;
    UiGestureContext context() const;
    InputStateFunctions stateFunctions() const;
    bool started_ = false;
    bool finished_ = false;
    int failure_ = 0;
    uintptr_t guiInstance_ = 0;
    FmLinuxUiLayout ui_{};
    FmLinuxUiClickConfig config_{};
    UiMouseCommand gesture_;
    MouseButtonOwnership button_;
    KeyboardKeyOwnership keys_[3];
};
#endif
