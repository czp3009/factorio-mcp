#pragma once
#include <cstddef>

struct String {
    std::size_t padding[FIXTURE_PADDING];
    char* data;
    std::size_t length;
    char local[24];

    __attribute__((always_inline)) String() : data(local), length(0) {
        local[0] = 0;
    }

    __attribute__((always_inline)) explicit String(char value) : data(local), length(1) {
        local[0] = value;
        local[1] = 0;
    }

    ~String();
};

struct Borrowed {
    Borrowed* self;
    void* values[5];
};

String optional(void*, void*, void*, void*, void*);
extern "C" bool fixture_lookup(void*, void*, void*, void*, void*);
extern "C" void fixture_borrow(Borrowed*);
extern "C" void fixture_consume(const String*);
extern unsigned consumed;
extern unsigned destroyed;
