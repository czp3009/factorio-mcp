#include "string_storage_fixture.h"
#include <cassert>
#include <cstdlib>

void* deleted_pointer = nullptr;
std::size_t deleted_size = 0;
unsigned deletion_count = 0;
std::size_t deletion_sizes[16]{};

extern "C" void fixture_delete(void* pointer) {
    deleted_pointer = pointer;
    deleted_size = 0;
    assert(deletion_count < 16);
    deletion_sizes[deletion_count] = 0;
    ++deletion_count;
    std::free(pointer);
}

extern "C" void fixture_delete_sized(void* pointer, std::size_t size) {
    deleted_pointer = pointer;
    deleted_size = size;
    assert(deletion_count < 16);
    deletion_sizes[deletion_count] = size;
    ++deletion_count;
    std::free(pointer);
}
