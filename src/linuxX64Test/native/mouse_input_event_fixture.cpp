#include "mouse_input_event.h"
#include <array>
#include <cassert>
#include <cerrno>
#include <cstring>
#include <limits>

template <class T> static T read(const unsigned char *bytes, unsigned offset) {
    T result;
    std::memcpy(&result, bytes + offset, sizeof(result));
    return result;
}

int main() {
    for (uint32_t padding : {1u, 19u, 57u}) {
        FmLinuxPointerEventLayout layout{};
        layout.extent = padding + 80;
        layout.type = padding + 4;
        layout.time = padding + 16;
        layout.emptyType = 71;
        for (unsigned button = 0; button < FM_LINUX_POINTER_BUTTONS; ++button)
            layout.codes[button] = 19 + button * 7;
        for (unsigned operation = 0; operation < FM_LINUX_POINTER_CASES; ++operation) {
            auto &item = layout.cases[operation];
            item.kind = 23 + operation * 11;
            item.x = item.y = item.code = item.wheel = item.wheelY = FM_LINUX_POINTER_NO_FIELD;
            if (operation != FM_LINUX_POINTER_ENTER) {
                item.x = padding + 32;
                item.y = padding + 44;
            }
            if (operation == FM_LINUX_POINTER_PRESS || operation == FM_LINUX_POINTER_RELEASE)
                item.code = padding + 56;
            if (operation == FM_LINUX_POINTER_WHEEL) {
                item.wheel = padding + 52;
                item.wheelY = padding + 64;
            }
            for (unsigned byte = padding + 72; byte < padding + 76; ++byte) {
                item.initialized[byte] = 1;
                item.defaults[byte] = byte + operation;
            }
        }
        assert(validPointerEventLayout(layout));
        std::array<unsigned char, FM_LINUX_EVENT_BYTES + 32> storage;
        auto *event = storage.data() + 16;
        auto reset = [&] {
            storage.fill(0xa5);
            std::memcpy(event + layout.type, &layout.emptyType, 4);
            std::memset(event + layout.time, 0, 8);
        };
        for (unsigned operation = 0; operation < FM_LINUX_POINTER_CASES; ++operation) {
            const bool isButton = operation == FM_LINUX_POINTER_PRESS || operation == FM_LINUX_POINTER_RELEASE;
            for (unsigned button = 1; button <= (isButton ? FM_LINUX_POINTER_BUTTONS : 1); ++button) {
                for (int direction : {-1, 1}) {
                    const auto &item = layout.cases[operation];
                    PointerEventRequest request{operation, isButton ? button : 0,
                                                operation == FM_LINUX_POINTER_ENTER ? 0 : 117,
                                                operation == FM_LINUX_POINTER_ENTER ? 0 : 219,
                                                operation == FM_LINUX_POINTER_WHEEL ? direction : 0};
                    reset();
                    const auto before = storage;
                    assert(writePointerEvent(layout, event, layout.extent, request, 17.25) == 0);
                    assert(read<uint32_t>(event, layout.type) == item.kind &&
                           read<double>(event, layout.time) == 17.25);
                    if (operation != FM_LINUX_POINTER_ENTER)
                        assert(read<int32_t>(event, item.x) == request.x && read<int32_t>(event, item.y) == request.y);
                    if (isButton)
                        assert(read<uint32_t>(event, item.code) == layout.codes[button - 1]);
                    if (operation == FM_LINUX_POINTER_WHEEL)
                        assert(read<int32_t>(event, item.wheel) == direction &&
                               read<int32_t>(event, item.wheelY) == direction);
                    for (size_t byte = 0; byte < storage.size(); ++byte) {
                        if (byte < 16 || byte >= 16 + layout.extent) {
                            assert(storage[byte] == before[byte]);
                            continue;
                        }
                        const auto offset = byte - 16;
                        if (item.initialized[offset]) {
                            assert(event[offset] == item.defaults[offset]);
                            continue;
                        }
                        bool field = offset >= layout.time && offset < layout.time + 8;
                        for (uint32_t at : {layout.type, item.x, item.y, item.code, item.wheel, item.wheelY})
                            field |= at != FM_LINUX_POINTER_NO_FIELD && offset >= at && offset - at < 4;
                        if (!field)
                            assert(storage[byte] == before[byte]);
                    }
                    const auto written = storage;
                    assert(writePointerEvent(layout, event, layout.extent, request, 17.26) == ESTALE);
                    assert(storage == written);
                }
            }
        }
        const PointerEventRequest ordinary{FM_LINUX_POINTER_MOVE, 0, 4, 8, 0};
        auto reject = [&](const FmLinuxPointerEventLayout &candidate, const PointerEventRequest &request,
                          size_t capacity, double time, int expected) {
            reset();
            const auto before = storage;
            assert(writePointerEvent(candidate, event, capacity, request, time) == expected);
            assert(storage == before);
        };
        reject(layout, ordinary, layout.extent - 1, 0, EINVAL);
        reject(layout, ordinary, layout.extent, -1, ERANGE);
        reject(layout, ordinary, layout.extent, std::numeric_limits<double>::quiet_NaN(), ERANGE);
        reject(layout, {FM_LINUX_POINTER_MOVE, 0, -1, 2, 0}, layout.extent, 0, ERANGE);
        reject(layout, {FM_LINUX_POINTER_PRESS, 0, 1, 2, 0}, layout.extent, 0, EINVAL);
        reject(layout, {FM_LINUX_POINTER_RELEASE, 6, 1, 2, 0}, layout.extent, 0, EINVAL);
        reject(layout, {FM_LINUX_POINTER_WHEEL, 0, 1, 2, 2}, layout.extent, 0, EINVAL);
        reject(layout, {FM_LINUX_POINTER_ENTER, 0, 1, 0, 0}, layout.extent, 0, EINVAL);
        for (unsigned change = 0; change < 9; ++change) {
            auto invalid = layout;
            switch (change) {
            case 0:
                invalid.extent = FM_LINUX_EVENT_BYTES + 1;
                break;
            case 1:
                invalid.cases[0].code = layout.time;
                break;
            case 2:
                invalid.cases[1].kind = invalid.cases[0].kind;
                break;
            case 3:
                invalid.cases[0].kind = layout.emptyType;
                break;
            case 4:
                invalid.cases[0].initialized[layout.type] = 1;
                break;
            case 5:
                invalid.cases[0].initialized[layout.extent] = 1;
                break;
            case 6:
                invalid.cases[0].initialized[layout.extent - 1] = 2;
                break;
            case 7:
                invalid.codes[4] = invalid.codes[0];
                break;
            case 8:
                invalid.cases[FM_LINUX_POINTER_WHEEL].wheelY = FM_LINUX_POINTER_NO_FIELD;
                break;
            }
            reject(invalid, ordinary, layout.extent, 0, EINVAL);
        }
        reset();
        event[layout.time] = 1;
        const auto before = storage;
        assert(writePointerEvent(layout, event, layout.extent, ordinary, 0) == ESTALE && storage == before);
        // Edges at a cursor outside the window preserve the native signed position, as on Windows.
        reset();
        assert(writePointerEvent(layout, event, layout.extent, {FM_LINUX_POINTER_PRESS, 5, -4, -8, 0}, 0) == 0);
    }
    alignas(16) std::array<unsigned char, 64> global{};
    alignas(16) std::array<unsigned char, 128> state{};
    uintptr_t globalPointer = reinterpret_cast<uintptr_t>(global.data());
    const uintptr_t statePointer = reinterpret_cast<uintptr_t>(state.data());
    FmLinuxMouseStateLayout owner{};
    owner.global = reinterpret_cast<uintptr_t>(&globalPointer);
    owner.globalSize = global.size();
    owner.stateSize = state.size();
    owner.stateMember = 24;
    owner.heldMask = 96;
    owner.eventSize = 80;
    owner.eventType = 4;
    owner.eventTime = 16;
    owner.eventCode = 40;
    owner.press = 12;
    owner.release = 18;
    for (unsigned index = 0; index < FM_LINUX_MOUSE_BUTTONS; ++index) {
        owner.codes[index] = 2 + index;
        owner.masks[index] = 1u << index;
    }
    std::memcpy(global.data() + owner.stateMember, &statePointer, sizeof(statePointer));
    FmLinuxPointerStateLayout cursor{32, 121, {1, 4, 2, 8, 16}};
    const int32_t position[2] = {-213, 519};
    const uint32_t held = cursor.masks[4];
    std::memcpy(state.data() + cursor.position, position, sizeof(position));
    std::memcpy(state.data() + owner.heldMask, &held, sizeof(held));
    InputStateObjects objects;
    PointerStateValue value;
    for (uint8_t inside : {uint8_t{0}, uint8_t{1}}) {
        state[cursor.inWindow] = inside;
        assert(readPointerState(owner, cursor, objects, value) == 0);
        assert(objects.global == globalPointer && objects.state == statePointer && objects.held == held);
        assert(value.x == position[0] && value.y == position[1] && value.inWindow == (inside != 0));
    }
    state[cursor.inWindow] = 2;
    assert(readPointerState(owner, cursor, objects, value) == EINVAL);
    assert(objects.global == 0 && objects.state == 0 && objects.held == 0 && value.x == 0 && value.y == 0 &&
           !value.inWindow);
    state[cursor.inWindow] = 1;
    for (unsigned change = 0; change < 4; ++change) {
        auto invalid = cursor;
        switch (change) {
        case 0:
            invalid.position = owner.stateSize - 4;
            break;
        case 1:
            invalid.inWindow = cursor.position;
            break;
        case 2:
            invalid.masks[4] = invalid.masks[0];
            break;
        case 3:
            invalid.position = owner.heldMask;
            break;
        }
        assert(readPointerState(owner, invalid, objects, value) == EINVAL && objects.state == 0);
    }
    globalPointer = 0;
    assert(readPointerState(owner, cursor, objects, value) == ENOENT && objects.state == 0);
    globalPointer = 1;
    assert(readPointerState(owner, cursor, objects, value) == EFAULT && objects.state == 0);
}
