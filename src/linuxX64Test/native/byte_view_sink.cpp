#include "byte_view_fixture.h"
#include <cassert>
#include <cstring>

extern "C" void fixture_touch(Locale* locale) {
    ++locale->calls;
}

extern "C" __attribute__((noinline)) int fixture_lookup(Locale* locale, View view) {
    const char* volatile requested = view.data;
    fixture_touch(locale);
    if (view.length != locale->length) {
        return 17;
    }
    volatile int result = std::memcmp(requested, locale->data, view.length);
    return result;
}

extern "C" __attribute__((noinline)) void fixture_provider(
    Output* output, Locale* locale, View view, const void* parameters, std::size_t count) {
    assert(parameters == nullptr && count == 0);
    output->first = fixture_lookup(locale, view);
    fixture_touch(locale);
    output->second = fixture_lookup(locale, view);
}
