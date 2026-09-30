#include "chat_submit.h"
#include <cassert>
#include <cerrno>
#include <new>
#include <stdexcept>
#include <string>
#include <thread>

namespace {
struct Action {
    uint32_t type;
    std::string value;
};
struct Fixture {
    unsigned phase = 0, failPhase = 0, resolutions = 0, rejectResolution = 0;
    unsigned stringDestructions = 0, actionDestructions = 0, sends = 0;
    std::string received;
    int player = 1;
    ChatSubmitCommand *reentrant = nullptr;
};
Fixture *active;

void phase() {
    ++active->phase;
    if (active->reentrant)
        assert(active->reentrant->finish() == EBUSY);
    if (active->phase == active->failPhase)
        throw std::runtime_error("fixture native failure");
}

void constructString(void *value) {
    phase();
    new (value) std::string();
}

void *assignString(void *value, const char *text, size_t size) {
    phase();
    static_cast<std::string *>(value)->assign(text, size);
    return value;
}

void destroyString(void *value) {
    ++active->stringDestructions;
    static_cast<std::string *>(value)->~basic_string();
    phase();
}

void constructAction(void *value, uint32_t type, const void *text) {
    phase();
    new (value) Action{type, *static_cast<const std::string *>(text)};
}

void destroyAction(void *value) {
    ++active->actionDestructions;
    static_cast<Action *>(value)->~Action();
    phase();
}

void submit(const void *player, void *value) {
    assert(player == &active->player);
    auto &action = *static_cast<Action *>(value);
    assert(action.type == 123);
    ++active->sends;
    active->received = std::move(action.value);
    phase(); // Failure can occur after the native dispatch has taken effect.
}

int resolve(void *context, void *&player) noexcept {
    auto &fixture = *static_cast<Fixture *>(context);
    ++fixture.resolutions;
    player = &fixture.player;
    return fixture.resolutions == fixture.rejectResolution ? ECANCELED : 0;
}

ChatSubmitConfig config() {
    return {sizeof(std::string), sizeof(Action), 123, constructString, assignString, destroyString,
        constructAction, destroyAction, submit};
}
}

int main() {
    const std::string message = "original text \xe4\xbd\xa0\xe5\xa5\xbd";
    {
        Fixture fixture;
        active = &fixture;
        ChatSubmitCommand command;
        fixture.reentrant = &command;
        std::thread foreign([&] {
            assert(command.start(config(), message.data(), message.size(), resolve, &fixture) == EPERM);
            assert(command.finish() == EPERM);
        });
        foreign.join();
        assert(command.start(config(), message.data(), message.size(), resolve, &fixture) == 0);
        assert(command.finished() && command.submitted() && command.submissionEntered());
        assert(fixture.received == message && fixture.sends == 1);
        assert(fixture.stringDestructions == 1 && fixture.actionDestructions == 1);
        assert(command.start(config(), message.data(), message.size(), resolve, &fixture) == EALREADY);
        assert(command.finish() == 0 && fixture.sends == 1);
    }
    for (unsigned fail = 1; fail <= 6; ++fail) {
        Fixture fixture;
        active = &fixture;
        fixture.failPhase = fail;
        ChatSubmitCommand command;
        assert(command.start(config(), message.data(), message.size(), resolve, &fixture) == EFAULT);
        assert(command.finished() == (fail <= 4));
        assert(fixture.sends == (fail >= 4 ? 1u : 0u));
        assert(command.submissionEntered() == (fail >= 4));
        assert(command.submitted() == (fail > 4));
        assert(fixture.stringDestructions == (fail > 1 ? 1u : 0u));
        assert(fixture.actionDestructions == (fail > 3 ? 1u : 0u));
        const unsigned phases = fixture.phase;
        fixture.failPhase = 0;
        assert(command.finish() == EFAULT && fixture.phase == phases);
    }
    for (unsigned reject = 1; reject <= 3; ++reject) {
        Fixture fixture;
        active = &fixture;
        fixture.rejectResolution = reject;
        ChatSubmitCommand command;
        assert(command.start(config(), message.data(), message.size(), resolve, &fixture) == ECANCELED);
        assert(command.finished() && !command.submissionEntered() && !fixture.sends);
        assert(fixture.stringDestructions == (reject > 1 ? 1u : 0u));
        assert(fixture.actionDestructions == (reject > 2 ? 1u : 0u));
    }
    for (const std::string text : {"", " ", "/command", " \t/command", "a\nb", "a\rb"}) {
        Fixture fixture;
        active = &fixture;
        ChatSubmitCommand command;
        assert(command.start(config(), text.data(), text.size(), resolve, &fixture) == EINVAL);
        assert(command.finished() && !fixture.phase && !fixture.resolutions);
    }
    {
        Fixture fixture;
        active = &fixture;
        ChatSubmitCommand command;
        auto invalid = config();
        invalid.actionSize = FM_LINUX_CHAT_ACTION_BYTES + 1;
        assert(command.start(invalid, message.data(), message.size(), resolve, &fixture) == EINVAL);
        assert(command.finished() && !fixture.phase);
    }
}
