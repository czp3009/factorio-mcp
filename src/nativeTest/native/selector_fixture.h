#pragma once
#include "../../nativeMain/native/selector_payload.h"
#include "../../nativeMain/native/ui_fields.h"

inline std::vector<char> selectorBytes(const std::vector<fm::ui::Step> &path) {
    std::vector<char> bytes;
    auto word = [&](uint32_t value) {
        for (unsigned shift = 0; shift < 32; shift += 8)
            bytes.push_back(static_cast<char>(value >> shift));
    };
    word(static_cast<uint32_t>(path.size()));
    for (const auto &step : path) {
        word(step.child);
        word(step.position);
        word(step.enabled ? uint32_t(*step.enabled) : UINT32_MAX);
        word(step.visible ? uint32_t(*step.visible) : UINT32_MAX);
        word(step.guiMatch >= 0);
        for (const auto &text : {step.type, step.text, step.prototypeName, step.prototypeType}) {
            word(text ? static_cast<uint32_t>(text->size()) : UINT32_MAX);
            if (text)
                bytes.insert(bytes.end(), text->begin(), text->end());
        }
    }
    return bytes;
}

template <class Wire> struct SelectorFixture {
    Wire value{};
    std::vector<fm::ui::Step> path = std::vector<fm::ui::Step>(1);
    std::vector<char> bytes;

    SelectorFixture() {
        value.maxDepth = INT32_MAX;
        value.fields = FM_UI_ALL_FIELDS;
    }

    explicit SelectorFixture(const Wire &source) : value(source), path(fm::ui::selectorPath(source.path, source.count)) {}

    operator const Wire &() {
        return *wire();
    }

    Wire *wire() {
        bytes = selectorBytes(path);
        value.count = static_cast<decltype(value.count)>(path.size());
        value.path = {reinterpret_cast<uintptr_t>(bytes.data()), bytes.size()};
        return &value;
    }
};
