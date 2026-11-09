#pragma once
#include <stdint.h>

// Owned textual/numeric copies. No graph callbacks or borrowed game pointers cross this boundary.
typedef struct FmUiGraphBuffer {
    uint64_t address, size;
} FmUiGraphBuffer;

typedef struct FmUiGraphSeries {
    FmUiGraphBuffer label, values;
    uint32_t index, total, offset;
} FmUiGraphSeries;

typedef struct FmUiGraph {
    uint32_t available, sampleCount;
    float minimum, maximum;
    FmUiGraphBuffer series;
    uint32_t collection, total, offset;
} FmUiGraph;

typedef struct FmUiGraphVectorLayout {
    uint32_t first, last, end, stride;
} FmUiGraphVectorLayout;

typedef struct FmUiGraphLayout {
    uint64_t type;
    uint32_t supported, size, sampleCount, minimum, maximum;
    FmUiGraphVectorLayout series, values;
    uint32_t seriesLabel;
} FmUiGraphLayout;

// 0: full property; 1: series; 2: one series' values.
typedef struct FmUiGraphRequest {
    uint32_t collection, series, offset, limit;
} FmUiGraphRequest;

#ifdef __cplusplus
#include <algorithm>
#include <cstring>
#include <limits>
#include <stdexcept>
#include <string>
#include <vector>

namespace fm {
inline bool graphMember(uint32_t offset, uint32_t width, uint32_t size) {
    return width && offset <= size && width <= size - offset;
}

inline bool validGraphLayout(const FmUiGraphLayout &v) {
    if (!v.supported) return !v.type;
    const auto vector = [](const FmUiGraphVectorLayout &x, uint32_t owner) {
        return x.stride && x.stride <= INT32_MAX && x.first != x.last && x.first != x.end && x.last != x.end &&
               x.first % 8 == 0 && x.last % 8 == 0 && x.end % 8 == 0 &&
               graphMember(x.first, 8, owner) && graphMember(x.last, 8, owner) && graphMember(x.end, 8, owner);
    };
    return v.supported == 1 && v.type && v.size >= 8 && v.size <= 16 * 1024 * 1024 &&
           graphMember(v.sampleCount, 4, v.size) && graphMember(v.minimum, 4, v.size) &&
           graphMember(v.maximum, 4, v.size) && vector(v.series, v.size) && vector(v.values, v.series.stride) &&
           v.values.stride == sizeof(float) && graphMember(v.seriesLabel, 1, v.series.stride);
}

template <class T> FmUiGraphBuffer graphBuffer(const std::vector<T> &v) {
    if (v.size() > INT32_MAX / sizeof(T)) throw std::length_error("Graph copy exceeds byte-array representation");
    return {v.empty() ? 0 : reinterpret_cast<uintptr_t>(v.data()), v.size() * sizeof(T)};
}

// Shared stored-container reader; platform callbacks validate memory and copy their native strings.
struct GraphStorage {
    std::vector<FmUiGraphSeries> series;
    std::vector<std::vector<float>> values;
    std::vector<std::vector<char>> strings;

    FmUiGraphBuffer text(const std::string &value) {
        return graphBuffer(strings.emplace_back(value.begin(), value.end()));
    }

    template <class Read, class String, class Cancel>
    FmUiGraph collect(uintptr_t object, const FmUiGraphLayout &layout, const FmUiGraphRequest &request,
                      Read read, String string, Cancel cancel) {
        if (!validGraphLayout(layout) || !layout.supported || request.collection > 2 ||
            (request.collection && (!request.limit || request.limit > INT32_MAX || request.offset > INT32_MAX)))
            throw std::runtime_error("Invalid graph observation layout or selection");
        auto scalar = [&](uintptr_t at, auto &value) {
            if (at > INTPTR_MAX - sizeof(value) || !read(at, &value, sizeof(value)))
                throw std::runtime_error("Graph stored value is unreadable");
        };
        struct Range { uintptr_t first; uint32_t count; };
        auto range = [&](uintptr_t at, const FmUiGraphVectorLayout &v) {
            uintptr_t first, last, end;
            scalar(at + v.first, first);
            scalar(at + v.last, last);
            scalar(at + v.end, end);
            if (last < first || end < last || end > INTPTR_MAX || (last - first) % v.stride ||
                (end - first) % v.stride || (last - first) / v.stride > INT32_MAX || (!first && end))
                throw std::runtime_error("Invalid graph stored vector bounds");
            return Range{first, static_cast<uint32_t>((last - first) / v.stride)};
        };
        auto bounds = [&](uint32_t count, bool selected) {
            const auto offset = selected ? request.offset : 0;
            const auto length = selected ? std::min(request.limit, count - std::min(offset, count)) : count;
            return std::pair<uint32_t, uint32_t>{offset, length};
        };
        FmUiGraph output{};
        output.available = 1;
        output.collection = request.collection;
        scalar(object + layout.sampleCount, output.sampleCount);
        scalar(object + layout.minimum, output.minimum);
        scalar(object + layout.maximum, output.maximum);
        auto page = [&](uint32_t count) {
            if (request.collection) { output.total = count; output.offset = request.offset; }
        };
        if (request.collection <= 2) {
            const auto rows = range(object, layout.series);
            if (request.collection == 2 && request.series >= rows.count)
                throw std::out_of_range("Graph series index is out of range");
            const auto selected = bounds(rows.count, request.collection == 1);
            const auto first = request.collection == 2 ? request.series : selected.first;
            const auto count = request.collection == 2 ? 1u : selected.second;
            if (count > INT32_MAX / sizeof(FmUiGraphSeries))
                throw std::length_error("Graph series copy exceeds byte-array representation");
            if (request.collection == 1) page(rows.count);
            series.reserve(count);
            values.reserve(count);
            for (uint32_t i = 0; i < count; ++i) {
                cancel();
                const auto at = rows.first + size_t(first + i) * layout.series.stride;
                const auto samples = range(at, layout.values);
                const auto slice = bounds(samples.count, request.collection == 2);
                if (slice.second > INT32_MAX / sizeof(float))
                    throw std::length_error("Graph sample copy exceeds byte-array representation");
                auto &copied = values.emplace_back(slice.second);
                if (!copied.empty() && !read(samples.first + size_t(slice.first) * sizeof(float), copied.data(), copied.size() * sizeof(float)))
                    throw std::runtime_error("Graph sample storage is unreadable");
                series.push_back({text(string(at + layout.seriesLabel)), graphBuffer(copied), first + i, samples.count, slice.first});
                if (request.collection == 2) page(samples.count);
            }
        }
        output.series = graphBuffer(series);
        return output;
    }
};
} // namespace fm
#endif
