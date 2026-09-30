#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <cstring>

struct MouseEvent;
extern "C" void fixture_borrow_frame(void **);

struct Widget {
    uintptr_t padding[FIXTURE_PADDING]{};
    Widget *parent = nullptr;
    int x = 0, y = 0;
    uint16_t accepted = 7;
    uint32_t flags = 0;
    unsigned calls = 0;
#if FIXTURE_PADDING == 1
    virtual int paddingX() const;
    virtual int paddingY() const;
#else
    virtual int paddingY() const;
    virtual int paddingX() const;
#endif
    virtual void onEvent(const MouseEvent &event);
    virtual bool enabled() const;
    __attribute__((noinline)) void dispatch(const MouseEvent &event);
    __attribute__((noinline)) void dispatchDown(const MouseEvent &event);
};

int Widget::paddingX() const {
    return 3;
}

int Widget::paddingY() const {
    return 7;
}

struct MouseEvent {
    uintptr_t padding[FIXTURE_PADDING == 1 ? 1 : 2]{};
    int x = 0, y = 0;
    uintptr_t payload[2]{};
    uint16_t mask = 1;
    Widget *source = nullptr;
    void *previous = nullptr;
    __attribute__((noinline)) MouseEvent relative(Widget *replacement) const;
};

void Widget::onEvent(const MouseEvent &event) {
    ++calls;
    x = event.x;
}

bool Widget::enabled() const {
    return true;
}

void Widget::dispatch(const MouseEvent &event) {
    const auto mask = event.mask;
    if (!enabled() || !(mask & accepted))
        return;
    void *borrowed = this;
    if (event.x)
        fixture_borrow_frame(&borrowed);
    onEvent(event);
    fixture_borrow_frame(&borrowed);
}

static constexpr unsigned clickBit = FIXTURE_PADDING == 1 ? 4 : 12;

void Widget::dispatchDown(const MouseEvent &event) {
    onEvent(event);
    if (flags & (1u << clickBit))
        dispatch(event);
    void *borrowed = this;
    fixture_borrow_frame(&borrowed);
}

MouseEvent MouseEvent::relative(Widget *replacement) const {
    MouseEvent result = *this;
    int oldX = 0, oldY = 0;
    auto current = source;
    while (current->parent) {
        auto parent = current->parent;
        oldX += current->x + parent->paddingX();
        oldY += current->y + parent->paddingY();
        current = parent;
    }
    int newX = 0, newY = 0;
    current = replacement;
    while (current->parent) {
        auto parent = current->parent;
        newX += current->x + parent->paddingX();
        newY += current->y + parent->paddingY();
        current = parent;
    }
#if FIXTURE_PADDING == 1
    result.x = x + oldX - newX;
    result.y = y + oldY - newY;
#else
    // Preserve a packed-coordinate store as a distinct optimized compiler fixture.
    uint64_t originalX = static_cast<uint32_t>(x);
    uint64_t originalY = static_cast<uint32_t>(y);
    asm volatile("" : "+r"(originalX), "+r"(originalY));
    uint64_t pair = static_cast<uint32_t>(originalX + oldX - newX) |
        (static_cast<uint64_t>(static_cast<uint32_t>(originalY + oldY - newY)) << 32);
    asm volatile("" : "+r"(pair));
    std::memcpy(&result.x, &pair, sizeof(pair));
#endif
    result.source = replacement;
    return result;
}

extern "C" {
size_t fixture_widget_size = sizeof(Widget);
size_t fixture_parent = offsetof(Widget, parent);
size_t fixture_event_size = sizeof(MouseEvent);
size_t fixture_source = offsetof(MouseEvent, source);
size_t fixture_x = offsetof(MouseEvent, x);
size_t fixture_y = offsetof(MouseEvent, y);
size_t fixture_mask = offsetof(MouseEvent, mask);
size_t fixture_accepted = offsetof(Widget, accepted);
size_t fixture_flags = offsetof(Widget, flags);
size_t fixture_click_bit = clickBit;
}

int main() {
    Widget root, first, second;
    first.parent = &root;
    first.x = 31;
    first.y = 43;
    second.parent = &root;
    second.x = 61;
    second.y = 73;
    MouseEvent event;
    event.source = &first;
    event.x = 100;
    event.y = 200;
    event.payload[0] = 19;
    const auto moved = event.relative(&second);
    if (moved.x != 70 || moved.y != 170 || moved.source != &second || moved.payload[0] != 19)
        std::abort();
    second.calls = 0;
    second.dispatchDown(event);
    if (second.calls != 1)
        std::abort();
    second.flags = 1u << clickBit;
    second.dispatchDown(event);
    if (second.calls != 3)
        std::abort();
    second.dispatch(event);
    if (second.x != event.x)
        std::abort();
    event.mask = 0;
    event.x = 999;
    second.dispatch(event);
    if (second.x == event.x)
        std::abort();
}
