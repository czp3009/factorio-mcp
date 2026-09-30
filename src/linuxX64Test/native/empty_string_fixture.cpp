#include "empty_string_fixture.h"
#include <cassert>
#include <new>

__attribute__((noinline)) String optional(void* a, void* b, void* c, void* d, void* e) {
    if (!fixture_lookup(a, b, c, d, e)) {
        return String();
    }
    Borrowed borrowed{&borrowed, {a, b, c, d, e}};
    fixture_borrow(&borrowed);
    return String('x');
}

extern "C" __attribute__((noinline)) void fixture_caller(void* a) {
    auto result = optional(a, a, a, a, a);
    fixture_consume(&result);
}

extern "C" void fixture_output(void*, void*, void*, void*, void*, void*) __asm__("_Z8optionalPvS_S_S_S_");

extern "C" {
extern const std::size_t fixture_size = sizeof(String);
extern const std::size_t fixture_data = offsetof(String, data);
extern const std::size_t fixture_length = offsetof(String, length);
extern const std::size_t fixture_local = offsetof(String, local);
}

int main() {
    int item = 0;
    void* cases[] = {nullptr, &item};
    for (void* value : cases) {
        fixture_caller(value);
        alignas(String) unsigned char storage[sizeof(String)];
        fixture_output(storage, value, value, value, value, value);
        auto* result = std::launder(reinterpret_cast<String*>(storage));
        assert(result->data == result->local && result->length == (value ? 1 : 0));
        assert(result->data[result->length] == 0);
        fixture_consume(result);
        result->~String();
    }
    assert(consumed == 4 && destroyed == 4);
}
