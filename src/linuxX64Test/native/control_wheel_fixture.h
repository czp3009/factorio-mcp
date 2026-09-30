#pragma once
#include <cstddef>
#include <cstdint>
#include <cstring>

extern "C" void* fixture_allocate(std::size_t size);

struct String {
    std::uint64_t padding[FIXTURE_PADDING];
    char* data;
    std::size_t length;
    union Storage {
        char local[16];
        std::size_t capacity;
    } storage;

    template<std::size_t N> String(const char (&value)[N]) : length(N - 1) {
        if constexpr (N <= sizeof(storage.local)) {
            data = storage.local;
        } else {
            data = static_cast<char*>(fixture_allocate(N));
            storage.capacity = N - 1;
        }
        std::memcpy(data, value, N);
    }

    String(const String& other);
    ~String();
};

extern "C" String* fixture_prefix(String* value, std::size_t position, std::size_t replaced,
                                   const char* text, std::size_t length);
