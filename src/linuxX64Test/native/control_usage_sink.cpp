extern "C" bool fixture_required() {
    return true;
}

extern "C" void fixture_noise() {
    asm volatile("" ::: "memory");
}
