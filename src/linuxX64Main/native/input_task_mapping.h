#pragma once
#include "input_task_wire.h"
#include <sys/types.h>

// Storage ownership only. Admission, replacement and cancellation policy belong to the Kotlin caller.
// Keep this object until close() succeeds, including after failed preparation. The resident's mapping and pidfd
// survive the owner's descriptor close/exit so held input can finish cleanup and publish its terminal result.
class InputTaskMapping {
  public:
    InputTaskMapping() = default;
    InputTaskMapping(const InputTaskMapping &) = delete;
    InputTaskMapping &operator=(const InputTaskMapping &) = delete;
    ~InputTaskMapping();
    int create(pid_t owner);
    int take(InputTaskMapping &source);
    int ownerAlive(bool &alive) const;
    int close();

    FmLinuxInputTask *task() const {
        return task_;
    }

    int descriptor() const {
        return descriptor_;
    }

    pid_t owner() const {
        return ownerPid_;
    }

    bool owned() const {
        return task_ || descriptor_ >= 0 || owner_ >= 0;
    }

  private:
    int descriptor_ = -1;
    int owner_ = -1;
    pid_t ownerPid_ = 0;
    FmLinuxInputTask *task_ = nullptr;
    int bindOwner(pid_t owner);
    int map();
    int validateOwner() const;
};
