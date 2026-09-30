#include "resident.h"
#include "linux_ipc.h"
#include "gui_hook.h"
#include "poll_hook.h"
#include "event_pump.h"
#include "view_lifetime.h"
#include "chat_submit.h"
#include "memory_read.h"
#include "pointer_hook.h"
#include "frame_hook.h"
#include <atomic>
#include <cerrno>
#include <fcntl.h>
#include <linux/memfd.h>
#include <optional>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <unistd.h>

extern "C" {
__attribute__((visibility("default"))) FmLinuxResidentInfo fm_linux_resident{0, -1, 0};
uint64_t fm_poll_original = 0;
uint64_t fm_view_retire_original = 0;
uint64_t fm_frame_original = 0;
}

static std::atomic_flag initializing = ATOMIC_FLAG_INIT;
static PointerHook hook(mprotect);
static PointerHook pollHook(mprotect);
static PointerHook retirementHook(mprotect);
static FrameHookOwner frameHook(mprotect, fm_frame_original);
static FmLinuxRetirementConfig retirementBound{};
static ViewLifetime viewLifetime;
static FmLinuxHookConfig bound{};
static FmLinuxPollHookConfig pollBound{};
static std::optional<UiClickCommand> click;
static std::optional<UiTextCommand> text;
static std::optional<UiKeyCommand> key;
static std::optional<ChatSubmitCommand> chat;
static std::optional<ChatReadCommand> chatRead;
static EventPump eventPump;

static void publishAction(FmLinuxShared *shared) {
    if (click && click->finished())
        click.reset();
    if (text && text->finished())
        text.reset();
    if (key && key->finished())
        key.reset();
    if (chat && chat->finished())
        chat.reset();
    if (chatRead && chatRead->finished())
        chatRead.reset();
    fm_ipc_store(&shared->actionOwned, click.has_value() || text.has_value() || key.has_value() || chat.has_value() || chatRead.has_value());
}

static int cleanupAction(FmLinuxShared *shared) {
    int result = click ? click->finish() : 0;
    const int textResult = text ? text->finish() : 0;
    if (!result)
        result = textResult;
    const int keyResult = key ? key->finish() : 0;
    if (!result)
        result = keyResult;
    const int chatResult = chat ? chat->finish() : 0;
    if (!result)
        result = chatResult;
    const int readResult = chatRead ? chatRead->finish() : 0;
    if (!result)
        result = readResult;
    publishAction(shared);
    return result;
}

class PreserveErrno {
public:
    PreserveErrno() : value(errno) {}
    ~PreserveErrno() { errno = value; }
private:
    int value;
};

static FmLinuxShared *storage() {
    if (!fm_ipc_load(&fm_linux_resident.initialized))
        return nullptr;
    return reinterpret_cast<FmLinuxShared *>(fm_linux_resident.mapping);
}

static void publishOwnership(FmLinuxShared *shared) {
    fm_ipc_store(&shared->pointerOwned, hook.ownsPointer());
    fm_ipc_store(&shared->protectionOwned, hook.ownsProtection() || pollHook.ownsProtection() || retirementHook.ownsProtection() || frameHook.ownsProtection());
    fm_ipc_store(&shared->attached, hook.hasOwnership() || pollHook.hasOwnership() || retirementHook.hasOwnership() || frameHook.hasOwnership());
}

static int removeHooks() {
    if (viewLifetime.owned())
        return EBUSY;
    if (const int error = frameHook.remove())
        return error;
    if (const int error = retirementHook.remove())
        return error;
    const int error = pollHook.remove();
    return error ? error : hook.remove();
}

static int installRetirement(FmLinuxShared *shared) {
    const auto config = shared->retirementConfig;
    if (!config.entry || config.entry % alignof(uintptr_t) || !config.original ||
        (config.protection != PROT_READ && config.protection != (PROT_READ | PROT_WRITE)))
        return EINVAL;
    if (fm_view_retire_original && (config.entry != retirementBound.entry ||
        config.original != retirementBound.original || config.protection != retirementBound.protection))
        return ESTALE;
    if (retirementHook.hasOwnership())
        return retirementHook.ownsPointer() && !retirementHook.ownsProtection() ? 0 : EBUSY;
    retirementBound = config;
    fm_view_retire_original = config.original;
    const int result = retirementHook.install(config.entry, config.original,
        reinterpret_cast<uintptr_t>(&fm_view_retire_hook), sysconf(_SC_PAGESIZE), config.protection);
    publishOwnership(shared);
    return result;
}

extern "C" void fm_before_view_retire(void *receiver) noexcept {
    PreserveErrno preserve;
    // No borrowed object is read, and late wrapper callers remain valid after hook removal.
    viewLifetime.retired(reinterpret_cast<uintptr_t>(receiver));
}

static int installPoll(FmLinuxShared *shared) {
    const auto config = shared->pollConfig;
    if (!validPollSite(config) || config.eventExtent != shared->keyConfig.event.extent)
        return EINVAL;
    if (fm_poll_original && !samePollSite(pollBound, config))
        return ESTALE;
    if (pollHook.hasOwnership())
        return pollHook.ownsPointer() && !pollHook.ownsProtection() ? 0 : EBUSY;
    pollBound = config;
    fm_poll_original = config.original;
    const int result = pollHook.install(config.entry, config.original, reinterpret_cast<uintptr_t>(&fm_poll_hook),
                                       sysconf(_SC_PAGESIZE), config.protection);
    publishOwnership(shared);
    return result;
}

static void complete(FmLinuxShared *shared, int result, uint64_t frame) {
    shared->result = -result;
    shared->resultFrame = frame;
    fm_ipc_store(&shared->command, FM_LINUX_COMPLETE);
}

static bool sameConfig(const FmLinuxHookConfig &a, const FmLinuxHookConfig &b) {
    return a.entry == b.entry && a.original == b.original && a.guiInstance == b.guiInstance &&
           a.caller == b.caller && a.callerStart == b.callerStart && a.callerEnd == b.callerEnd &&
           a.protection == b.protection;
}

extern "C" __attribute__((visibility("default"))) int64_t fm_linux_attach() {
    PreserveErrno preserve;
    auto *shared = storage();
    if (!shared || syscall(SYS_gettid) != getpid())
        return -EPERM;
    if (fm_ipc_load(&shared->command) != FM_LINUX_IDLE || hook.hasOwnership() || pollHook.hasOwnership() ||
        retirementHook.hasOwnership() || frameHook.hasOwnership() || click || text || key || chat || chatRead)
        return -EBUSY;
    const auto config = shared->config;
    if (!config.entry || !config.original || !config.guiInstance || !config.callerStart ||
        config.guiInstance % alignof(uintptr_t) != 0 || config.caller < config.callerStart ||
        config.caller >= config.callerEnd)
        return -EINVAL;
    // Keep the original target and hook ABI state immutable for any in-flight wrapper after removal.
    if (fm_gui_original) {
        if (!sameConfig(bound, config))
            return -ESTALE;
    } else {
        if (!initializeHookState())
            return -ENOTSUP;
        bound = config;
        fm_gui_original = config.original;
    }
    const int result = hook.install(config.entry, config.original, reinterpret_cast<uintptr_t>(&fm_gui_hook),
                                    sysconf(_SC_PAGESIZE), config.protection);
    publishOwnership(shared);
    return -result;
}

extern "C" __attribute__((visibility("default"))) int64_t fm_linux_cleanup() {
    PreserveErrno preserve;
    auto *shared = storage();
    if (!shared || syscall(SYS_gettid) != getpid())
        return -EPERM;
    const auto command = fm_ipc_load(&shared->command);
    if (command == FM_LINUX_PENDING || command == FM_LINUX_RUNNING || click || text || key || chat || chatRead)
        return -EBUSY;
    const int result = removeHooks();
    publishOwnership(shared);
    return -result;
}

extern "C" void fm_after_gui_logic(void *receiver, uintptr_t caller) noexcept {
    PreserveErrno preserve;
    // Only the verified main-thread caller may access configuration or GUI state.
    if (syscall(SYS_gettid) != getpid())
        return;
    auto *shared = storage();
    if (!shared || !fm_ipc_load(&shared->attached) || caller != bound.caller ||
        caller < bound.callerStart || caller >= bound.callerEnd)
        return;
    auto *instance = reinterpret_cast<void **>(bound.guiInstance);
    if (!receiver || *instance != receiver)
        return;
    const uint64_t frame = __atomic_add_fetch(&shared->frame, uint64_t{1}, __ATOMIC_RELEASE);
    // A suspended render loop must not prevent a pending capture from acknowledging cancellation.
    if (fm_ipc_load(&shared->command) == FM_LINUX_RUNNING && shared->operation == FM_LINUX_SCREENSHOT) {
        if (fm_ipc_load(&shared->cancel)) {
            shared->frameSize = {};
            shared->frameBytes = 0;
            complete(shared, ECANCELED, frame);
        }
        return;
    }
    const bool resumedCleanup = fm_ipc_load(&shared->command) == FM_LINUX_RUNNING && key &&
        (shared->operation == FM_LINUX_CLEANUP || shared->operation == FM_LINUX_DETACH);
    if (resumedCleanup) {
        if (key->active())
            return;
    } else if (!fm_ipc_exchange_if(&shared->command, FM_LINUX_PENDING, FM_LINUX_RUNNING)) {
        return;
    }
    if (shared->operation == FM_LINUX_CHAT)
        shared->chatProgress = {};
    int result;
    if (shared->operation == FM_LINUX_DETACH) {
        // Cleanup must finish even when the requesting session was canceled.
        result = cleanupAction(shared);
        if (key && key->active())
            return;
        if (!result && !click && !text && !key && !chat && !chatRead)
            result = removeHooks();
        publishOwnership(shared);
    } else if (shared->operation == FM_LINUX_CLEANUP) {
        result = cleanupAction(shared);
        if (key && key->active())
            return;
    } else if (click || text || key || chat || chatRead) {
        // Only explicit cleanup/detach can resume owned releases. Never overwrite or replay an uncertain action.
        result = EBUSY;
    } else if (fm_ipc_load(&shared->cancel)) {
        result = ECANCELED;
    } else if (shared->operation == FM_LINUX_FRAME) {
        result = 0;
    } else if (shared->operation == FM_LINUX_SCREENSHOT) {
        shared->frameSize = {};
        shared->frameBytes = 0;
        shared->frameGlError = 0;
        result = validFrameContext(shared->frameContext) &&
            shared->frameSite.deviceGlobal == shared->frameContext.device ? 0 : EINVAL;
        FrameReadbackApi api{};
        if (!result)
            result = readFrameApi(shared->frameApi, api);
        if (!result)
            result = frameHook.install(shared->frameSite, reinterpret_cast<uintptr_t>(&fm_frame_hook), sysconf(_SC_PAGESIZE));
        publishOwnership(shared);
        if (!result)
            return;
    } else if (shared->operation == FM_LINUX_UI) {
        result = snapshotUi(receiver, shared->ui, shared->nodeLimit, &shared->cancel, shared->snapshot, &shared->selector);
    } else if (shared->operation == FM_LINUX_CONTROLS) {
        result = snapshotControls(shared->controlsLayout, &shared->cancel, shared->controlsSnapshot);
    } else if (shared->operation == FM_LINUX_INPUT_CONTEXT) {
        shared->inputContextSnapshot = {};
        InputContext context;
        result = installRetirement(shared);
        if (!result)
            result = readInputContext(shared->inputContextConfig, &shared->cancel, context);
        if (!result) {
            shared->inputContextSnapshot.tick = context.tick;
            shared->inputContextSnapshot.paused = context.paused;
            shared->inputContextSnapshot.stopped = context.stopped;
        }
    } else if (shared->operation == FM_LINUX_CHAT) {
        const auto &wire = shared->chatConfig;
        ChatSubmitConfig config{wire.stringSize, wire.actionSize, wire.actionType,
            reinterpret_cast<void (*)(void *)>(wire.constructString),
            reinterpret_cast<void *(*)(void *, const char *, size_t)>(wire.assignString),
            reinterpret_cast<void (*)(void *)>(wire.destroyString),
            reinterpret_cast<void (*)(void *, uint32_t, const void *)>(wire.constructAction),
            reinterpret_cast<void (*)(void *)>(wire.destroyAction),
            reinterpret_cast<void (*)(const void *, void *)>(wire.submit)};
        ChatAdmission admission(wire.admission, &shared->cancel);
        chat.emplace();
        result = chat->start(config, reinterpret_cast<const char *>(shared->chatRequest.text),
            shared->chatRequest.size, ChatAdmission::resolve, &admission);
        shared->chatProgress.entered = chat->submissionEntered();
        shared->chatProgress.returned = chat->submitted();
        publishAction(shared);
    } else if (shared->operation == FM_LINUX_CHAT_READ) {
        const auto &wire = shared->chatReadConfig;
        shared->chatSnapshot.count = 0;
        WorldObjects world;
        PlayerObjects player;
        uintptr_t console = 0;
        result = wire.world.gameSize == wire.player.gameSize &&
            fm::member(wire.console, sizeof(uintptr_t), wire.player.playerSize) ? 0 : EINVAL;
        if (!result)
            result = readWorldObjects(wire.world, &shared->cancel, world);
        if (!result)
            result = readLocalPlayer(world.game, wire.player, &shared->cancel, player);
        if (!result && !fm::read(player.player + wire.console, console))
            result = EFAULT;
        if (!result) {
            ChatReadConfig config{wire.layout, reinterpret_cast<void (*)(void *, const void *)>(wire.raw),
                reinterpret_cast<void (*)(void *)>(wire.destroyString)};
            chatRead.emplace();
            result = chatRead->start(config, console, player.player, &shared->cancel, shared->chatSnapshot);
            publishAction(shared);
        }
    } else if (shared->operation == FM_LINUX_QUERY) {
        result = queryWorld(shared->worldConfig, shared->query, &shared->cancel, shared->queryResult);
    } else if (shared->operation == FM_LINUX_CLICK) {
        click.emplace();
        result = click->start(bound.guiInstance, receiver, shared->ui, shared->clickConfig, shared->selector,
            shared->clickRequest, &shared->cancel, shared->snapshot);
        publishAction(shared);
    } else if (shared->operation == FM_LINUX_TEXT) {
        text.emplace();
        result = text->start(bound.guiInstance, receiver, shared->ui, shared->textConfig, shared->selector,
            shared->textRequest, &shared->cancel, shared->snapshot);
        publishAction(shared);
    } else if (shared->operation == FM_LINUX_KEY) {
        result = installPoll(shared);
        if (!result) {
            key.emplace();
            result = key->start(bound.guiInstance, receiver, shared->ui, shared->keyConfig, shared->selector,
                shared->keyRequest, &shared->cancel, shared->snapshot);
            publishAction(shared);
            if (!result && key && key->active())
                return;
        }
    } else {
        result = ENOTSUP;
    }
    complete(shared, result, frame);
}

extern "C" void fm_before_frame(void *device, void *window, uintptr_t caller) noexcept {
    PreserveErrno preserve;
    if (syscall(SYS_gettid) != getpid())
        return;
    auto *shared = storage();
    if (!shared || !frameHook.ownsPointer() || !fm_ipc_load(&shared->attached) ||
        fm_ipc_load(&shared->command) != FM_LINUX_RUNNING || shared->operation != FM_LINUX_SCREENSHOT ||
        caller != shared->frameContext.caller)
        return;
    FmLinuxFrameSize size{};
    FrameReadbackApi api{};
    size_t written = 0;
    int result = readFrameSize(shared->frameContext, reinterpret_cast<uintptr_t>(device),
        reinterpret_cast<uintptr_t>(window), caller, &shared->cancel, size);
    if (!result)
        result = readFrameApi(shared->frameApi, api);
    if (!result && fm_ipc_load(&shared->cancel))
        result = ECANCELED;
    if (!result) {
        try {
            result = readFrame(api, size.width, size.height, shared->framePixels, sizeof(shared->framePixels),
                written, shared->frameGlError);
        } catch (...) {
            result = EIO;
        }
    }
    if (fm_ipc_load(&shared->cancel))
        result = ECANCELED;
    shared->frameSize = result ? FmLinuxFrameSize{} : size;
    shared->frameBytes = result ? 0 : static_cast<uint32_t>(written);
    complete(shared, result, fm_ipc_load64(&shared->frame));
}

extern "C" int fm_before_poll(void *receiver, void *event, uintptr_t caller, uintptr_t callerFrame) noexcept {
    PreserveErrno preserve;
    return eventPump.intercept(receiver, event, caller, callerFrame);
}

extern "C" bool fm_after_empty_poll(void *receiver, void *event, uintptr_t caller, uintptr_t callerFrame) noexcept {
    PreserveErrno preserve;
    if (syscall(SYS_gettid) != getpid())
        return false;
    auto *shared = storage();
    if (!shared || !pollHook.ownsPointer() || !key || !key->active() ||
        fm_ipc_load(&shared->command) != FM_LINUX_RUNNING ||
        (shared->operation != FM_LINUX_KEY && shared->operation != FM_LINUX_CLEANUP && shared->operation != FM_LINUX_DETACH) ||
        !matchesPollSite(pollBound, receiver, event, caller, callerFrame))
        return false;
    bool produced = false;
    const int error = key->poll(event, fm_ipc_load(&shared->cancel) != 0, produced);
    if (error || (!key->active() && shared->operation == FM_LINUX_KEY)) {
        const int result = key->failure();
        publishAction(shared);
        complete(shared, result, fm_ipc_load64(&shared->frame));
    }
    return produced;
}

// Initializing storage does not attach or install a hook. Retained storage outlives MCP processes.
extern "C" __attribute__((visibility("default"))) int64_t fm_linux_initialize() {
    const int previousErrno = errno;
    if (initializing.test_and_set(std::memory_order_acquire)) {
        errno = previousErrno;
        return -EBUSY;
    }
    int64_t result;
    if (fm_ipc_load(&fm_linux_resident.initialized)) {
        result = fm_linux_resident.descriptor;
    } else {
        const int descriptor = syscall(SYS_memfd_create, "factorio-mcp", MFD_CLOEXEC | MFD_ALLOW_SEALING);
        if (descriptor < 0) {
            result = -errno;
        } else if (ftruncate(descriptor, sizeof(FmLinuxShared)) != 0 ||
                   fcntl(descriptor, F_ADD_SEALS, F_SEAL_SHRINK | F_SEAL_GROW | F_SEAL_SEAL) != 0) {
            result = -errno;
            close(descriptor);
        } else {
            void *mapping = mmap(nullptr, sizeof(FmLinuxShared), PROT_READ | PROT_WRITE, MAP_SHARED, descriptor, 0);
            if (mapping == MAP_FAILED) {
                result = -errno;
                close(descriptor);
            } else {
                auto *shared = static_cast<FmLinuxShared *>(mapping);
                shared->process = getpid();
                fm_ipc_store(&shared->initialized, 1);
                fm_linux_resident.mapping = reinterpret_cast<uint64_t>(mapping);
                fm_linux_resident.descriptor = descriptor;
                fm_ipc_store(&fm_linux_resident.initialized, 1);
                result = descriptor;
            }
        }
    }
    initializing.clear(std::memory_order_release);
    errno = previousErrno;
    return result;
}
