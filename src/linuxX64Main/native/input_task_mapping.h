#pragma once
#include "input_task_wire.h"
#include <sys/types.h>

// Storage ownership only. Admission, replacement and cancellation policy belong to the Kotlin caller.
// Keep this object until close() succeeds, including after a failed open. The resident's mapping and pidfd
// survive the owner's descriptor close/exit so held input can finish cleanup and publish its terminal result.
class InputTaskMapping {
  public:
    InputTaskMapping() = default;
    InputTaskMapping(const InputTaskMapping &) = delete;
    InputTaskMapping &operator=(const InputTaskMapping &) = delete;
    ~InputTaskMapping();
    int open(pid_t owner, int descriptor);
    int ownerAlive(bool &alive) const;
    int close();

    FmLinuxInputTask *task() const {
        return task_;
    }

    bool owned() const {
        return task_ || descriptor_ >= 0 || owner_ >= 0;
    }

  private:
    int descriptor_ = -1;
    int owner_ = -1;
    FmLinuxInputTask *task_ = nullptr;
};
