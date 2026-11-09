#include "selector_fixture.h"
#include <cassert>
#include <string>

template <class Operation> void rejects(Operation operation) {
    bool rejected = false;
    try {
        operation();
    } catch (const std::exception &) {
        rejected = true;
    }
    assert(rejected);
}

int main() {
    fm::RequestStorage storage;
    const auto empty = storage.reserve(0);
    assert(!empty.address && !empty.size && storage.owns(empty));
    const auto first = storage.reserve(512);
    assert(first.size == 512 && storage.owns(first));
    assert(storage.owns({first.address + 511, 1}));
    assert(!storage.owns({first.address + 511, 2}));
    assert(!storage.owns({first.address - 1, 1}));
    const auto large = storage.reserve(17 * 1024 * 1024);
    assert(large.size == 17 * 1024 * 1024 && storage.owns(large) && !storage.owns(first));
    storage.clear();
    assert(!storage.owns(large));
    rejects([&] { storage.reserve(uint32_t(INT32_MAX) + 1); });

    std::string text(8000, 'x');
    text += "\xe7\x95\x8c";
    std::vector<fm::ui::Step> path(600);
    for (size_t index = 0; index < path.size(); ++index) {
        path[index].child = index % 2;
        path[index].position = static_cast<int>(index);
        path[index].type = "Label";
        path[index].text = text;
        path[index].visible = false;
    }
    auto bytes = selectorBytes(path);
    auto view = [&] { return FmRequestBuffer{reinterpret_cast<uintptr_t>(bytes.data()), bytes.size()}; };
    const auto decoded = fm::ui::selectorPath(view(), 600);
    assert(decoded.size() == 600 && decoded.back().text == text && decoded.back().position == 599);
    assert(decoded[1].child && !decoded[0].child && decoded[0].visible == false && !decoded[0].enabled);
    rejects([&] { fm::ui::selectorPath(view(), 599); });
    bytes.push_back(0);
    rejects([&] { fm::ui::selectorPath(view(), 600); });
    bytes.pop_back();
    bytes.pop_back();
    rejects([&] { fm::ui::selectorPath(view(), 600); });
    bytes = selectorBytes(path);
    bytes[4] = 2;
    rejects([&] { fm::ui::selectorPath(view(), 600); });
    path[0].text = std::string_view("a\0b", 3);
    bytes = selectorBytes(path);
    rejects([&] { fm::ui::selectorPath(view(), 600); });
    rejects([&] { fm::requestView({1, 4}); });
    rejects([&] { fm::requestView({4096, uint64_t(INT32_MAX) + 1}); });
}
