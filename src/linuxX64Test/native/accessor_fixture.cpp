#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <functional>
#include <string>

// Keep the standard visitor failure target local, as in the statically linked game executable.
namespace std {
__attribute__((noinline, noreturn)) void __throw_bad_function_call() {
    throw bad_function_call();
}
}

#ifndef FIXTURE_INLINE_DELETE
__attribute__((noinline))
#endif
void operator delete(void *pointer, std::size_t) noexcept {
    std::free(pointer);
}

struct FixtureReceiver {
    uint64_t prefix[FIXTURE_PADDING];
    uint8_t flags;
    uint32_t count;
    void *pointer;
};

struct FixtureVirtual {
    uint64_t padding[FIXTURE_PADDING];
    virtual ~FixtureVirtual();
#if FIXTURE_PADDING == 1
    virtual unsigned other();
    virtual unsigned selected(unsigned value);
#else
    virtual unsigned selected(unsigned value);
    virtual unsigned other();
#endif
};

FixtureVirtual::~FixtureVirtual() = default;

unsigned FixtureVirtual::other() {
    return 1;
}

unsigned FixtureVirtual::selected(unsigned value) {
    return value + 2;
}

struct FixtureTree;

struct FixtureRectangle {
    int32_t x, y, width, height;
};

struct FixtureGeometry {
    uint64_t padding[FIXTURE_PADDING];
    FixtureGeometry *parent = nullptr;
    int32_t x = 0, y = 0, width = 0, height = 0;
#if FIXTURE_PADDING == 1
    virtual int paddingX() const;
    virtual int paddingY() const;
#else
    virtual int paddingY() const;
    virtual int paddingX() const;
#endif
    __attribute__((noinline)) FixtureRectangle rectangle() const;
};

int FixtureGeometry::paddingX() const {
    return 3;
}

int FixtureGeometry::paddingY() const {
    return 7;
}

FixtureRectangle FixtureGeometry::rectangle() const {
    int32_t left = 0, top = 0;
    auto current = this;
    while (current->parent) {
        auto next = current->parent;
        left += current->x + next->paddingX();
        top += current->y + next->paddingY();
        current = next;
    }
    return {left, top, width, height};
}

struct FixtureRange {
    FixtureTree **begin;
    FixtureTree **end;
};

struct FixtureTree {
    uint64_t padding[FIXTURE_PADDING];
    FixtureRange first;
    uint64_t middle[FIXTURE_PADDING];
    FixtureRange second;

    __attribute__((noinline)) void walk(const std::function<void(FixtureTree *)> &visitor);
};

void FixtureTree::walk(const std::function<void(FixtureTree *)> &visitor) {
    for (auto child = first.begin; child != first.end; ++child)
        (*child)->walk(visitor);
    for (auto child = second.begin; child != second.end; ++child)
        (*child)->walk(visitor);
    visitor(this);
}

namespace fixture {
struct Text {
    __attribute__((noinline)) virtual ~Text();
    uint64_t padding[FIXTURE_PADDING];
    std::string content;
#if FIXTURE_PADDING == 1
    virtual unsigned other();
#endif
    virtual const std::string &getText() const;
};

struct Label : Text {
    ~Label() override;
    uint64_t extra[FIXTURE_PADDING];
    std::string label;
    const std::string &getText() const override;
};

Text::~Text() = default;

Label::~Label() = default;

const std::string &Text::getText() const {
    return content;
}

const std::string &Label::getText() const {
    return label;
}

#if FIXTURE_PADDING == 1
unsigned Text::other() {
    return 0;
}
#endif
}

extern "C" {
size_t fixture_geometry_size = sizeof(FixtureGeometry);
size_t fixture_geometry_parent = __builtin_offsetof(FixtureGeometry, parent);
int (FixtureGeometry::*fixture_geometry_x)() const = &FixtureGeometry::paddingX;
int (FixtureGeometry::*fixture_geometry_y)() const = &FixtureGeometry::paddingY;
size_t fixture_text_size = sizeof(fixture::Text);
size_t fixture_text_offset = __builtin_offsetof(fixture::Text, content);
size_t fixture_label_size = sizeof(fixture::Label);
size_t fixture_label_offset = __builtin_offsetof(fixture::Label, label);
const std::string &(fixture::Text::*fixture_text_member)() const = &fixture::Text::getText;

__attribute__((noinline)) const char *fixture_string_data(const std::string *value) {
    return value->data();
}

__attribute__((noinline)) size_t fixture_string_size(const std::string *value) {
    return value->size();
}

size_t fixture_accessor_size = sizeof(FixtureReceiver);
size_t fixture_flags_offset = offsetof(FixtureReceiver, flags);
size_t fixture_count_offset = offsetof(FixtureReceiver, count);
size_t fixture_pointer_offset = offsetof(FixtureReceiver, pointer);
size_t fixture_virtual_size = sizeof(FixtureVirtual);
size_t fixture_tree_size = sizeof(FixtureTree);
size_t fixture_tree_first_begin = offsetof(FixtureTree, first) + offsetof(FixtureRange, begin);
size_t fixture_tree_first_end = offsetof(FixtureTree, first) + offsetof(FixtureRange, end);
size_t fixture_tree_second_begin = offsetof(FixtureTree, second) + offsetof(FixtureRange, begin);
size_t fixture_tree_second_end = offsetof(FixtureTree, second) + offsetof(FixtureRange, end);
unsigned (FixtureVirtual::*fixture_virtual_member)(unsigned) = &FixtureVirtual::selected;

__attribute__((noinline)) bool fixture_enabled(const FixtureReceiver *receiver) {
    return (receiver->flags & 0x20) != 0;
}

uint32_t fixture_mutation_mask = 1u << FIXTURE_PADDING;

__attribute__((noinline)) void fixture_flag_set(FixtureReceiver *receiver) {
    receiver->count |= 1u << FIXTURE_PADDING;
}

__attribute__((noinline)) void fixture_flag_clear(FixtureReceiver *receiver) {
    receiver->count &= ~(1u << FIXTURE_PADDING);
}

__attribute__((noinline)) void fixture_flag_boolean(FixtureReceiver *receiver, bool value) {
    if (value) {
        receiver->count |= 1u << FIXTURE_PADDING;
        asm volatile("" ::: "memory");
    } else {
        receiver->count &= ~(1u << FIXTURE_PADDING);
        asm volatile("" ::: "memory");
    }
}

__attribute__((noinline)) uint32_t fixture_count(const FixtureReceiver *receiver) {
    return receiver->count;
}

__attribute__((noinline)) void *fixture_pointer(const FixtureReceiver *receiver) {
    return receiver->pointer;
}

__attribute__((noinline)) const uint32_t *fixture_address(const FixtureReceiver *receiver) {
    return &receiver->count;
}

__attribute__((noinline)) void fixture_member_call(FixtureReceiver *receiver) {
    asm volatile("" : : "r"(receiver) : "memory");
}

__attribute__((noinline)) void fixture_call(FixtureReceiver *receiver) {
    fixture_member_call(static_cast<FixtureReceiver *>(receiver->pointer));
    asm volatile("" ::: "memory");
}

__attribute__((noinline)) uint32_t fixture_mutating(FixtureReceiver *receiver) {
    return ++receiver->count;
}

__attribute__((noinline)) uint32_t fixture_indirect(const FixtureReceiver *receiver) {
    return static_cast<const FixtureReceiver *>(receiver->pointer)->count;
}
}

int main() {
    FixtureGeometry root, child;
    child.parent = &root;
    child.x = -20;
    child.y = 12;
    child.width = 123;
    child.height = 456;
    auto rectangle = child.rectangle();
    if (rectangle.x != -17 || rectangle.y != 19 || rectangle.width != 123 || rectangle.height != 456)
        return 2;
    FixtureReceiver receiver{};
    receiver.flags = 0x20;
    receiver.count = 123;
    receiver.pointer = &receiver;
    return !fixture_enabled(&receiver) || fixture_count(&receiver) != 123 || fixture_pointer(&receiver) != &receiver;
}
