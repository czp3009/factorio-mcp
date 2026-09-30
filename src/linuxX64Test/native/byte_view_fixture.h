#pragma once
#include <cstddef>

struct View {
#if FIXTURE_PADDING == 1
    const char* data;
    std::size_t length;
#else
    std::size_t length;
    const char* data;
#endif
};

struct Locale {
    std::size_t padding[FIXTURE_PADDING];
    const char* data;
    std::size_t length;
    unsigned calls;
};

struct Context {
    std::size_t padding[FIXTURE_PADDING];
    Locale* selected;
};

struct Output {
    int first;
    int second;
};

extern "C" void fixture_touch(Locale* locale);
extern "C" int fixture_lookup(Locale* locale, View view);
extern "C" void fixture_provider(Output* output, Locale* locale, View view, const void* parameters, std::size_t count);
extern "C" void fixture_optional(Output* output, Context* context, View view, const void* parameters, std::size_t count);
