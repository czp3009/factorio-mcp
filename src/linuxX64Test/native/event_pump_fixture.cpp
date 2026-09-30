#include "event_pump.h"
#include <cassert>
#include <cerrno>
#include <stdexcept>
#include <sys/mman.h>
#include <thread>

namespace {
struct Frame {
    uintptr_t outer = 0x2000;
    uint64_t event = 0;
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

    Fixture() {
        site.table = site.entry = reinterpret_cast<uintptr_t>(table);
        site.original = table[0];
        site.caller = 0x1000;
        site.pumpCaller = frame.outer;
        site.eventFromFrame = offsetof(Frame, event);
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
        assert(self.pump.dispatch(self.site, run, &self, write, &self, nested) == EBUSY);
        assert(!nested.delivered);
        std::thread worker([&] {
            assert(self.poll() == -1);
            assert(self.pump.dispatch(self.site, run, &self, write, &self, nested) == EPERM);
        });
        worker.join();
        assert(self.poll(self.site.caller + 1) == -1 && self.writes == 0);
        if (self.omitPoll)
            return;
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

    int dispatch(EventPumpProgress &progress) {
        return pump.dispatch(site, run, this, write, this, progress);
    }
};
} // namespace

int main() {
    EventPumpProgress progress;
    for (unsigned condition = 0; condition < 5; ++condition) {
        Fixture fixture;
        assert(fixture.poll() == -1);
        fixture.writeError = condition == 1 ? ESTALE : 0;
        fixture.throws = condition == 2;
        fixture.omitEmpty = condition == 3;
        fixture.omitPoll = condition == 4;
        const int expected[] = {0, ESTALE, EFAULT, EPROTO, EPROTO};
        assert(fixture.dispatch(progress) == expected[condition]);
        assert(progress.delivered == (condition == 0 || condition == 2 || condition == 3));
        assert(progress.returned == !fixture.throws);
        assert(progress.emptyObserved == (condition == 0 || condition == 1));
        assert(fixture.poll() == -1);
        const auto writes = fixture.writes;
        assert(writes == (fixture.omitPoll ? 0u : 1u));
        // Completion releases the interception scope even after exceptions. A new dispatch is explicit,
        // never an automatic replay of the previous payload.
        fixture.writes = 0;
        fixture.frame.event = 0;
        fixture.writeError = 0;
        fixture.throws = fixture.omitEmpty = fixture.omitPoll = false;
        assert(fixture.dispatch(progress) == 0 && fixture.writes == 1);
    }
    Fixture invalid;
    invalid.site.eventExtent = 0;
    assert(invalid.dispatch(progress) == EINVAL && !progress.delivered && !progress.returned);
}
