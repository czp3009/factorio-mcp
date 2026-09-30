#pragma once
#include <cstddef>

extern "C" void fixture_delete(void* pointer);
extern "C" void fixture_delete_sized(void* pointer, std::size_t size);
extern void* deleted_pointer;
extern std::size_t deleted_size;
extern unsigned deletion_count;
extern std::size_t deletion_sizes[16];
