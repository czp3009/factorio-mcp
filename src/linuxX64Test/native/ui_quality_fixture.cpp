#include "ui_quality.h"
#include <cassert>
#include <cerrno>
#include <cstddef>
#include <stdexcept>
#include <string>
#include <typeinfo>

struct Widget {
    virtual ~Widget() = default;
    char padding[23]{};
};

struct Prototype {
    virtual ~Prototype() = default;
    char padding[31]{};

    struct Name {
        const char *data;
        uint64_t size;
    } name{};
};

template <class T> struct Provider {
    virtual void unrelated() const = 0;
    virtual T condition() const = 0;
};

template <class T> struct Button : Widget, Provider<T> {
    T value{};
    bool fail{};
    mutable unsigned calls{};

    void unrelated() const override {
        assert(false);
    }

    T condition() const override {
        ++calls;
        if (fail)
            throw std::runtime_error("fixture");
        return value;
    }
};

template <class T> static void verify() {
    Button<T> button;
    Prototype prototype;
    std::string text = "raw-quality";
    prototype.name = {text.data(), text.size()};
    Prototype *entries[]{nullptr, &prototype};

    struct Registry {
        Prototype **last;
        uint64_t padding;
        Prototype **first;
    } registry{entries + 2, 0, entries};

    FmLinuxQualityLayout layout{};
    layout.widgetType = reinterpret_cast<uintptr_t>(&typeid(Widget));
    layout.providerType = reinterpret_cast<uintptr_t>(&typeid(Provider<T>));
    layout.registry = reinterpret_cast<uintptr_t>(&registry);
    layout.registrySize = sizeof(registry);
    layout.first = offsetof(Registry, first);
    layout.last = offsetof(Registry, last);
    layout.getter = 1; // Synthetic provider's independently declared slot.
    layout.width = sizeof(T);
    layout.quality = sizeof(T) - 2;
    layout.comparison = sizeof(T) - 1;
    FmLinuxIdentityLayout identity{};
    identity.widgetType = layout.widgetType;
    identity.providerType = layout.providerType;
    identity.prototypeSlot = 1;
    identity.name = reinterpret_cast<uintptr_t>(&prototype.name) - reinterpret_cast<uintptr_t>(&prototype);
    identity.minimumExtent = identity.qualitySize = sizeof(prototype);
    identity.stringSize = sizeof(prototype.name);
    identity.stringData = offsetof(Prototype::Name, data);
    identity.stringLength = offsetof(Prototype::Name, size);
    assert(validQualityLayout(layout) && validIdentityLayout(identity));
    FmLinuxUiQuality output{};
    auto read = [&] {
        return collectQuality(reinterpret_cast<uintptr_t>(static_cast<Widget *>(&button)), layout, identity, output);
    };
    auto value = [&](unsigned quality, unsigned comparison) {
        button.value = (T(quality) << (layout.quality * 8)) | (T(comparison) << (layout.comparison * 8));
    };
    value(1, 255);
    assert(read() == 0 && output.available && output.quality == 1 && output.comparison == 255 && output.lookup == 1);
    assert(std::string(output.name, output.nameSize) == text && !output.truncated && button.calls == 1);
    value(0, 0);
    assert(read() == 0 && output.available && !output.quality && !output.comparison && !output.lookup &&
           !output.nameSize);
    value(2, 9);
    assert(read() == 0 && output.lookup == 2 && !output.nameSize);
    value(1, 2);
    text = std::string(254, 'x') + "\xc3\xa9";
    prototype.name = {text.data(), text.size()};
    assert(read() == 0 && output.truncated && output.nameSize == 254);
    registry.last = entries + 1;
    assert(read() == 0 && output.lookup == 2);
    registry.last = reinterpret_cast<Prototype **>(reinterpret_cast<uintptr_t>(entries) + 1);
    assert(read() == EPROTO);
    registry.last = entries + 2;
    button.fail = true;
    assert(read() == EFAULT);
    layout.comparison = layout.width;
    assert(!validQualityLayout(layout));
    layout.comparison = layout.quality;
    assert(!validQualityLayout(layout));
}

int main() {
    verify<uint16_t>();
    verify<uint32_t>();
    verify<uint64_t>();
    assert(validQualityLayout({}));
}
