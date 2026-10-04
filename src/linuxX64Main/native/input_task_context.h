#pragma once
#include "input_context.h"
#include "view_lifetime.h"
#include "../../nativeMain/native/input_sequence.h"

// One admitted task's comparison-only ownership. Every check reacquires the full context at the current safe
// point. The caller must have installed verified lifetime notifications before admission, serialize all reads
// with game object mutation, and release this binding only after input cleanup has finished.
class InputTaskContext {
  public:
    using Reader = int (*)(const FmLinuxInputContextConfig &, const uint32_t *, InputContext &);

    explicit InputTaskContext(ViewLifetime &lifetime, Reader reader = readInputContext);
    InputTaskContext(const InputTaskContext &) = delete;
    InputTaskContext &operator=(const InputTaskContext &) = delete;
    int bind(const FmLinuxInputContextConfig &config, const uint32_t *cancel, InputContext &output);
    int read(InputContext &output);

    bool owned() const {
        return owned_;
    }

    int failure() const {
        return failure_;
    }

    void release();

  private:
    struct Identity {
        uintptr_t game, source, player, map, view;
    } identity_{};

    ViewLifetime &lifetime_;
    Reader reader_;
    FmLinuxInputContextConfig config_{};
    bool started_ = false;
    bool owned_ = false;
    int failure_ = 0;
    int record(int error);
};

// The concrete emitter owns native press/release progress. Releases bypass world checks so cancellation,
// unload and a failed down can still reconcile that ownership. Never replay a native release already entered.
class GuardedInputEmitter final : public InputEmitter {
  public:
    GuardedInputEmitter(InputTaskContext &context, InputEmitter &native);
    void move(InputPosition position) override;
    void moveWorld(InputPoint position) override;
    void button(InputButton button, bool down) override;
    void wheel(int32_t direction) override;

  private:
    InputTaskContext &context_;
    InputEmitter &native_;
    void check();
};
