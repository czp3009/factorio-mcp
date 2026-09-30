#include <cassert>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <string>
#include <string_view>
#include <vector>

struct RegistryEntry {
    std::uint64_t padding[FIXTURE_PADDING];
    std::string name;
};

extern "C" {
std::vector<RegistryEntry*> fixture_registry;
unsigned char fixture_registry_ready = 1;
extern const std::size_t fixture_registry_entry_size = sizeof(RegistryEntry);
extern const std::size_t fixture_registry_name_offset = offsetof(RegistryEntry, name);

__attribute__((noinline)) RegistryEntry* fixture_find_name(std::string_view name) {
    if (!fixture_registry_ready) return nullptr;
    for (auto* entry : fixture_registry) {
        if (entry->name.size() == name.size() &&
            (name.empty() || std::memcmp(name.data(), entry->name.data(), name.size()) == 0)) {
            return entry;
        }
    }
    return nullptr;
}
}

int main() {
    RegistryEntry a{{}, "alpha"};
    RegistryEntry b{{}, "bravo"};
    RegistryEntry c{{}, ""};
    assert(fixture_find_name("alpha") == nullptr);
    fixture_registry = {&a, &b, &c};
    assert(fixture_find_name("alpha") == &a);
    assert(fixture_find_name("bravo") == &b);
    assert(fixture_find_name("") == &c);
    assert(fixture_find_name("other") == nullptr);
    assert(fixture_find_name("longer") == nullptr);
}
