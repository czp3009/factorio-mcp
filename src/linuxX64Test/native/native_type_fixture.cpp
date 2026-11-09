#include "native_type.h"
#include <array>
#include <cassert>
#include <string>
#include <typeinfo>

template <int Depth> struct NestedType {
    using Type = std::array<typename NestedType<Depth - 1>::Type, 1>;
};

template <> struct NestedType<0> {
    using Type = int;
};

int main() {
    struct TypeInfo {
        uintptr_t table = 0;
        const char *name = nullptr;
    } info;
    std::array<uintptr_t, 3> table{0, reinterpret_cast<uintptr_t>(&info), 0};
    const uintptr_t object = reinterpret_cast<uintptr_t>(table.data() + 2);
    ObservationStrings strings;
    FmLinuxObservationBuffer output{};
    uint32_t size = 0;
    bool truncated = true;
    std::string encoded = typeid(NestedType<24>::Type).name();
    int status = -1;
    const std::unique_ptr<char, decltype(&std::free)> decoded(
        abi::__cxa_demangle(encoded.c_str(), nullptr, nullptr, &status), &std::free);
    assert(status == 0 && decoded);
    std::string expected = decoded.get();
    assert(expected.size() > 255);
    info.name = encoded.c_str();
    assert(fm::readTypeName(reinterpret_cast<uintptr_t>(&object), output, size, truncated, strings) == 0);
    assert(!truncated && size == expected.size() && observationText(output) == expected);
    encoded.assign(encoded.size(), 'X');
    for (int index = 0; index < 128; ++index)
        strings.emplace_back(4096, 'Y');
    assert(observationText(output) == expected);

    expected.assign(3072, '@');
    info.name = expected.c_str();
    assert(fm::readTypeName(reinterpret_cast<uintptr_t>(&object), output, size, truncated, strings) == 0);
    assert(!truncated && observationText(output) == expected);
    assert(fm::readTypeName(1, output, size, truncated, strings) == EFAULT);
    assert(output.address == 0 && output.size == 0 && size == 0);
}
