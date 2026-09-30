extern "C" {
extern volatile unsigned fixture_pressed;

__attribute__((noinline)) bool fixture_control_down() {
    return (fixture_pressed & 1) != 0;
}

__attribute__((noinline)) bool fixture_shift_down() {
    return (fixture_pressed & 2) != 0;
}
}
