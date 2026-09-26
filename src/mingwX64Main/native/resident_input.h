#pragma once
#include "input_events.h"
#include <atomic>
#include <memory>

// One bounded input task, serviced only by mutually exclusive game frontend/evaluation phases.
class ResidentInput {
    struct Task;
    class Emitter;
    std::unique_ptr<Task> task;
    std::atomic<uint64_t> worldEpoch{};
    std::atomic<void *> boundGame{};
    bool evaluating{};

    void pollCancellation();
    void publish();

  public:
    ResidentInput();
    ~ResidentInput();
    void admit(const Symbols &symbols, const char *name, size_t capacity);
    void frontend(const Symbols &symbols);
    void evaluate(const Symbols &symbols, void *receiver, void (*original)(void *));
    void worldDestroyed(void *game);
    void cancel(const char *reason);
    bool active() const;
};
