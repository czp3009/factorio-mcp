#pragma once
#include "input_clock.h"
#include "ui_capture.h"
#include "ui_modal.h"

#define FM_LINUX_KEY_EVENT_BYTES 256
#define FM_LINUX_TEXT_SCALARS 1024

typedef struct FmLinuxKeyEventLayout {
    uint32_t extent;
    uint32_t key;
    uint32_t extended;
    uint32_t character;
    uint32_t control;
    uint32_t source;
    uint32_t time;
    uint32_t none;
    uint32_t selectAll;
    uint32_t backspace;
    uint32_t extendedNone;
    uint8_t defaults[FM_LINUX_KEY_EVENT_BYTES];
} FmLinuxKeyEventLayout;

typedef struct FmLinuxUiTextConfig {
    FmLinuxKeyEventLayout event;
    FmLinuxInputClockLayout clock;
    FmLinuxCaptureLayout capture;
    FmLinuxModalLayout modal;
    uint64_t dynamicCast;
    uint64_t widgetType;
    uint64_t textBoxType;
    uint64_t focus;
    uint64_t keyDown;
} FmLinuxUiTextConfig;

typedef struct FmLinuxUiTextRequest {
    uint32_t count;
    uint32_t text[FM_LINUX_TEXT_SCALARS];
} FmLinuxUiTextRequest;

#ifdef __cplusplus
#include "ui_reference.h"

// One finite text replacement. Each callback reborrows and recasts the selected widget. Retain this
// command until finished(); finish() only releases owned references and never replays editing events.
class UiTextCommand {
public:
    bool finished() const { return finished_; }
    int start(uintptr_t guiInstance, void *gui, const FmLinuxUiLayout &ui, const FmLinuxUiTextConfig &config,
              const FmLinuxUiSelector &selector, const FmLinuxUiTextRequest &request, const uint32_t *cancel,
              FmLinuxUiSnapshot &scratch);
    int finish();

private:
    int record(int error);
    int borrow(UiTarget &target, void *&textBox) const;
    int dispatch(uint32_t key, uint32_t character, bool control, const uint32_t *cancel);
    bool started_ = false;
    bool finished_ = false;
    int failure_ = 0;
    uintptr_t guiInstance_ = 0;
    FmLinuxUiLayout ui_{};
    FmLinuxUiTextConfig config_{};
    FmLinuxUiTextRequest request_{};
    UiTargetReference target_;
};
#endif
