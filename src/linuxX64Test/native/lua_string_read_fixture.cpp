#include <cstddef>
#include <cstdint>
#include <cstring>

#ifndef FIXTURE_PADDING
#define FIXTURE_PADDING 1
#endif

struct Text {
    unsigned char padding[FIXTURE_PADDING]{};
    size_t size{};
    char data[16]{};
};

struct Value {
    unsigned char padding[FIXTURE_PADDING]{};
    Text *text{};
    unsigned tag{};
};

struct State {
    Value *top{};
};

extern "C" {
extern const uint64_t fixture_value_size = sizeof(Value);
extern const uint64_t fixture_tag_offset = offsetof(Value, tag);
extern const uint64_t fixture_pointer_offset = offsetof(Value, text);
extern const uint64_t fixture_length_offset = offsetof(Text, size);
extern const uint64_t fixture_data_offset = offsetof(Text, data);

__attribute__((noinline)) Value *fixture_index(State *state, int index) {
    return state->top + index;
}

__attribute__((noinline)) const char *fixture_tolstring(State *state, int index, size_t *size) {
    auto *value = fixture_index(state, index);
    if ((value->tag & 15) != 4)
        return nullptr;
    if (size)
        *size = value->text->size;
    return value->text->data;
}
}

int main() {
    Text text;
    const char input[] = {'a', 0, 'b'};
    std::memcpy(text.data, input, sizeof(input));
    text.size = sizeof(input);
    Value value;
    value.tag = 4 | 64;
    value.text = &text;
    State state{&value + 1};
    size_t size = 0;
    auto *result = fixture_tolstring(&state, -1, &size);
    return size == sizeof(input) && result == text.data && !std::memcmp(result, input, size) ? 0 : 1;
}
