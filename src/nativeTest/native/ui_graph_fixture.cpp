#include "../../nativeMain/native/ui_graph.h"
#include "../../nativeMain/native/ui_stored_identity.h"
#include <cassert>
#include <cstddef>
#include <cmath>

namespace {
struct Series { uint64_t padding = 0; std::vector<float> values; std::string label; };
struct Axis { float absolute, normalized; const char *label; };
struct Mark { std::string text; uint32_t sample; std::string caption; };
struct Graph {
    uint64_t padding = 0;
    std::vector<Axis> vertical;
    float maximum = 10;
    uint32_t sampleCount = 5;
    std::vector<Series> series;
    float minimum = -2;
    std::vector<Axis> horizontal;
    std::vector<Mark> marks;
};

// Synthetic container layout is deliberately independent of either game's platform layouts.
struct Vector { uintptr_t first, last, end; };
FmUiGraphVectorLayout vector(size_t member, size_t stride) {
    static_assert(sizeof(std::vector<int>) == sizeof(Vector));
    return {static_cast<uint32_t>(member + offsetof(Vector, first)), static_cast<uint32_t>(member + offsetof(Vector, last)),
            static_cast<uint32_t>(member + offsetof(Vector, end)), static_cast<uint32_t>(stride)};
}

FmUiGraphLayout layout() {
    FmUiGraphLayout l{};
    l.supported = 1;
    l.type = 0x1000;
    l.size = sizeof(Graph);
    l.sampleCount = offsetof(Graph, sampleCount);
    l.minimum = offsetof(Graph, minimum);
    l.maximum = offsetof(Graph, maximum);
    l.series = vector(offsetof(Graph, series), sizeof(Series));
    l.values = vector(offsetof(Series, values), sizeof(float));
    l.seriesLabel = offsetof(Series, label);
    return l;
}

template <class T> const T *data(const FmUiGraphBuffer &v) { return reinterpret_cast<const T *>(v.address); }
std::string text(const FmUiGraphBuffer &v) { return {data<char>(v), static_cast<size_t>(v.size)}; }
}

int main() {
    auto l = layout();
    assert(fm::validGraphLayout(l));
    auto invalid = l;
    invalid.seriesLabel = invalid.series.stride;
    assert(!fm::validGraphLayout(invalid));
    invalid = l;
    invalid.values.stride = 8;
    assert(!fm::validGraphLayout(invalid));
    Graph source;
    source.series.push_back({0, {0, 1.5f, -2, 8, std::numeric_limits<float>::infinity()}, "[entity=original] original label"});
    source.series.push_back({0, {9, 7, 5}, "Repeated label"});
    source.series.push_back({0, {}, "Repeated label"});
    source.horizontal.push_back({15000, 0, "15k"});
    source.horizontal.push_back({0, 1, nullptr});
    source.vertical.push_back({0.25f, 0.5f, "original/m"});
    source.marks.push_back({"stored description", 4, "stored caption"});
    size_t sampleBytes = 0, stringsRead = 0;
    auto read = [&](uintptr_t address, void *out, size_t count) {
        if (address == reinterpret_cast<uintptr_t>(source.series[0].values.data() + 1)) sampleBytes += count;
        std::memcpy(out, reinterpret_cast<const void *>(address), count);
        return true;
    };
    auto string = [&](uintptr_t address) { ++stringsRead; return *reinterpret_cast<const std::string *>(address); };
    fm::GraphStorage complete;
    const auto snapshot = complete.collect(reinterpret_cast<uintptr_t>(&source), l, {}, read, string, [] {});
    assert(snapshot.available && snapshot.sampleCount == 5 && snapshot.minimum == -2 && snapshot.maximum == 10);
    assert(snapshot.series.size == 3 * sizeof(FmUiGraphSeries));
    assert(text(data<FmUiGraphSeries>(snapshot.series)[0].label) == source.series[0].label);
    assert(std::isinf(data<float>(data<FmUiGraphSeries>(snapshot.series)[0].values)[4]));
    source.series[0].values[0] = 42;
    assert(data<float>(data<FmUiGraphSeries>(snapshot.series)[0].values)[0] == 0);

    stringsRead = 0;
    fm::GraphStorage page;
    const auto sliced = page.collect(reinterpret_cast<uintptr_t>(&source), l, {2, 0, 1, 2}, read, string, [] {});
    assert(sliced.collection == 2 && sliced.total == 5 && sliced.offset == 1 && sampleBytes == 2 * sizeof(float));
    assert(stringsRead == 1);
    assert(data<float>(data<FmUiGraphSeries>(sliced.series)[0].values)[0] == 1.5f);
    fm::GraphStorage beyond;
    const auto empty = beyond.collect(reinterpret_cast<uintptr_t>(&source), l, {1, 0, 99, 2}, read, string, [] {});
    assert(empty.total == 3 && empty.offset == 99 && empty.series.size == 0);
    bool rejected = false;
    try { fm::GraphStorage{}.collect(reinterpret_cast<uintptr_t>(&source), l, {2, 3, 0, 1}, read, string, [] {}); }
    catch (const std::out_of_range &) { rejected = true; }
    assert(rejected);
    rejected = false;
    try { fm::GraphStorage{}.collect(reinterpret_cast<uintptr_t>(&source), l, {}, read, string,
        [] { throw std::runtime_error("cancelled"); }); }
    catch (const std::runtime_error &) { rejected = true; }
    assert(rejected);

    struct Reference { uint64_t padding; uint16_t id; } reference{0, 1};
    uintptr_t prototypes[] = {0, 0x123456};
    Vector registry{reinterpret_cast<uintptr_t>(prototypes), reinterpret_cast<uintptr_t>(prototypes + 2), 0};
    FmUiStoredPrototypeLayout identity{0x1000, reinterpret_cast<uintptr_t>(&registry), 1, sizeof(reference), offsetof(Reference, id), 1,
                                     offsetof(Vector, first), offsetof(Vector, last), 0};
    assert(fm::validStoredPrototypeLayout(identity));
    assert(fm::storedPrototype(reinterpret_cast<uintptr_t>(&reference), identity, read) == prototypes[1]);
    reference.id = 2;
    rejected = false;
    try { fm::storedPrototype(reinterpret_cast<uintptr_t>(&reference), identity, read); }
    catch (const std::runtime_error &) { rejected = true; }
    assert(rejected);
}
