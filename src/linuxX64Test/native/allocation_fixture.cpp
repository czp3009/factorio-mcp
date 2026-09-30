#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <new>

#ifndef FIXTURE_PADDING
#define FIXTURE_PADDING 1
#endif

struct Source {
    uint64_t value;
};

struct Object {
    unsigned char padding[FIXTURE_PADDING];
    uint64_t value;

    __attribute__((noinline)) explicit Object(const Source *source) : value(source->value) {}
    __attribute__((noinline)) ~Object();
};

struct Owner {
    unsigned char padding[FIXTURE_PADDING];
    Object *pointer;
};

extern "C" {
extern const uint64_t fixture_object_size = sizeof(Object);
extern const uint64_t fixture_owner_pointer = offsetof(Owner, pointer);
Object *fixture_global{};
volatile unsigned char fixture_flag{};
volatile uint64_t fixture_destroyed{};
volatile size_t fixture_freed{};

__attribute__((noinline)) void *fixture_allocate(size_t size) {
    void *result = std::malloc(size);
    if (!result)
        std::abort();
    return result;
}

__attribute__((noinline)) void fixture_publish(const Source *source) {
    fixture_flag = 1;
    void *memory = fixture_allocate(sizeof(Object));
    auto *object = new (memory) Object(source);
    fixture_global = object;
}

__attribute__((noinline)) void fixture_delete(void *pointer, size_t size) {
    fixture_freed = size;
    std::free(pointer);
}

__attribute__((noinline)) void fixture_destroy_owner(Owner *owner) {
    Object *pointer = owner->pointer;
    if (pointer) {
        pointer->~Object();
        fixture_delete(pointer, sizeof(Object));
    }
    owner->pointer = nullptr;
}
}

Object::~Object() {
    fixture_destroyed = value;
}

int main() {
    Source source{19};
    fixture_publish(&source);
    const bool valid = fixture_global && fixture_global->value == source.value && fixture_flag == 1;
    Owner owner{};
    owner.pointer = fixture_global;
    fixture_destroy_owner(&owner);
    fixture_destroy_owner(&owner);
    fixture_global = nullptr;
    return valid && !owner.pointer && fixture_destroyed == 19 && fixture_freed == sizeof(Object) ? 0 : 1;
}
