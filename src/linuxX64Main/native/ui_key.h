#pragma once
#include "keyboard_event.h"
#include "ui_capture.h"
#include "ui_modal.h"

typedef struct FmLinuxUiKeyConfig {
    FmLinuxKeyboardEventLayout event;
    FmLinuxEventClock clock;
    FmLinuxCaptureLayout capture;
    FmLinuxModalLayout modal;
    uint64_t focus;
} FmLinuxUiKeyConfig;

typedef struct FmLinuxUiKeyRequest {
    uint32_t count;
    uint32_t keys[8];
} FmLinuxUiKeyRequest;

#ifdef __cplusplus
#include "ui_reference.h"
#include "../../nativeMain/native/key_gesture.h"

class UiKeyCommand {
public:
    bool finished() const { return started_ && !gesture_.active() && !target_.owned(); }
    bool active() const { return gesture_.active(); }
    int failure() const { return failure_; }
    int start(uintptr_t instance, void *gui, const FmLinuxUiLayout &ui, const FmLinuxUiKeyConfig &config,
              const FmLinuxUiSelector &selector, const FmLinuxUiKeyRequest &request, const uint32_t *cancel,
              FmLinuxUiSnapshot &scratch);
    int finish();
    // An error retains any owed releases. Only commit a gesture edge after the native event was written.
    int poll(void *event, bool canceled, bool &produced);

private:
    int record(int error);
    void cancel();
    bool started_ = false;
    int failure_ = 0;
    FmLinuxUiKeyConfig config_{};
    KeyGesture gesture_;
    UiTargetReference target_;
};
#endif
