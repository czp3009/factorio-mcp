extern "C" {
unsigned fixture_lifetime_calls = 0;

bool fixture_lifetime_remaining() {
    static unsigned remaining = 3;
    if (!remaining) return false;
    --remaining;
    return true;
}

void fixture_lifetime_work() {
    asm volatile("" ::: "memory");
}
}
