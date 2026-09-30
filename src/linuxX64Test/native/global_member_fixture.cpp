#include <atomic>
#include <cstddef>
#include <cstdint>

#ifndef FIXTURE_PADDING
#define FIXTURE_PADDING 1
#endif

struct Object {
    unsigned char padding[FIXTURE_PADDING]{};
    std::atomic<unsigned char> busy{};

    __attribute__((noinline)) ~Object();
};

struct Context {
    unsigned char padding[FIXTURE_PADDING]{};
    Object *current{};
};

extern "C" {
extern const uint64_t fixture_context_size = sizeof(Context);
extern const uint64_t fixture_object_size = sizeof(Object);
extern const uint64_t fixture_current_offset = offsetof(Context, current);
Context *fixture_context{};
volatile uint64_t fixture_yields{};

__attribute__((noinline)) void fixture_yield() {
    fixture_yields = fixture_yields + 1;
}
}

Object::~Object() {
    while (busy.exchange(1, std::memory_order_acquire))
        fixture_yield();
    if (fixture_context->current == this)
        fixture_context->current = nullptr;
    busy.store(0, std::memory_order_release);
}

int main() {
    Context context{};
    fixture_context = &context;
    auto *current = new Object;
    context.current = current;
    delete new Object;
    if (context.current != current)
        return 1;
    delete current;
    return context.current ? 2 : 0;
}
