#include "ui_identity.h"
#include <cassert>
#include <cerrno>
#include <cstddef>
#include <cstdint>
#include <stdexcept>
#include <string>
#include <typeinfo>

struct NativeString {
    uint64_t padding = 0;
    const char *data = nullptr;
    uint64_t length = 0;
};

struct Prototype {
    virtual ~Prototype() = default;
    unsigned char padding[FIXTURE_PADDING + 1]{};
    NativeString name;
};

struct OtherBase {
    virtual ~OtherBase() = default;
    uintptr_t padding[3]{};
};

struct Quality : OtherBase, Prototype {};

struct Widget {
    virtual ~Widget() = default;
    unsigned char padding[FIXTURE_PADDING + 3]{};
};

struct Provider {
    virtual const Quality *quality() const = 0;
    virtual const Prototype *prototype() const = 0;
};

struct Values {
    const Quality *qualityValue = nullptr;
    const Prototype *prototypeValue = nullptr;
    mutable unsigned calls = 0;
    bool fail = false;
};

struct Button : Widget, Provider, Values {
    const Quality *quality() const override {
        ++calls;
        return qualityValue;
    }

    const Prototype *prototype() const override {
        ++calls;
        if (fail)
            throw std::runtime_error("fixture");
        return prototypeValue;
    }
};

struct VirtualButton : Widget, virtual Provider, Values {
    const Quality *quality() const override {
        ++calls;
        return qualityValue;
    }

    const Prototype *prototype() const override {
        ++calls;
        return prototypeValue;
    }
};

static FmLinuxIdentityLayout layout() {
    Prototype prototype;
    Quality quality;
    FmLinuxIdentityLayout result{};
    result.widgetType = reinterpret_cast<uintptr_t>(&typeid(Widget));
    result.providerType = reinterpret_cast<uintptr_t>(&typeid(Provider));
    // The reordered slots and member locations describe only these synthetic types.
    result.prototypeSlot = 1;
    result.qualitySlot = 0;
    result.name = reinterpret_cast<uintptr_t>(&prototype.name) - reinterpret_cast<uintptr_t>(&prototype);
    result.minimumExtent = sizeof(Prototype);
    result.qualityBase = reinterpret_cast<uintptr_t>(static_cast<Prototype *>(&quality)) - reinterpret_cast<uintptr_t>(&quality);
    result.qualitySize = sizeof(Quality);
    result.stringSize = sizeof(NativeString);
    result.stringData = offsetof(NativeString, data);
    result.stringLength = offsetof(NativeString, length);
    assert(result.qualityBase > 0);
    return result;
}

template <class T> void check() {
    T button;
    Prototype prototype;
    Quality quality;
    std::string name = "raw-item-name";
    std::string qualityName = "raw-quality-name";
    prototype.name = {0, name.data(), name.size()};
    quality.name = {0, qualityName.data(), qualityName.size()};
    button.prototypeValue = &prototype;
    button.qualityValue = &quality;
    FmLinuxUiIdentity output{};
    const auto source = reinterpret_cast<uintptr_t>(static_cast<Widget *>(&button));
    auto observe = [&] { return collectIdentity(source, layout(), output); };
    assert(observe() == 0 && output.available && button.calls == 2);
    assert(output.prototype.flags == 1 && output.quality.flags == 1);
    assert(std::string(output.prototype.name, output.prototype.nameSize) == name);
    assert(std::string(output.quality.name, output.quality.nameSize) == qualityName);
    assert(std::string(output.prototype.type, output.prototype.typeSize) == "Prototype");
    assert(std::string(output.quality.type, output.quality.typeSize) == "Quality");
    button.prototypeValue = nullptr;
    button.qualityValue = nullptr;
    assert(observe() == 0 && output.available && !output.prototype.flags && !output.quality.flags);
    button.prototypeValue = &prototype;
    name = std::string(254, 'a') + "\xe4\xb8\xad";
    prototype.name = {0, name.data(), name.size()};
    assert(observe() == 0 && output.prototype.flags == 3 && output.prototype.nameSize == 254);
    name.clear();
    prototype.name = {0, name.data(), 0};
    assert(observe() == 0 && output.prototype.flags == 1 && output.prototype.nameSize == 0);
    prototype.name.length = 65537;
    assert(observe() == EPROTO);
    prototype.name = {0, reinterpret_cast<const char *>(1), 1};
    assert(observe() == EFAULT);
}

int main() {
    check<Button>();
    check<VirtualButton>();
    Widget plain;
    FmLinuxUiIdentity output{};
    assert(collectIdentity(reinterpret_cast<uintptr_t>(&plain), layout(), output) == 0 && !output.available);
    Button throwing;
    throwing.fail = true;
    assert(collectIdentity(reinterpret_cast<uintptr_t>(static_cast<Widget *>(&throwing)), layout(), output) == EFAULT);
    assert(validIdentityLayout({}));
    assert(collectIdentity(0, {}, output) == 0 && !output.available);
    assert(collectIdentity(1, layout(), output) == EFAULT);
    auto invalid = layout();
    invalid.prototypeSlot = invalid.qualitySlot;
    assert(!validIdentityLayout(invalid));
    invalid = layout();
    invalid.name = invalid.minimumExtent;
    assert(!validIdentityLayout(invalid));
    invalid = layout();
    invalid.stringLength = invalid.stringData;
    assert(!validIdentityLayout(invalid));
}
