#include "string_storage_fixture.h"
#include <cassert>
#include <cstdlib>
#include <cstring>
#include <initializer_list>
#include <new>

struct String {
    std::size_t padding[FIXTURE_PADDING];
    char* data;
    std::size_t length;
    union {
        char local[FIXTURE_PADDING + 17];
        std::size_t capacity;
    };
};

struct Owner {
    std::size_t padding[FIXTURE_PADDING + 1];
    String* value;

    __attribute__((noinline)) void dispose() {
        String* const original = value;
        if (original) {
            if (original->data != original->local) {
                fixture_delete_sized(original->data, original->capacity + 1);
            }
            fixture_delete_sized(original, sizeof(String));
        }
    }
};

extern "C" __attribute__((noinline)) void fixture_string_destroy(String* value) {
    if (value->data != value->local) {
        fixture_delete(value->data);
    }
}

extern "C" {
extern const std::size_t fixture_string_size = sizeof(String);
extern const std::size_t fixture_string_data = offsetof(String, data);
extern const std::size_t fixture_string_local = offsetof(String, local);
}

int main() {
    Owner owner{};
    owner.dispose();
    assert(deletion_count == 0);
    for (bool heap : {false, true}) {
        String value{};
        value.data = heap ? static_cast<char*>(std::malloc(73)) : value.local;
        value.length = heap ? 72 : 2;
        if (heap) {
            value.capacity = 72;
        }
        std::memset(value.data, 'x', value.length);
        value.data[value.length] = 0;
        const auto before = deletion_count;
        fixture_string_destroy(&value);
        assert(deletion_count == before + (heap ? 1 : 0));
        if (heap) {
            assert(deleted_pointer == value.data && deleted_size == 0);
        } else {
            assert(std::strcmp(value.data, "xx") == 0);
        }
        owner.value = new (std::malloc(sizeof(String))) String{};
        owner.value->data = heap ? static_cast<char*>(std::malloc(73)) : owner.value->local;
        if (heap) {
            owner.value->capacity = 72;
        }
        const auto before_owner = deletion_count;
        owner.dispose();
        assert(deletion_count == before_owner + (heap ? 2 : 1));
        if (heap) {
            assert(deletion_sizes[before_owner] == 73);
        }
        assert(deleted_pointer == owner.value && deleted_size == sizeof(String));
    }
}
