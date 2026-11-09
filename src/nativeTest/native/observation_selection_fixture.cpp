#include "../../nativeMain/native/text_slice.h"
#include "../../nativeMain/native/binding_query.h"
#include "../../nativeMain/native/chat_observation.h"
#include <cassert>
#include <string>

struct Buffer { uint64_t address, size; };
struct Control { char id[64]; };
struct UiNode {
    Buffer text, tooltipTitle, tooltipText;
    uint32_t tooltipStatus, optionOffset, optionTotal, optionCount, optionFirst;
};
struct Option { Buffer text; };
struct Record {
    uint64_t tick;
    uint32_t stream, textIncluded;
    Buffer text, raw, playerName;
    FmTextRange textRange;
};

template <class Operation> void rejects(Operation operation) {
    bool rejected = false;
    try { operation(); } catch (const std::exception &) { rejected = true; }
    assert(rejected);
}

int main() {
    const std::string original = "A\xe7\x95\x8c\xf0\x9f\x9a\x80\n[font=default]original[/font]";
    std::vector<std::vector<char>> strings;
    auto buffer = [&] { return Buffer{reinterpret_cast<uintptr_t>(original.data()), original.size()}; };
    auto value = buffer();
    const auto range = sliceObservation(value, 4, 5, strings);
    assert(range.available && range.total == original.size() && range.offset == 4 && range.nextOffset == 9 && range.hasMore);
    assert(std::string_view(reinterpret_cast<const char *>(value.address), value.size) == "\xf0\x9f\x9a\x80\n");
    value = buffer();
    rejects([&] { sliceObservation(value, 2, 8, strings); });
    rejects([&] { sliceObservation(value, 1, 2, strings); });
    const auto past = sliceObservation(value, INT_MAX, 64, strings);
    assert(!value.size && past.nextOffset == original.size() && !past.hasMore);
    value = {};
    assert(sliceObservation(value, 0, 1, strings).available && !value.size);
    UiNode widget{buffer(), buffer(), buffer(), 3, 0, 0, 0, 0};
    std::vector<Option> options;
    const auto tooltip = sliceUiText(widget, options, {2, 4, 5}, true, false, strings);
    assert(tooltip.available && tooltip.total == original.size() && widget.tooltipTitle.size == 5 && !widget.tooltipText.size);
    widget = {buffer(), buffer(), buffer(), 3, 0, 0, 0, 0};
    assert(sliceUiText(widget, options, {3, 4, 5}, true, false, strings).available && !widget.tooltipTitle.size && widget.tooltipText.size == 5);
    widget.tooltipStatus = 2;
    assert(!sliceUiText(widget, options, {2, 0, 4}, true, false, strings).available);
    assert(!sliceUiText(widget, options, {4, 0, 4}, true, false, strings).available);

    std::vector<unsigned char> bytes;
    auto word = [&](uint32_t number) {
        for (int shift = 0; shift < 32; shift += 8) bytes.push_back(static_cast<unsigned char>(number >> shift));
    };
    auto text = [&](const std::string &input) {
        word(static_cast<uint32_t>(input.size()));
        bytes.insert(bytes.end(), input.begin(), input.end());
    };
    auto request = [&] { return FmRequestBuffer{reinterpret_cast<uintptr_t>(bytes.data()), bytes.size()}; };
    const std::vector<Control> all{{"control-z"}, {"control-a"}, {"\xe2\x84\xaa"}, {"k"}};
    auto controls = all;
    word(1); word(1); word(0); word(0); word(0);
    auto selected = fm::selectBindings(controls, request());
    assert(selected.matchedCount == 4 && selected.hasMore && controls.size() == 1 && std::string(controls[0].id) == "control-z");
    bytes.clear();
    word(0); word(INT_MAX); word(0); word(0); word(1); word(3); word('k'); word('K'); word(0x212a);
    controls = all;
    selected = fm::selectBindings(controls, request());
    assert(selected.matchedCount == 2 && !selected.hasMore && controls.size() == 2 && std::string(controls[0].id) == "k");
    bytes.clear();
    word(1); word(1); word(1); word(3); text("control-a"); text("control-z"); text("absent"); word(0);
    controls = all;
    selected = fm::selectBindings(controls, request());
    assert(selected.matchedCount == 2 && !selected.hasMore && controls.size() == 1 && std::string(controls[0].id) == "control-z");
    assert(selected.presence == std::vector<unsigned char>({1, 1, 0}));
    bytes.push_back(0);
    rejects([&] { fm::selectBindings(controls, request()); });
    bytes.clear();
    rejects([&] { fm::selectBindings(controls, request()); });

    const std::vector<Record> history{{9, 0, 0, buffer(), buffer(), buffer(), {}},
        {7, 0, 0, buffer(), buffer(), buffer(), {}}, {7, 0, 0, buffer(), buffer(), buffer(), {}},
        {10, 1, 0, buffer(), buffer(), buffer(), {}}, {7, 1, 0, buffer(), buffer(), buffer(), {}}};
    auto records = history;
    FmChatReadOptions read{};
    read.limit = 2;
    selectChatText(records, read, strings);
    assert(records[0].text.size && records[3].text.size && records[0].textIncluded && records[3].textIncluded);
    assert(!records[1].text.size && !records[2].text.size && !records[4].text.size && !records[1].textIncluded);
    read.hasOffset = 1;
    read.tick = 7;
    read.counts[0] = 1;
    read.textSlice = {2, 4, 5};
    records = history;
    selectChatText(records, read, strings);
    assert(records[1].textIncluded && records[4].textIncluded && !records[2].textIncluded && !records[0].textIncluded);
    assert(!records[1].text.size && records[1].raw.size == 5 && !records[1].playerName.size);
    assert(records[1].textRange.available && records[1].textRange.total == original.size());
    records = history;
    read.tick = 100;
    selectChatText(records, read, strings);
    for (const auto &record : records) assert(!record.textIncluded && !record.text.size && !record.raw.size && !record.playerName.size);
}
