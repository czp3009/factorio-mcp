#include <cstdint>

#ifndef FIXTURE_PADDING
#define FIXTURE_PADDING 1
#endif

struct Object {
    unsigned char padding[FIXTURE_PADDING];
    void *value;
    __attribute__((noinline)) explicit Object(void *input) : value(input) {}
};

extern "C" {
extern const uint64_t fixture_object_size = sizeof(Object);
__attribute__((noinline)) Object *fixture_allocate(void *input) {
    return new Object(input);
}
}

int main() {
    int value = 7;
    auto *object = fixture_allocate(&value);
    const bool valid = object->value == &value;
    delete object;
    return valid ? 0 : 1;
}
