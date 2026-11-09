#include "widget_properties.h"
#include <array>
#include <cassert>
#include <cstddef>
#include <cstring>
#include <stdexcept>
#include <string>

namespace {
struct Prototype {
    unsigned char prefix[41];
    std::string name;
};

struct Quality {
    unsigned char prefix[9];
    std::string name;
};

Prototype prototype{{}, "test-item"};
Quality quality{{}, "rare"};
unsigned char widget[96]{};
int widgetType, providerType;
bool empty{}, qualityEmpty{}, supported{true};
int calls{}, qualityCalls{};
std::string prototypeTypeName = "class FixturePrototype", qualityTypeName = "class QualityPrototype";

void *cast(void *value, long adjustment, void *source, void *target, int reference) {
    assert(value == widget && adjustment == 0 && source == &widgetType && target == &providerType && reference == 0);
    return supported ? widget + 37 : nullptr;
}

const void *getPrototype(const void *receiver) {
    assert(receiver == widget + 37);
    ++calls;
    return empty ? nullptr : &prototype;
}

const void *getQuality(const void *receiver) {
    assert(receiver == widget + 37);
    ++qualityCalls;
    return qualityEmpty ? nullptr : &quality;
}

size_t stringSize(const void *value) {
    return static_cast<const std::string *>(value)->size();
}

const char *stringData(const void *value) {
    return static_cast<const std::string *>(value)->data();
}

const void *typeId(const void *value) {
    assert(value == &prototype || value == &quality);
    return value;
}

const char *typeName(const void *value) {
    return value == &prototype ? prototypeTypeName.c_str() : qualityTypeName.c_str();
}
} // namespace

int main() {
    Symbols symbols{};
    symbols.address[DynamicCast] = reinterpret_cast<uint64_t>(&cast);
    symbols.address[WidgetType] = reinterpret_cast<uint64_t>(&widgetType);
    symbols.address[StringSize] = reinterpret_cast<uint64_t>(&stringSize);
    symbols.address[StringData] = reinterpret_cast<uint64_t>(&stringData);
    symbols.address[TypeId] = reinterpret_cast<uint64_t>(&typeId);
    symbols.address[TypeName] = reinterpret_cast<uint64_t>(&typeName);
    symbols.slots.supported = 1;
    symbols.slots.type = reinterpret_cast<uint64_t>(&providerType);
    symbols.slots.name = offsetof(Prototype, name);
    symbols.slots.qualityName = offsetof(Quality, name);
    std::array<uintptr_t, 10> table{};
    auto *pointer = table.data();
    memcpy(widget + 37, &pointer, sizeof(pointer));
    ObservationStorage storage;
    auto collect = [&] {
        FmNode node{};
        collectSlotIdentity(symbols, widget, node, storage);
        return node;
    };
    for (unsigned shift : {0u, 3u}) {
        symbols.slots.prototype = (shift + 4) * sizeof(void *);
        symbols.slots.quality = (shift + 1) * sizeof(void *);
        table[shift + 4] = reinterpret_cast<uintptr_t>(&getPrototype);
        table[shift + 1] = reinterpret_cast<uintptr_t>(&getQuality);
        auto result = collect();
        assert(result.properties == (32 | 512) && !strcmp(result.prototypeName, "test-item"));
        assert(observationText(result.prototypeType) == "class FixturePrototype");
        assert(!strcmp(result.qualityName, "rare") && observationText(result.qualityType) == "class QualityPrototype");
        empty = true;
        result = collect();
        assert(!result.prototypeName[0] && observationText(result.prototypeType).empty() && !strcmp(result.qualityName, "rare"));
        qualityEmpty = true;
        assert(!collect().qualityName[0]);
        empty = qualityEmpty = false;
    }
    int before = calls;
    supported = false;
    assert(collect().properties == 0 && calls == before && qualityCalls == before);
    supported = true;
    prototype.name = std::string(254, 'x') + "\xe7\x95\x8c";
    quality.name = std::string(254, 'y') + "\xe7\x95\x8c";
    prototypeTypeName = std::string(2048, 'P');
    qualityTypeName = std::string(3072, 'Q');
    auto result = collect();
    assert(result.identityTruncated == 5 && strlen(result.prototypeName) == 254 && strlen(result.qualityName) == 254);
    assert(observationText(result.prototypeType) == prototypeTypeName);
    assert(observationText(result.qualityType) == qualityTypeName);
    symbols.slots.quality = 3;
    bool rejected = false;
    try {
        collect();
    } catch (const std::runtime_error &) {
        rejected = true;
    }
    assert(rejected);
    symbols.slots.supported = 0;
    assert(collect().properties == 0);
}
