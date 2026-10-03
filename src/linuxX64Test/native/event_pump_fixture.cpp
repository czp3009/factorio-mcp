#include "event_pump.h"
#include <cassert>
#include <cerrno>
#include <stdexcept>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <thread>
#include <unistd.h>

namespace {
struct Frame {
    uint64_t event = 0;
    uintptr_t outer = 0x2000;
};

struct Fixture {
    uintptr_t table[1]{0x3000};
    uintptr_t *window = table;
    Frame frame;
    FmLinuxPollHookConfig site{};
    EventPump pump;
    unsigned writes = 0;
    unsigned routed = 0;
    int writeError = 0;
    bool throws = false;
    bool omitEmpty = false;
    bool omitPoll = false;
    bool wrongCaller = false;

    Fixture() {
        site.table = site.entry = reinterpret_cast<uintptr_t>(table);
        site.original = table[0];
        site.caller = 0x1000;
        site.pumpCaller = frame.outer;
        site.stackReturn = offsetof(Frame, outer);
        site.eventFromStack = offsetof(Frame, event);
        site.eventExtent = sizeof(frame.event);
        site.protection = PROT_READ;
    }

    int poll(uintptr_t caller = 0x1000) {
        return pump.intercept(&window, &frame.event, caller, reinterpret_cast<uintptr_t>(&frame));
    }

    static int write(void *event, size_t size, void *context) noexcept {
        auto &self = *static_cast<Fixture *>(context);
        assert(event == &self.frame.event && size == sizeof(self.frame.event));
        ++self.writes;
        if (self.writeError)
            return self.writeError;
        *static_cast<uint64_t *>(event) = 42;
        return 0;
    }

    static void run(void *context) {
        auto &self = *static_cast<Fixture *>(context);
        EventPumpProgress nested;
        const auto thread = static_cast<pid_t>(syscall(SYS_gettid));
        assert(self.pump.dispatch(self.site, run, &self, write, &self, nested, thread) == EBUSY);
        assert(!nested.delivered);
        std::thread worker([&] {
            assert(self.poll() == -1);
            assert(self.pump.dispatch(self.site, run, &self, write, &self, nested) == EPERM);
            const auto foreignThread = static_cast<pid_t>(syscall(SYS_gettid));
            assert(self.pump.dispatch(self.site, run, &self, write, &self, nested, foreignThread) == EBUSY);
        });
        worker.join();
        if (self.omitPoll)
            return;
        if (self.wrongCaller) {
            assert(self.poll(self.site.caller + 1) == 0 && self.writes == 0);
            assert(self.poll() == 0 && self.writes == 0);
            return;
        }
        const int available = self.poll();
        assert(available == (self.writeError ? 0 : 1));
        if (available == 1) {
            assert(self.frame.event == 42);
            ++self.routed;
        }
        if (self.throws)
            throw std::runtime_error("native event route failed after delivery");
        if (!self.omitEmpty) {
            self.frame.event = 0;
            assert(self.poll() == 0 && self.frame.event == 0);
            assert(self.poll() == 0 && self.writes == 1);
        }
    }

    int dispatch(EventPumpProgress &progress, bool worker) {
        int result = -1;
        auto invoke = [&] {
            const pid_t thread = worker ? static_cast<pid_t>(syscall(SYS_gettid)) : 0;
            result = pump.dispatch(site, run, this, write, this, progress, thread);
        };
        if (worker) {
            std::thread evaluation(invoke);
            evaluation.join();
        } else {
            invoke();
        }
        return result;
    }
};
} // namespace

void exercise(bool worker) {
    EventPumpProgress progress;
    for (unsigned condition = 0; condition < 6; ++condition) {
        Fixture fixture;
        assert(fixture.poll() == -1);
        fixture.writeError = condition == 1 ? ESTALE : 0;
        fixture.throws = condition == 2;
        fixture.omitEmpty = condition == 3;
        fixture.omitPoll = condition == 4;
        fixture.wrongCaller = condition == 5;
        const int expected[] = {0, ESTALE, EFAULT, EPROTO, EPROTO, EPROTO};
        assert(fixture.dispatch(progress, worker) == expected[condition]);
        assert(progress.delivered == (condition == 0 || condition == 2 || condition == 3));
        assert(progress.returned == !fixture.throws);
        assert(progress.emptyObserved == (condition == 0 || condition == 1 || condition == 5));
        assert(fixture.poll() == -1);
        const auto writes = fixture.writes;
        assert(writes == (fixture.omitPoll || fixture.wrongCaller ? 0u : 1u));
        // Completion releases the interception scope even after exceptions. A new dispatch is explicit,
        // never an automatic replay of the previous payload.
        fixture.writes = 0;
        fixture.frame.event = 0;
        fixture.writeError = 0;
        fixture.throws = fixture.omitEmpty = fixture.omitPoll = fixture.wrongCaller = false;
        assert(fixture.dispatch(progress, worker) == 0 && fixture.writes == 1);
    }
    Fixture invalid;
    invalid.site.eventExtent = 0;
    assert(invalid.dispatch(progress, worker) == EINVAL && !progress.delivered && !progress.returned);
}

int main() {
    exercise(false);
    exercise(true);
}
