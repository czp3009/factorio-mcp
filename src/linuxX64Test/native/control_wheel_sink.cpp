#include "control_wheel_fixture.h"
#include <cassert>
#include <new>

extern "C" void* fixture_allocate(std::size_t size) {
    return ::operator new(size);
}

String::String(const String& other) : length(other.length) {
    if (length < sizeof(storage.local)) {
        data = storage.local;
    } else {
        data = static_cast<char*>(fixture_allocate(length + 1));
        storage.capacity = length;
    }
    std::memcpy(data, other.data, length + 1);
}

String::~String() {
    if (data != storage.local) ::operator delete(data);
}

extern "C" String* fixture_prefix(String* value, std::size_t position, std::size_t replaced,
                                   const char* text, std::size_t length) {
    assert(value && position == 0 && replaced == 0 && text == nullptr && length == 0);
    return value;
}
