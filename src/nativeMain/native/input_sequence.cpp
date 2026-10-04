#include "input_sequence.h"
#include <algorithm>
#include <cmath>
#include <exception>
#include <limits>
#include <stdexcept>
#include <utility>

namespace {
InputButton button(const InputEntry &entry) {
    return {entry.kind == InputKind::Keyboard ? InputDevice::Keyboard : InputDevice::Mouse, entry.code};
}

bool isButton(const InputEntry &entry) {
    return entry.kind == InputKind::Keyboard || entry.kind == InputKind::MouseButton;
}

uint64_t points(const InputEntry &entry) {
    return static_cast<uint64_t>(
               std::ceil(std::max(std::abs(entry.to.x - entry.from.x), std::abs(entry.to.y - entry.from.y)))) +
           1;
}

InputPoint position(const InputEntry &entry, const InputInterval &interval, uint64_t tick) {
    const auto duration = uint64_t(interval.last) - interval.first;
    const auto count = points(entry);
    const double progress =
        entry.perPointTicks
            ? (count == 1 ? 0.0 : double((tick - interval.first) / entry.perPointTicks) / double(count - 1))
            : (duration == 0 ? 1.0 : double(tick - interval.first) / double(duration));
    InputPoint result{entry.from.x + (entry.to.x - entry.from.x) * progress,
                      entry.from.y + (entry.to.y - entry.from.y) * progress};
    if (entry.tileCenters) {
        result.x = std::floor(result.x) + 0.5;
        result.y = std::floor(result.y) + 0.5;
    }
    return result;
}
} // namespace

void InputSequence::validate(const std::vector<InputEntry> &entries) {
    if (entries.size() > FM_INPUT_ENTRIES)
        throw std::invalid_argument("Input timeline exceeds 256 entries");
    for (const auto &entry : entries) {
        if (entry.kind > InputKind::Wheel || entry.intervals.empty() || entry.intervals.size() > FM_INPUT_INTERVALS)
            throw std::invalid_argument("Invalid timeline entry");
        for (const auto interval : entry.intervals) {
            if (interval.last < interval.first)
                throw std::invalid_argument("Timeline interval endpoints must be inclusive with start <= end");
        }
        if (isButton(entry) && (!entry.code || entry.world || entry.tileCenters || entry.perPointTicks))
            throw std::invalid_argument("Invalid input button");
        if (entry.kind == InputKind::Wheel &&
            (entry.code < 1 || entry.code > 2 || entry.perPointTicks || entry.world || entry.tileCenters))
            throw std::invalid_argument("Invalid wheel event");
        if (entry.kind == InputKind::Position || entry.kind == InputKind::Motion) {
            if (entry.code || (entry.tileCenters && !entry.world))
                throw std::invalid_argument("Invalid pointer descriptor");
            for (const auto point : {entry.from, entry.to}) {
                if (!std::isfinite(point.x) || !std::isfinite(point.y))
                    throw std::invalid_argument("Pointer coordinates must be finite");
                for (const auto value : {point.x, point.y}) {
                    if (entry.world ? std::abs(value) > 1000000
                                    : (value < 0 || value > INT32_MAX || std::floor(value) != value))
                        throw std::invalid_argument("Pointer coordinates exceed bounds");
                    if (entry.tileCenters && value != std::floor(value) + 0.5)
                        throw std::invalid_argument("Tile-center path requires centered endpoints");
                }
            }
            if (entry.kind == InputKind::Position &&
                (entry.perPointTicks || entry.from.x != entry.to.x || entry.from.y != entry.to.y))
                throw std::invalid_argument("Invalid static pointer position");
            if (entry.perPointTicks) {
                if (entry.intervals.size() != 1 || uint64_t(entry.intervals[0].last) - entry.intervals[0].first + 1 !=
                                                       points(entry) * entry.perPointTicks)
                    throw std::invalid_argument("Dwell duration does not match the path");
            }
        }
    }
}

std::vector<InputEntry> InputSequence::decode(const FmInputTimelineEntry *rows, uint32_t count) {
    if (count > FM_INPUT_ENTRIES || (count && !rows))
        throw std::invalid_argument("Invalid input wire bounds");
    std::vector<InputEntry> result;
    result.reserve(count);
    for (uint32_t i = 0; i < count; ++i) {
        const auto &row = rows[i];
        if (row.intervalCount > FM_INPUT_INTERVALS || row.space > 1 || row.tileCenters > 1)
            throw std::invalid_argument("Invalid input wire descriptor");
        InputEntry entry{static_cast<InputKind>(row.kind),
                         row.code,
                         {},
                         bool(row.space),
                         bool(row.tileCenters),
                         {row.fromX, row.fromY},
                         {row.toX, row.toY},
                         row.perPointTicks};
        for (uint32_t j = 0; j < row.intervalCount; ++j)
            entry.intervals.push_back({row.intervals[j].first, row.intervals[j].last});
        result.push_back(std::move(entry));
    }
    validate(result);
    return result;
}

InputSequence::InputSequence(std::vector<InputEntry> values) : entries(std::move(values)), cursors(entries.size()) {
    validate(entries);
    held.reserve(entries.size());
    for (const auto &entry : entries)
        for (const auto interval : entry.intervals)
            lastTick = std::max(lastTick, uint64_t(interval.last));
    if (entries.empty())
        stateValue = InputSequenceState::Succeeded;
}

void InputSequence::acquire(InputButton button, InputEmitter &emitter) {
    const auto found = std::find_if(held.begin(), held.end(), [&](const Held &item) { return item.button == button; });
    if (found != held.end()) {
        ++found->owners;
        return;
    }
    // Record ownership before dispatch, including an exception after a native state update.
    held.push_back({button, 1});
    emitter.button(button, true);
}

void InputSequence::relinquish(InputButton button, InputEmitter &emitter) {
    const auto found = std::find_if(held.begin(), held.end(), [&](const Held &item) { return item.button == button; });
    if (found == held.end())
        throw std::logic_error("Missing input ownership");
    if (found->owners > 1) {
        --found->owners;
    } else {
        emitter.button(button, false);
        held.erase(found);
    }
}

void InputSequence::release(InputEmitter &emitter) {
    std::exception_ptr failure;
    for (size_t i = held.size(); i > 0; --i) {
        try {
            emitter.button(held[i - 1].button, false);
            held.erase(held.begin() + i - 1);
        } catch (...) {
            if (!failure)
                failure = std::current_exception();
        }
    }
    if (failure)
        std::rethrow_exception(failure);
}

void InputSequence::fail(const char *message) {
    if (reasonValue.empty())
        reasonValue = std::string(message).substr(0, 1024);
    preparedTick.reset();
    stateValue = InputSequenceState::Releasing;
}

void InputSequence::beforeTick(uint64_t tick, InputEmitter &emitter) {
    if (stateValue == InputSequenceState::Releasing) {
        cleanup(emitter);
        return;
    }
    if (stateValue != InputSequenceState::Running)
        return;
    if (preparedTick) {
        if (*preparedTick != tick) {
            fail("Another input tick began before the previous evaluation completed");
            cleanup(emitter);
        }
        return;
    }
    if (previousTick && tick == *previousTick)
        return;
    if (previousTick && (*previousTick == UINT64_MAX || tick != *previousTick + 1)) {
        fail("Input clock changed discontinuously");
        cleanup(emitter);
        return;
    }
    try {
        for (size_t i = 0; i < entries.size(); ++i) {
            const auto &entry = entries[i];
            auto &cursor = cursors[i];
            if (cursor.completed)
                continue;
            for (size_t j = 0; j < entry.intervals.size(); ++j) {
                const auto interval = entry.intervals[j];
                if (evaluatedTicks < interval.first || evaluatedTicks > interval.last)
                    continue;
                if (!cursor.active[j]) {
                    cursor.active[j] = true;
                    if (isButton(entry))
                        acquire(button(entry), emitter);
                    else if (entry.kind == InputKind::Wheel)
                        emitter.wheel(entry.code == 1 ? 1 : -1);
                }
                if (stateValue != InputSequenceState::Running) {
                    cleanup(emitter);
                    return;
                }
                if (entry.kind == InputKind::Motion || entry.kind == InputKind::Position) {
                    const auto point = position(entry, interval, evaluatedTicks);
                    if (entry.world)
                        emitter.moveWorld(point);
                    else
                        emitter.move({static_cast<int32_t>(std::floor(point.x + 0.5)),
                                      static_cast<int32_t>(std::floor(point.y + 0.5))});
                }
                if (stateValue != InputSequenceState::Running) {
                    cleanup(emitter);
                    return;
                }
            }
        }
        preparedTick = tick;
    } catch (const std::exception &error) {
        fail(error.what());
        cleanup(emitter);
    }
}

void InputSequence::afterTick(uint64_t tick, InputEmitter &emitter) {
    if (stateValue != InputSequenceState::Running || !preparedTick)
        return;
    if (*preparedTick != tick) {
        fail("Input evaluation completed with a different tick");
        cleanup(emitter);
        return;
    }
    preparedTick.reset();
    previousTick = tick;
    const auto relative = evaluatedTicks++;
    try {
        for (size_t i = 0; i < entries.size(); ++i) {
            const auto &entry = entries[i];
            auto &cursor = cursors[i];
            if (cursor.completed)
                continue;
            for (size_t j = 0; j < entry.intervals.size(); ++j) {
                if (!cursor.active[j] || entry.intervals[j].last != relative)
                    continue;
                if (isButton(entry))
                    relinquish(button(entry), emitter);
                cursor.active[j] = false;
                ++cursor.finished;
                if (stateValue != InputSequenceState::Running) {
                    cleanup(emitter);
                    return;
                }
            }
            if (cursor.finished == entry.intervals.size()) {
                cursor.completed = true;
                ++completedEntries;
            }
        }
        if (relative == lastTick) {
            release(emitter);
            stateValue = InputSequenceState::Succeeded;
        }
    } catch (const std::exception &error) {
        fail(error.what());
        cleanup(emitter);
    }
}

void InputSequence::cancel(const char *reason) {
    if (stateValue == InputSequenceState::Running)
        fail(reason);
}

void InputSequence::cleanup(InputEmitter &emitter) {
    if (stateValue != InputSequenceState::Releasing)
        return;
    try {
        release(emitter);
        stateValue = InputSequenceState::Aborted;
    } catch (const std::exception &) {
        // Keep outstanding obligations so the next eligible phase can finish cleanup.
    }
}

InputSequenceState InputSequence::state() const {
    return stateValue;
}

const std::string &InputSequence::reason() const {
    return reasonValue;
}

uint64_t InputSequence::ticks() const {
    return evaluatedTicks;
}

size_t InputSequence::completed() const {
    return completedEntries;
}

bool InputSequence::hasHeldInput() const {
    return !held.empty();
}
