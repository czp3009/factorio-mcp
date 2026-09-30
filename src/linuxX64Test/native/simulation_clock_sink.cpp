extern "C" void fixture_clock_stop() {}
extern "C" {
unsigned fixture_clock_paused_calls;
void fixture_clock_paused() { ++fixture_clock_paused_calls; }
}
