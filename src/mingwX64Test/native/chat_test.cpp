#include "chat.h"
#include <cassert>
#include <cstddef>
#include <cstring>
#include <list>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
struct Item {
    uint64_t tick;
    uint16_t player;
    std::string text;
};

struct Node {
    Node *next, *previous;
    Item value;
};

struct List {
    Node *head;
    uint64_t count;
};

struct Console {
    List lists[2];
};

struct Player {
    uint16_t index;
    Console *console;
};

struct Action {
    uint64_t tick;
    uint16_t type, player;
    alignas(std::string) unsigned char buffer[sizeof(std::string)];
};

struct Context {
    Player *player;
};

int sent{}, destroyed{};
bool throwOnSend{};

void *localPlayer(void *game) {
    return game;
}

size_t stringSize(const void *value) {
    return static_cast<const std::string *>(value)->size();
}

const char *stringData(const void *value) {
    return static_cast<const std::string *>(value)->c_str();
}

void *raw(const void *value, void *output) {
    return new (output) std::string(*static_cast<const std::string *>(value));
}

void destroyString(void *value) {
    static_cast<std::string *>(value)->~basic_string();
}

void *construct(void *value, const char *text) {
    return new (value) std::string(text);
}

bool noData(uint16_t type) {
    assert(type == 97);
    return false;
}

bool dataType(uint16_t type, const void *info) {
    return type == 97 && info == reinterpret_cast<void *>(123);
}

void send(const void *context, void *value) {
    const auto *player = static_cast<const Context *>(context)->player;
    const auto &action = *static_cast<Action *>(value);
    assert(action.type == 97 && action.player == player->index && action.tick == 0);
    assert(*reinterpret_cast<const std::string *>(action.buffer) == "fixture");
    ++sent;
    if (throwOnSend)
        throw std::runtime_error("synthetic send failure");
}

void destroyAction(void *value) {
    ++destroyed;
    destroyString(static_cast<Action *>(value)->buffer);
}

Symbols symbols() {
    Symbols result{};
    result.address[LocalPlayer] = reinterpret_cast<uintptr_t>(localPlayer);
    result.address[StringSize] = reinterpret_cast<uintptr_t>(stringSize);
    result.address[StringData] = reinterpret_cast<uintptr_t>(stringData);
    result.address[LocalisedRaw] = reinterpret_cast<uintptr_t>(raw);
    result.address[StringDestroy] = reinterpret_cast<uintptr_t>(destroyString);
    result.address[ChatStringConstructor] = reinterpret_cast<uintptr_t>(construct);
    result.address[ActionNoData] = reinterpret_cast<uintptr_t>(noData);
    result.address[ActionDataType] = reinterpret_cast<uintptr_t>(dataType);
    result.address[StringTypeInfo] = 123;
    result.address[GuiSend] = reinterpret_cast<uintptr_t>(send);
    result.address[ActionDestroy] = reinterpret_cast<uintptr_t>(destroyAction);
    result.world.supported = 1;
    result.world.playerIndex = offsetof(Player, index);
    auto &layout = result.chat;
    layout.supported = layout.sendSupported = 1;
    layout.console = offsetof(Player, console);
    layout.lists[0] = offsetof(Console, lists);
    layout.lists[1] = offsetof(Console, lists) + sizeof(List);
    layout.head = offsetof(List, head);
    layout.count = offsetof(List, count);
    layout.next = offsetof(Node, next);
    layout.previous = offsetof(Node, previous);
    layout.value = offsetof(Node, value);
    layout.tick = offsetof(Item, tick);
    layout.playerIndex = offsetof(Item, player);
    layout.text = offsetof(Item, text);
    layout.cached = 0;
    layout.stringSize = sizeof(std::string);
    layout.contextSize = sizeof(Context);
    layout.contextPlayer = offsetof(Context, player);
    layout.actionSize = sizeof(Action);
    layout.actionType = offsetof(Action, type);
    layout.actionPlayer = offsetof(Action, player);
    layout.actionBuffer = offsetof(Action, buffer);
    layout.writeToConsole = 97;
    return result;
}
} // namespace

int main() {
    auto adapter = symbols();
    Node head{}, other{};
    head.next = head.previous = &head;
    other.next = other.previous = &other;
    Console console{{{&head, 0}, {&other, 0}}};
    Player player{42, &console};
    auto result = std::make_unique<FmChatSnapshot>();
    readChat(adapter, &player, *result);
    assert(result->count == 0);
    std::vector<std::unique_ptr<Node>> entries;
    for (int i = 0; i < 130; ++i) {
        auto node = std::make_unique<Node>();
        node->next = head.next;
        node->previous = &head;
        head.next->previous = node.get();
        head.next = node.get();
        node->value = {uint64_t(i), 42, "message"};
        entries.push_back(std::move(node));
        ++console.lists[0].count;
    }
    readChat(adapter, &player, *result);
    assert(result->count == 128 && result->totals[0] == 130);
    assert(result->records[0].tick == 129 && result->records[127].tick == 2);
    assert(result->records[0].playerIndex == 42 && std::string(result->records[0].raw) == "message");
    entries.back()->value.text = std::string(5000, 'a');
    readChat(adapter, &player, *result);
    assert(result->records[0].truncated == 3 && strlen(result->records[0].text) == 4095);
    FmChatRequest request{};
    strcpy_s(request.text, "fixture");
    request.size = 7;
    sendChat(adapter, &player, request);
    assert(sent == 1 && destroyed == 1);
    throwOnSend = true;
    try {
        sendChat(adapter, &player, request);
        assert(false);
    } catch (const std::runtime_error &) {
        assert(sent == 2 && destroyed == 2);
    }
    request.text[0] = '/';
    try {
        sendChat(adapter, &player, request);
        assert(false);
    } catch (const std::runtime_error &) {
        assert(sent == 2);
    }
}
