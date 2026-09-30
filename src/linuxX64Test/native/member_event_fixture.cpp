#include <cassert>
#include <cstddef>
#include <cstdint>

struct KeyEvent {
    unsigned char padding[FIXTURE_PADDING];
    std::uint32_t key;
    std::uint32_t character;
    bool control;
    unsigned char separator[FIXTURE_PADDING];
    std::uint32_t extended;
    double time;
    bool handled;
    void *source;
};

struct Gui {
    unsigned char padding[FIXTURE_PADDING * 3];
    KeyEvent event;

    __attribute__((always_inline)) void setKeyEvent(std::uint32_t key, std::uint32_t character,
                                                   std::uint32_t extended, double time, bool control) {
        event.key = key;
        asm volatile("" ::: "memory");
        event.character = character;
        asm volatile("" ::: "memory");
        event.extended = extended;
        asm volatile("" ::: "memory");
        event.time = time;
        asm volatile("" ::: "memory");
        event.control = control;
        asm volatile("" ::: "memory");
        event.handled = false;
        asm volatile("" ::: "memory");
        event.source = nullptr;
    }
};

static volatile unsigned observed;

extern "C" {
extern const std::uint64_t fixture_member_gui_size = sizeof(Gui);
extern const std::uint64_t fixture_member_event = offsetof(Gui, event);
extern const std::uint64_t fixture_member_event_size = sizeof(KeyEvent);
extern const std::uint64_t fixture_member_key = offsetof(KeyEvent, key);
extern const std::uint64_t fixture_member_character = offsetof(KeyEvent, character);
extern const std::uint64_t fixture_member_extended = offsetof(KeyEvent, extended);
extern const std::uint64_t fixture_member_time = offsetof(KeyEvent, time);
extern const std::uint64_t fixture_member_control = offsetof(KeyEvent, control);
extern const std::uint64_t fixture_member_handled = offsetof(KeyEvent, handled);
extern const std::uint64_t fixture_member_source = offsetof(KeyEvent, source);

__attribute__((noinline)) void fixture_default_member(Gui *gui) {
    gui->event.key = 79;
    gui->event.character = 0;
    gui->event.control = false;
    gui->event.time = 0;
    gui->event.handled = false;
}

__attribute__((noinline)) void fixture_member_dispatch(void *, const KeyEvent &event) {
    observed = event.key + event.character + event.extended + event.control;
}

__attribute__((noinline)) unsigned fixture_construct_member(Gui *gui, const KeyEvent &input) {
    auto key = input.key;
    auto character = input.character;
    auto extended = input.extended;
    auto time = input.time;
    auto control = input.control;
    asm volatile("" : "+r"(key), "+r"(character), "+r"(extended), "+x"(time), "+r"(control));
    gui->setKeyEvent(key, character, extended, time, control);
    fixture_member_dispatch(gui, gui->event);
    return observed;
}
}

int main() {
    Gui gui{};
    KeyEvent input{};
    input.key = 197;
    input.character = 0x1f600;
    input.extended = 41;
    input.time = 1.25;
    input.control = true;
    assert(fixture_construct_member(&gui, input) == input.key + input.character + input.extended + 1);
    assert(gui.event.time == input.time && gui.event.control && !gui.event.handled && !gui.event.source);
    fixture_default_member(&gui);
    assert(gui.event.key == 79 && !gui.event.character && !gui.event.control && !gui.event.time && !gui.event.handled);
}
