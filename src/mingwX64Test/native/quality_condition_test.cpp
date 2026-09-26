#include "widget_properties.h"
#include <array>
#include <cassert>
#include <cstring>
#include <stdexcept>
#include <string>

namespace {
unsigned char widget[96]{};
int widgetType, providerType;
bool supported = true, wrongReturn = false;
unsigned qualityOffset, comparisonOffset, calls;
unsigned char prototype[96]{};
std::string qualityName = "fixture-quality";

size_t stringSize(const void *value) {
    assert(value == prototype + 17);
    return qualityName.size();
}

const char *stringData(const void *value) {
    assert(value == prototype + 17);
    return qualityName.data();
}

void *cast(void *value, long adjustment, void *source, void *target, int reference) {
    assert(value == widget && adjustment == 0 && source == &widgetType && target == &providerType && reference == 0);
    return supported ? widget + 41 : nullptr;
}

void *getID(const void *receiver, void *storage) {
    assert(receiver == widget + 41);
    ++calls;
    auto *bytes = static_cast<unsigned char *>(storage);
    bytes[qualityOffset] = 19;
    bytes[comparisonOffset] = 251;
    return wrongReturn ? nullptr : storage;
}
} // namespace

int main() {
    Symbols symbols{};
    symbols.address[DynamicCast] = reinterpret_cast<uint64_t>(&cast);
    symbols.address[WidgetType] = reinterpret_cast<uint64_t>(&widgetType);
    symbols.address[StringSize] = reinterpret_cast<uint64_t>(&stringSize);
    symbols.address[StringData] = reinterpret_cast<uint64_t>(&stringData);
    auto &layout = symbols.conditions;
    layout.supported = 1;
    layout.type = reinterpret_cast<uint64_t>(&providerType);
    layout.size = 64;
    std::array<const void *, 32> prototypes{};
    prototypes[19] = prototype;
    const void *registry[] = {nullptr, prototypes.data(), prototypes.data() + prototypes.size()};
    layout.registry = reinterpret_cast<uintptr_t>(registry);
    layout.first = sizeof(void *);
    layout.last = 2 * sizeof(void *);
    layout.name = 17;
    std::array<uintptr_t, 10> table{};
    auto *pointer = table.data();
    memcpy(widget + 41, &pointer, sizeof(pointer));
    auto collect = [&] {
        FmNode node{};
        collectQualityCondition(symbols, widget, node);
        return node;
    };
    for (unsigned shift : {0u, 4u}) {
        layout.getter = (shift + 2) * sizeof(void *);
        table[shift + 2] = reinterpret_cast<uintptr_t>(&getID);
        layout.quality = qualityOffset = shift + 7;
        layout.comparison = comparisonOffset = shift + 13;
        const auto result = collect();
        assert(result.properties == 4096 && result.conditionQuality == 19 && result.conditionComparison == 251);
        assert(result.conditionLookup == 1 && !strcmp(result.conditionName, "fixture-quality"));
    }
    qualityName = std::string(254, 'a') + "\xe7\x95\x8c";
    auto truncated = collect();
    assert(truncated.conditionNameTruncated && strlen(truncated.conditionName) == 254);
    prototypes[19] = nullptr;
    assert(collect().conditionLookup == 0);
    registry[2] = prototypes.data() + 19;
    assert(collect().conditionLookup == 2);
    registry[2] = prototypes.data() + prototypes.size();
    supported = false;
    const auto before = calls;
    assert(collect().properties == 0 && calls == before);
    supported = true;
    auto rejects = [&] {
        bool rejected = false;
        try {
            collect();
        } catch (const std::runtime_error &) {
            rejected = true;
        }
        assert(rejected);
    };
    wrongReturn = true;
    rejects();
    wrongReturn = false;
    layout.size = 65;
    rejects();
    layout.size = 64;
    layout.quality = 64;
    rejects();
    layout.quality = qualityOffset;
    layout.getter = 3;
    rejects();
    layout.supported = 0;
    assert(collect().properties == 0);
}
