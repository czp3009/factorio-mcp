#include "chat.h"
#include "protocol.h"
#include <algorithm>
#include <cstring>

namespace {
template <class T> T read(const void *object, unsigned offset) {
    T result;
    memcpy(&result, static_cast<const unsigned char *>(object) + offset, sizeof(result));
    return result;
}

template <class T> void write(void *object, unsigned offset, T value) {
    memcpy(static_cast<unsigned char *>(object) + offset, &value, sizeof(value));
}

template <class T> T function(const Symbols &symbols, FmSymbol id) {
    return reinterpret_cast<T>(symbols.address[id]);
}

bool copyString(const Symbols &symbols, const void *source, char *output, size_t capacity) {
    const auto size = function<size_t (*)(const void *)>(symbols, StringSize)(source);
    require(size <= 1024 * 1024, "Chat string exceeds read bound");
    const auto *text = function<const char *(*)(const void *)>(symbols, StringData)(source);
    require(text, "Chat string data is absent");
    size_t count = std::min(size, capacity - 1);
    if (count < size)
        while (count && (static_cast<unsigned char>(text[count]) & 0xc0) == 0x80)
            --count;
    memcpy(output, text, count);
    output[count] = 0;
    return count < size;
}

struct DestroyString {
    const Symbols &symbols;
    void *value;

    ~DestroyString() {
        function<void (*)(void *)>(symbols, StringDestroy)(value);
    }
};
} // namespace

void readChat(const Symbols &symbols, void *game, FmChatSnapshot &result) {
    const auto &layout = symbols.chat;
    require(layout.supported && game, "Chat observation adapter is unavailable");
    void *player = function<void *(*)(void *)>(symbols, LocalPlayer)(game);
    require(player, "Chat requires a local player");
    auto *console = read<unsigned char *>(player, layout.console);
    require(console, "Local output console is unavailable");
    result.consoleIdentity = reinterpret_cast<uintptr_t>(console);
    result.count = 0;
    for (unsigned stream = 0; stream < 2; ++stream) {
        auto *list = console + layout.lists[stream];
        auto *head = read<unsigned char *>(list, layout.head);
        const auto total = read<uint64_t>(list, layout.count);
        require(head && total <= 65536, "Chat history list exceeds bound");
        result.totals[stream] = total;
        // OutputConsole::add inserts at begin(); the front contains the most recent entries.
        auto *node = read<unsigned char *>(head, layout.next);
        for (uint64_t index = 0; index < std::min(total, uint64_t(FM_MAX_CHAT / 2)); ++index) {
            require(node && node != head, "Chat history list ended unexpectedly");
            auto *item = node + layout.value;
            auto &record = result.records[result.count++];
            record.identity = reinterpret_cast<uintptr_t>(node);
            record.stream = stream;
            record.tick = read<uint64_t>(item, layout.tick);
            record.playerIndex = read<uint16_t>(item, layout.playerIndex);
            auto *text = item + layout.text;
            record.truncated = copyString(symbols, text + layout.cached, record.text, sizeof(record.text));
            alignas(16) unsigned char raw[256];
            require(layout.stringSize <= sizeof(raw), "Unsupported chat string storage");
            // str_raw formats the localization expression without requesting/refreshing translation.
            function<void *(*)(const void *, void *)>(symbols, LocalisedRaw)(text, raw);
            DestroyString cleanup{symbols, raw};
            record.truncated |= copyString(symbols, raw, record.raw, sizeof(record.raw)) ? 2u : 0u;
            auto *next = read<unsigned char *>(node, layout.next);
            require(next && read<void *>(next, layout.previous) == node, "Invalid chat list linkage");
            node = next;
        }
        if (total <= FM_MAX_CHAT / 2)
            require(node == head, "Chat history list length mismatch");
    }
}

void sendChat(const Symbols &symbols, void *game, const FmChatRequest &request) {
    const auto &layout = symbols.chat;
    require(layout.sendSupported && symbols.world.supported && game, "Chat submission adapter is unavailable");
    require(request.size > 0 && request.size < sizeof(request.text) && request.text[request.size] == 0 &&
                !memchr(request.text, 0, request.size) && !memchr(request.text, '\n', request.size) &&
                !memchr(request.text, '\r', request.size) && request.text[0] != '/',
            "Chat requires one plain message, not a console command");
    void *player = function<void *(*)(void *)>(symbols, LocalPlayer)(game);
    require(player, "Chat requires a local player");
    require(!function<bool (*)(uint16_t)>(symbols, ActionNoData)(uint16_t(layout.writeToConsole)) &&
                function<bool (*)(uint16_t, const void *)>(symbols, ActionDataType)(
                    uint16_t(layout.writeToConsole), reinterpret_cast<void *>(symbols.address[StringTypeInfo])),
            "Chat action data type changed");
    alignas(16) unsigned char action[256]{}, context[256]{};
    require(layout.actionSize <= sizeof(action) && layout.contextSize <= sizeof(context), "Chat storage exceeds bound");
    write(context, layout.contextPlayer, player);
    write(action, layout.actionType, uint16_t(layout.writeToConsole));
    write(action, layout.actionPlayer, read<uint16_t>(player, symbols.world.playerIndex));
    // The game's constructor owns allocation; send may move the value, and its destructor handles either state.
    function<void *(*)(void *, const char *)>(symbols, ChatStringConstructor)(action + layout.actionBuffer,
                                                                              request.text);

    struct DestroyAction {
        const Symbols &symbols;
        void *value;

        ~DestroyAction() {
            function<void (*)(void *)>(symbols, ActionDestroy)(value);
        }
    } cleanup{symbols, action};

    function<void (*)(const void *, void *)>(symbols, GuiSend)(context, action);
}
