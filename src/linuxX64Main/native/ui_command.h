#pragma once
#include "ui_gesture.h"
#include "ui_reference.h"

// One finite widget gesture. The caller owns input-state mirroring and cancellation/admission policy.
// The containing resident command must retain this nonmovable object until finished(), including after errors.
// Every method that touches game state runs at the verified frontend safe point with the same validated context.
class UiMouseCommand {
public:
    bool started() const { return started_; }
    bool finished() const { return finished_; }
    int failure() const { return failure_; }
    const UiMouseProgress &progress() const { return progress_; }
    int start(const UiGestureContext &context, const UiTarget &target, const UiMouseRequest &request);
    int finish(const UiGestureContext &context);

private:
    int record(int error);
    int releaseReferences();
    void save(const UiMouseGesture &state);
    int rememberCapture(const UiGestureContext &context);
    UiGestureContext bind(const UiGestureContext &context);

    bool started_ = false;
    bool finished_ = false;
    bool captureKnown_ = false;
    bool releasedOriginal_ = false;
    int failure_ = 0;
    UiMouseParameters parameters_;
    UiMouseProgress progress_;
    UiCaptureProgress captureProgress_;
    UiCaptureProgress finalCaptureProgress_;
    UiTargetReference target_;
    TargetReference initial_;
    TargetReference previous_;
    UiTargetReference recipient_;
    UiTargetReference finalRecipient_;
    TargetReference pendingCapture_;
};
