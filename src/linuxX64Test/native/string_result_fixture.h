#pragma once
#include <cstddef>

struct String {
    std::size_t padding[FIXTURE_PADDING];
    char* data;
    std::size_t length;
    char local[FIXTURE_PADDING + 19];

    __attribute__((always_inline)) void initializeStorage() {
        data = local;
    }

    __attribute__((always_inline)) void setLength(std::size_t value) {
        length = value;
    }

    explicit String(char value) {
        initializeStorage();
        local[0] = value;
        local[1] = 0;
        setLength(1);
    }

    ~String();
};

struct Control {
    char value;
    String name() const;
};

extern "C" void fixture_consume(const String* value);
extern unsigned consumed;
extern unsigned destroyed;
