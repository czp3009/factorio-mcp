#include "string_result_fixture.h"
#include <cassert>
#include <initializer_list>
#include <new>

__attribute__((noinline)) String Control::name() const {
    return String(value);
}

extern "C" __attribute__((noinline)) void fixture_caller(const Control* control) {
    auto result = control->name();
    fixture_consume(&result);
}

// Exercise the explicit native bridge: output first, receiver second, no dependency on the RAX return value.
extern "C" void fixture_explicit_output(void*, const Control*) __asm__("_ZNK7Control4nameEv");

extern "C" {
extern const std::size_t fixture_result_size = sizeof(String);
extern const std::size_t fixture_result_data = offsetof(String, data);
extern const std::size_t fixture_result_length = offsetof(String, length);
extern const std::size_t fixture_result_local = offsetof(String, local);
}

int main() {
    for (char value : {'a', 'z'}) {
        const Control control{value};
        fixture_caller(&control);
        alignas(String) unsigned char storage[sizeof(String)];
        fixture_explicit_output(storage, &control);
        auto* result = std::launder(reinterpret_cast<String*>(storage));
        assert(result->data == result->local && result->length == 1 && result->data[0] == value);
        fixture_consume(result);
        result->~String();
    }
    assert(consumed == 4 && destroyed == 4);
}
