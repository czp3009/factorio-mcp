#include "resident.h"
#include "linux_ipc.h"
#include "chat_fixture_world.h"
#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <stdexcept>
#include <string>
#include <sys/mman.h>
#include <thread>
#include <unistd.h>

static void require(bool condition, const char *message) {
    if (!condition) {
        std::fprintf(stderr, "factorio-mcp resident fixture: %s\n", message);
        std::abort();
    }
}

static bool failRetirement = false;

namespace {
struct ChatAction {
    uint32_t type;
    std::string text;
};

struct ReadString { const char *data; uint64_t size; };
struct ReadItem { uint64_t tick; ReadString text; uint16_t index; };
struct ReadLinks { uintptr_t next, previous; };
struct ReadNode { ReadLinks links; ReadItem item; };
struct ReadList { ReadLinks head; uint64_t count; };
struct ReadConsole { void *player; ReadList lists[2]; };

void unexpectedRaw(void *, const void *) {
    require(false, "empty history invoked raw formatting");
}

void unexpectedStringDestroy(void *) {
    require(false, "empty history invoked string destruction");
}

unsigned stringsOwned = 0, actionsOwned = 0, submissions = 0;
bool failSubmission = false;
bool failActionDestruction = false, failStringDestruction = false;
unsigned actionDestructions = 0, stringDestructions = 0;
const void *submittedPlayer = nullptr;
std::string submittedText;
uint32_t submittedType = 0;

void constructChatString(void *storage) {
    new (storage) std::string;
    ++stringsOwned;
}

void *assignChatString(void *storage, const char *text, size_t size) {
    return &static_cast<std::string *>(storage)->assign(text, size);
}

void destroyChatString(void *storage) {
    ++stringDestructions;
    if (failStringDestruction)
        throw std::runtime_error("fixture string destruction failure");
    static_cast<std::string *>(storage)->~basic_string();
    --stringsOwned;
}

void constructChatAction(void *storage, uint32_t type, const void *text) {
    new (storage) ChatAction{type, *static_cast<const std::string *>(text)};
    ++actionsOwned;
}

void destroyChatAction(void *storage) {
    ++actionDestructions;
    if (failActionDestruction)
        throw std::runtime_error("fixture action destruction failure");
    static_cast<ChatAction *>(storage)->~ChatAction();
    --actionsOwned;
}

void submitChat(const void *player, void *storage) {
    auto &action = *static_cast<ChatAction *>(storage);
    ++submissions;
    submittedPlayer = player;
    submittedType = action.type;
    submittedText = std::move(action.text);
    if (failSubmission)
        throw std::runtime_error("fixture chat submission failure");
}
}

static void retire(unsigned *calls) {
    ++*calls;
    errno = ERANGE;
    if (failRetirement)
        throw std::runtime_error("fixture retirement failure");
}

class FixtureGui {
public:
    uintptr_t caller = 0;
    unsigned calls = 0;

    virtual void logic(bool flag) {
        require(flag, "argument was changed");
        caller = reinterpret_cast<uintptr_t>(__builtin_return_address(0));
        ++calls;
        errno = EDOM;
    }
};

__attribute__((noinline)) static void invoke(FixtureGui *gui) {
    asm volatile("" : "+r"(gui) : : "memory");
    gui->logic(true);
    // Keep a real, shared return site under release optimization, just like the verified frontend.
    asm volatile("" : : : "memory");
}

int main(int argc, char **argv) {
    alarm(30);
    errno = ENOENT;
    require(fm_linux_initialize() >= 0 && errno == ENOENT, "initialize failed or changed errno");
    auto *shared = reinterpret_cast<FmLinuxShared *>(fm_linux_resident.mapping);
    require(shared->initialized == 1 && shared->process == static_cast<uint32_t>(getpid()), "invalid IPC");
    require(fm_linux_cleanup() == 0, "initial cleanup failed");
    FixtureGui gui;
    FixtureGui *instance = &gui;
    invoke(&gui);
    const uintptr_t caller = gui.caller;
    uintptr_t *originalTable;
    std::memcpy(&originalTable, static_cast<void *>(&gui), sizeof(originalTable));
    const size_t pageSize = sysconf(_SC_PAGESIZE);
    void *page = mmap(nullptr, pageSize, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    require(page != MAP_FAILED, "cannot allocate synthetic table");
    auto *table = static_cast<uintptr_t *>(page);
    std::memcpy(table, originalTable - 2, 3 * sizeof(uintptr_t));
    table[8] = reinterpret_cast<uintptr_t>(&retire);
    uintptr_t *addressPoint = table + 2;
    std::memcpy(static_cast<void *>(&gui), &addressPoint, sizeof(addressPoint));
    require(mprotect(page, pageSize, PROT_READ) == 0, "cannot protect synthetic table");
    shared->config = {reinterpret_cast<uintptr_t>(addressPoint), table[2],
                      reinterpret_cast<uintptr_t>(&instance), caller, caller - 1, caller + 1, PROT_READ};
    shared->command = FM_LINUX_COMPLETE;
    require(fm_linux_attach() == -EBUSY && !shared->attached, "attach overwrote unreconciled result");
    shared->command = FM_LINUX_IDLE;
    require(fm_linux_attach() == 0 && shared->attached && shared->pointerOwned, "attach failed");
    require(fm_linux_attach() == -EBUSY, "duplicate attach lost ownership");

    auto request = [&](uint32_t operation, bool canceled) {
        require(fm_ipc_load(&shared->command) == FM_LINUX_IDLE, "overwriting unfinished request");
        shared->operation = operation;
        shared->cancel = canceled;
        fm_ipc_store(&shared->command, FM_LINUX_PENDING);
    };
    auto consume = [&](int result) {
        require(fm_ipc_load(&shared->command) == FM_LINUX_COMPLETE, "request was not completed");
        require(shared->result == result, "unexpected request result");
        require(shared->resultFrame > 0 && shared->resultFrame <= fm_ipc_load64(&shared->frame), "invalid frame");
        fm_ipc_store(&shared->command, FM_LINUX_IDLE);
    };
    request(FM_LINUX_FRAME, false);
    require(fm_linux_cleanup() == -EBUSY, "cleanup discarded pending request");
    std::thread worker([&] { invoke(&gui); });
    worker.join();
    require(shared->command == FM_LINUX_PENDING && shared->frame == 0, "worker executed request");
    instance = nullptr;
    invoke(&gui);
    require(shared->command == FM_LINUX_PENDING && shared->frame == 0, "stale receiver executed request");
    instance = &gui;
    invoke(&gui);
    require(errno == EDOM, "callback changed original errno");
    consume(0);
    request(FM_LINUX_FRAME, true);
    invoke(&gui);
    consume(-ECANCELED);
    request(999, false);
    invoke(&gui);
    consume(-ENOTSUP);
    request(FM_LINUX_INPUT_CONTEXT, false);
    invoke(&gui);
    consume(-EINVAL);
    require(table[8] == reinterpret_cast<uintptr_t>(&retire), "invalid retirement configuration patched a table");
    shared->retirementConfig = {reinterpret_cast<uintptr_t>(table + 8), table[8], PROT_READ};
    request(FM_LINUX_INPUT_CONTEXT, false);
    invoke(&gui);
    // Context metadata is deliberately absent; the independently verified retirement hook remains owned.
    consume(-EINVAL);
    require(table[8] != reinterpret_cast<uintptr_t>(&retire), "retirement hook was not installed");
    const auto delayedRetirement = reinterpret_cast<void (*)(unsigned *)>(table[8]);
    unsigned retirements = 0;
    delayedRetirement(&retirements);
    require(retirements == 1 && errno == ERANGE, "retirement dispatch or errno was changed");
    failRetirement = true;
    try {
        delayedRetirement(&retirements);
        require(false, "retirement exception was swallowed");
    } catch (const std::runtime_error &) {
        require(retirements == 2, "throwing retirement was replayed");
    }
    failRetirement = false;
    ++shared->retirementConfig.original;
    request(FM_LINUX_INPUT_CONTEXT, false);
    invoke(&gui);
    consume(-ESTALE);
    --shared->retirementConfig.original;
    shared->controlsSnapshot.count = 17;
    shared->controlsSnapshot.registryCount = 19;
    request(FM_LINUX_CONTROLS, false);
    invoke(&gui);
    consume(-EINVAL);
    require(!shared->controlsSnapshot.count && !shared->controlsSnapshot.registryCount,
        "failed control read retained stale rows");
    request(FM_LINUX_CONTROLS, true);
    invoke(&gui);
    consume(-ECANCELED);
    shared->queryResult.size = 17;
    shared->queryResult.errorSize = 19;
    request(FM_LINUX_QUERY, false);
    invoke(&gui);
    consume(-EINVAL);
    require(!shared->queryResult.size && !shared->queryResult.errorSize, "failed query retained stale result lengths");
    request(FM_LINUX_CLICK, false);
    invoke(&gui);
    consume(-EINVAL);
    require(!shared->actionOwned, "rejected click retained action ownership");
    request(FM_LINUX_CLICK, true);
    invoke(&gui);
    consume(-ECANCELED);
    require(!shared->actionOwned, "canceled click acquired action ownership");
    request(FM_LINUX_TEXT, false);
    invoke(&gui);
    consume(-EINVAL);
    require(!shared->actionOwned, "rejected text retained action ownership");
    request(FM_LINUX_TEXT, true);
    invoke(&gui);
    consume(-ECANCELED);
    require(!shared->actionOwned, "canceled text acquired action ownership");
    for (const bool canceled : {false, true}) {
        shared->chatProgress = {1, 1};
        request(FM_LINUX_CHAT, canceled);
        invoke(&gui);
        consume(canceled ? -ECANCELED : -EINVAL);
        require(!shared->actionOwned && !shared->chatProgress.entered && !shared->chatProgress.returned,
            "rejected chat retained ownership or stale submission progress");
    }
    chat_fixture::World chatWorld;
    ReadConsole console{};
    console.player = &chatWorld.player;
    chatWorld.player.console = &console;
    auto &read = shared->chatReadConfig;
    read.world = chatWorld.config.world;
    read.player = chatWorld.config.player;
    read.console = chat_fixture::offset(&chatWorld.player, &chatWorld.player.console);
    read.raw = reinterpret_cast<uintptr_t>(&unexpectedRaw);
    read.destroyString = reinterpret_cast<uintptr_t>(&unexpectedStringDestroy);
    auto &layout = read.layout;
    layout.consoleSize = sizeof(console);
    layout.consolePlayer = offsetof(ReadConsole, player);
    for (unsigned i = 0; i < 2; ++i) {
        auto &head = console.lists[i].head;
        head.next = head.previous = reinterpret_cast<uintptr_t>(&head);
        layout.sentinel[i] = offsetof(ReadConsole, lists) + i * sizeof(ReadList) + offsetof(ReadList, head);
        layout.count[i] = offsetof(ReadConsole, lists) + i * sizeof(ReadList) + offsetof(ReadList, count);
    }
    layout.nodeSize = sizeof(ReadNode);
    layout.next = offsetof(ReadLinks, next);
    layout.previous = offsetof(ReadLinks, previous);
    layout.value = offsetof(ReadNode, item);
    layout.tick = offsetof(ReadItem, tick);
    layout.playerIndex = offsetof(ReadItem, index);
    layout.indexWidth = sizeof(ReadItem::index);
    layout.text = offsetof(ReadItem, text);
    layout.textSize = sizeof(ReadString);
    layout.stringSize = sizeof(ReadString);
    layout.stringData = offsetof(ReadString, data);
    layout.stringLength = offsetof(ReadString, size);
    request(FM_LINUX_CHAT_READ, false);
    invoke(&gui);
    consume(0);
    require(shared->chatSnapshot.consoleIdentity == reinterpret_cast<uintptr_t>(&console) &&
        !shared->chatSnapshot.count && !shared->chatSnapshot.totals[0] && !shared->chatSnapshot.totals[1] &&
        !shared->actionOwned, "empty chat history lost identity, bounds or cleanup");
    console.player = &chatWorld.replacement;
    request(FM_LINUX_CHAT_READ, false);
    invoke(&gui);
    consume(-ESTALE);
    require(!shared->chatSnapshot.count && !shared->actionOwned, "stale console retained rows or ownership");
    console.player = &chatWorld.player;
    request(FM_LINUX_CHAT_READ, true);
    invoke(&gui);
    consume(-ECANCELED);
    const auto nodeSize = layout.nodeSize;
    layout.nodeSize = 0;
    request(FM_LINUX_CHAT_READ, false);
    invoke(&gui);
    consume(-EINVAL);
    require(!shared->chatSnapshot.count && !shared->actionOwned, "invalid read retained rows or ownership");
    layout.nodeSize = nodeSize;
    shared->chatConfig = {chatWorld.config,
        reinterpret_cast<uintptr_t>(&constructChatString), reinterpret_cast<uintptr_t>(&assignChatString),
        reinterpret_cast<uintptr_t>(&destroyChatString), reinterpret_cast<uintptr_t>(&constructChatAction),
        reinterpret_cast<uintptr_t>(&destroyChatAction), reinterpret_cast<uintptr_t>(&submitChat),
        sizeof(std::string), sizeof(ChatAction), 37};
    const std::string message = "fixture chat: 中文";
    shared->chatRequest.size = message.size();
    std::memcpy(shared->chatRequest.text, message.data(), message.size());
    for (const bool fail : {false, true}) {
        failSubmission = fail;
        const unsigned before = submissions;
        request(FM_LINUX_CHAT, false);
        invoke(&gui);
        consume(fail ? -EFAULT : 0);
        require(submissions == before + 1 && submittedPlayer == &chatWorld.player &&
            submittedType == 37 && submittedText == message, "chat wire changed submission arguments");
        require(shared->chatProgress.entered && shared->chatProgress.returned == !fail &&
            !shared->actionOwned && !stringsOwned && !actionsOwned, "chat completion lost cleanup or progress");
        invoke(&gui);
        require(submissions == before + 1, "completed chat was replayed");
    }
    chatWorld.handler.field = chatWorld.config.excluded;
    const unsigned beforeRejectedChat = submissions;
    request(FM_LINUX_CHAT, false);
    invoke(&gui);
    consume(-EACCES);
    require(submissions == beforeRejectedChat && !shared->chatProgress.entered &&
        !shared->chatProgress.returned && !shared->actionOwned && !stringsOwned && !actionsOwned,
        "excluded chat entered native submission or retained resources");
    if (argc == 2) {
        failActionDestruction = std::strcmp(argv[1], "action-destruction") == 0;
        failStringDestruction = std::strcmp(argv[1], "string-destruction") == 0;
        require(failActionDestruction || failStringDestruction, "unknown failure fixture mode");
        failSubmission = false;
        chatWorld.handler.field = 0;
        request(FM_LINUX_CHAT, false);
        invoke(&gui);
        consume(-EFAULT);
        require(shared->actionOwned && shared->chatProgress.entered && shared->chatProgress.returned,
            "uncertain destruction discarded ownership or submission progress");
        const unsigned submitted = submissions, actionDestroyed = actionDestructions,
            stringDestroyed = stringDestructions;
        require(fm_linux_cleanup() == -EBUSY, "direct cleanup discarded uncertain chat");
        request(FM_LINUX_FRAME, false);
        invoke(&gui);
        consume(-EBUSY);
        for (const auto operation : {FM_LINUX_CLEANUP, FM_LINUX_DETACH, FM_LINUX_CLEANUP}) {
            request(operation, true);
            invoke(&gui);
            consume(-EFAULT);
            require(shared->actionOwned && shared->attached && shared->pointerOwned &&
                submissions == submitted && actionDestructions == actionDestroyed &&
                stringDestructions == stringDestroyed, "cleanup replayed uncertain work or lost ownership");
        }
        // This isolated fixture process intentionally ends with an uncertain native resource.
        return 0;
    }
    request(FM_LINUX_CLEANUP, true);
    invoke(&gui);
    consume(0);
    require(mprotect(page, pageSize, PROT_READ | PROT_WRITE) == 0, "cannot simulate changed retirement entry");
    table[8] = reinterpret_cast<uintptr_t>(&retire);
    require(mprotect(page, pageSize, PROT_READ) == 0, "cannot restore synthetic protection");
    request(FM_LINUX_DETACH, true);
    invoke(&gui);
    consume(-ESTALE);
    require(shared->attached && shared->pointerOwned, "failed retirement unhook lost attachment ownership");
    require(mprotect(page, pageSize, PROT_READ | PROT_WRITE) == 0, "cannot restore owned retirement entry");
    table[8] = reinterpret_cast<uintptr_t>(delayedRetirement);
    require(mprotect(page, pageSize, PROT_READ) == 0, "cannot restore synthetic protection");
    request(FM_LINUX_DETACH, true);
    invoke(&gui);
    consume(0);
    require(!shared->attached && !shared->pointerOwned && !shared->protectionOwned,
            "detach retained ownership");
    require(table[2] == originalTable[0], "detach did not restore original pointer");
    require(table[8] == reinterpret_cast<uintptr_t>(&retire), "detach did not restore retirement pointer");
    delayedRetirement(&retirements);
    require(retirements == 3, "late retirement wrapper lost its original target");
    const auto frame = shared->frame;
    invoke(&gui);
    require(shared->frame == frame, "callback survived detach");
    require(fm_linux_cleanup() == 0, "repeat cleanup failed");
    require(fm_linux_attach() == 0, "reattach failed");
    request(FM_LINUX_FRAME, false);
    require(fm_linux_initialize() >= 0 && shared->command == FM_LINUX_PENDING,
            "initialization reset an admitted command");
    invoke(&gui);
    consume(0);
    request(FM_LINUX_INPUT_CONTEXT, false);
    invoke(&gui);
    consume(-EINVAL);
    require(table[8] == reinterpret_cast<uintptr_t>(delayedRetirement), "retirement hook could not be reinstalled");
    require(fm_linux_cleanup() == 0 && !shared->attached, "direct cleanup failed");
    ++shared->config.caller;
    require(fm_linux_attach() < 0 && !shared->attached, "resident rebound to different configuration");
    std::memcpy(static_cast<void *>(&gui), &originalTable, sizeof(originalTable));
    require(munmap(page, pageSize) == 0, "cannot release synthetic table");
    std::puts("factorio-mcp resident fixture: passed");
}
