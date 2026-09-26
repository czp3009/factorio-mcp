#include "detour.h"
#include <atomic>
#include <cstdio>
#include <cstring>
#include <stdexcept>
#include <thread>

static Detour hook;
static Detour framedHook;
extern "C" long long fixture(long long, double, long long, double, long long);
using Framed = decltype(&fixture);

static long long framedReplacement(long long a, double b, long long c, double d, long long e) {
    return reinterpret_cast<Framed>(framedHook.trampoline())(a, b, c, d, e) + 100;
}

using Function = int (*)(int);

static int replacement(int value) {
    return reinterpret_cast<Function>(hook.trampoline())(value) + 100;
}

static void expect(bool value) {
    if (!value)
        throw std::runtime_error("Fixture assertion failed");
}

int main() {
    try {
        unsigned char unsupported[]{0xeb, 0x04};
        bool rejected = false;
        try {
            decodePrologue(unsupported);
        } catch (...) {
            rejected = true;
        }
        expect(rejected);
        unsigned char xbegin[]{0xc7, 0xf8, 0x10, 0, 0, 0};
        rejected = false;
        try {
            decodePrologue(xbegin);
        } catch (const std::exception &) {
            rejected = true;
        }
        expect(rejected);
        unsigned char relative[]{0x48, 0x8b, 0x05, 0, 0, 0, 0};
        expect(decodePrologue(relative).length == 7 && decodePrologue(relative).displacement == 3);
        unsigned char wideImmediate[]{0x66, 0x48, 0xc7, 0xc0, 1, 0, 0, 0};
        expect(decodePrologue(wideImmediate).length == sizeof(wideImmediate));
        for (const auto instruction :
             {std::span<const unsigned char>(relative), std::span<const unsigned char>(wideImmediate)}) {
            for (size_t size = 0; size < instruction.size(); ++size) {
                rejected = false;
                try {
                    decodePrologue(instruction.first(size));
                } catch (const std::exception &) {
                    rejected = true;
                }
                expect(rejected);
            }
        }
        auto *guarded =
            static_cast<unsigned char *>(VirtualAlloc(nullptr, 8192, MEM_COMMIT | MEM_RESERVE, PAGE_READWRITE));
        expect(guarded != nullptr);
        guarded[4095] = 0x48;
        guarded[256] = 1;
        DWORD protection{};
        expect(VirtualProtect(guarded, 4096, PAGE_EXECUTE_READ, &protection));
        expect(VirtualProtect(guarded + 4096, 4096, PAGE_NOACCESS, &protection));
        RUNTIME_FUNCTION guardedFunction{4095, 4111, 256};
        expect(RtlAddFunctionTable(&guardedFunction, 1, reinterpret_cast<DWORD64>(guarded)));
        Detour truncated;
        rejected = false;
        try {
            truncated.prepare(guarded + 4095, reinterpret_cast<void *>(replacement));
        } catch (const std::exception &) {
            rejected = true;
        }
        expect(rejected && !truncated.trampoline() && guarded[4095] == 0x48);
        expect(RtlDeleteFunctionTable(&guardedFunction));
        expect(VirtualFree(guarded, 0, MEM_RELEASE));
        auto *code =
            static_cast<unsigned char *>(VirtualAlloc(nullptr, 4096, MEM_COMMIT | MEM_RESERVE, PAGE_READWRITE));
        expect(code != nullptr);
        // mov eax,[rip+26]; add eax,ecx; padding; ret. The data remains in the original allocation.
        memset(code, 0x90, 32);
        code[0] = 0x8b;
        code[1] = 0x05;
        int displacement = 26;
        memcpy(code + 2, &displacement, 4);
        code[6] = 0x03;
        code[7] = 0xc1;
        code[16] = 0xc3;
        int constant = 7;
        memcpy(code + 32, &constant, 4);
        memset(code + 64, 0x90, 32);
        code[80] = 0xc3;
        memset(code + 128, 0x90, 16);
        code[256] = 1;
        // Use a supported equivalent ADD encoding in this fixture's copied prefix.
        code[6] = 0x8d;
        code[7] = 0x04;
        code[8] = 0x08;
        DWORD previous{};
        expect(VirtualProtect(code, 4096, PAGE_EXECUTE_READ, &previous));
        Detour withoutBounds;
        rejected = false;
        try {
            withoutBounds.prepare(code, reinterpret_cast<void *>(replacement));
        } catch (const std::exception &) {
            rejected = true;
        }
        expect(rejected && !withoutBounds.trampoline() && code[0] == 0x8b);
        RUNTIME_FUNCTION functions[]{{0, 17, 256}, {64, 81, 256}, {128, 136, 256}};
        expect(RtlAddFunctionTable(functions, 3, reinterpret_cast<DWORD64>(code)));
        Detour shortHook;
        rejected = false;
        try {
            shortHook.prepare(code + 128, reinterpret_cast<void *>(replacement));
        } catch (const std::exception &) {
            rejected = true;
        }
        expect(rejected && !shortHook.trampoline() && code[128] == 0x90);
        auto function = reinterpret_cast<Function>(code);
        expect(function(5) == 12);
        hook.prepare(code, reinterpret_cast<void *>(replacement));
        expect(reinterpret_cast<Function>(hook.trampoline())(5) == 12);
        // An unprepared transaction member must leave an earlier valid target unchanged.
        Detour unprepared;
        rejected = false;
        try {
            Detour::change({&hook, &unprepared}, true);
        } catch (const std::exception &) {
            rejected = true;
        }
        expect(rejected && function(5) == 12);
        auto tamper = [&](unsigned char value) {
            DWORD protection{};
            expect(VirtualProtect(code, 4096, PAGE_EXECUTE_READWRITE, &protection));
            code[0] = value;
            DWORD unused{};
            expect(VirtualProtect(code, 4096, protection, &unused));
            FlushInstructionCache(GetCurrentProcess(), code, 4096);
        };
        tamper(0x90);
        rejected = false;
        try {
            Detour::change({&hook}, true);
        } catch (const std::exception &) {
            rejected = true;
        }
        expect(rejected && code[0] == 0x90 && !hook.installed());
        tamper(0x8b);
        Detour::change({&hook}, true);
        tamper(0x90);
        rejected = false;
        try {
            Detour::change({&hook}, false);
        } catch (const std::exception &) {
            rejected = true;
        }
        expect(rejected && code[0] == 0x90 && hook.installed());
        tamper(0xff);
        Detour::change({&hook}, false);
        MEMORY_BASIC_INFORMATION restored{};
        expect(VirtualQuery(code, &restored, sizeof(restored)) != 0 && restored.Protect == PAGE_EXECUTE_READ);
        auto *pages =
            static_cast<unsigned char *>(VirtualAlloc(nullptr, 8192, MEM_COMMIT | MEM_RESERVE, PAGE_READWRITE));
        expect(pages != nullptr);
        pages[256] = 1;
        memset(pages + 4096 - 8, 0x90, 32);
        expect(VirtualProtect(pages, 4096, PAGE_EXECUTE_READ, &previous));
        expect(VirtualProtect(pages + 4096, 4096, PAGE_EXECUTE_READWRITE, &previous));
        RUNTIME_FUNCTION crossingFunction{4096 - 8, 4096 + 24, 256};
        expect(RtlAddFunctionTable(&crossingFunction, 1, reinterpret_cast<DWORD64>(pages)));
        Detour crossing;
        crossing.prepare(pages + 4096 - 8, reinterpret_cast<void *>(replacement));
        for (bool install : {true, false}) {
            Detour::change({&crossing}, install);
            expect(VirtualQuery(pages, &restored, sizeof(restored)) && restored.Protect == PAGE_EXECUTE_READ);
            expect(VirtualQuery(pages + 4096, &restored, sizeof(restored)) &&
                   restored.Protect == PAGE_EXECUTE_READWRITE);
        }
        Detour samePage;
        samePage.prepare(code + 64, reinterpret_cast<void *>(replacement));
        expect(VirtualProtect(code, 4096, PAGE_NOACCESS, &previous));
        rejected = false;
        try {
            Detour::change({&hook, &samePage}, true);
        } catch (const std::exception &) {
            rejected = true;
        }
        expect(rejected && !hook.installed() && !samePage.installed());
        expect(VirtualQuery(code, &restored, sizeof(restored)) && restored.Protect == PAGE_NOACCESS);
        expect(VirtualProtect(code, 4096, previous, &previous));
        expect(function(5) == 12);
        Detour::change({&hook, &samePage}, true);
        expect(VirtualQuery(code, &restored, sizeof(restored)) != 0 && restored.Protect == PAGE_EXECUTE_READ);
        Detour::change({&hook, &samePage}, false);
        expect(VirtualQuery(code, &restored, sizeof(restored)) != 0 && restored.Protect == PAGE_EXECUTE_READ);
        framedHook.prepare(reinterpret_cast<void *>(fixture), reinterpret_cast<void *>(framedReplacement));
        expect(fixture(1, 2.0, 3, 4.0, 5) == 15);
        Detour::change({&framedHook}, true);
        expect(fixture(1, 2.0, 3, 4.0, 5) == 115);
        // Unwind from the relocated prologue after push/sub, restoring RBX and the caller PC.
        DWORD64 unwindBase{};
        auto *metadata =
            RtlLookupFunctionEntry(reinterpret_cast<DWORD64>(framedHook.trampoline()), &unwindBase, nullptr);
        expect(metadata != nullptr);
        DWORD64 stack[8]{};
        stack[4] = 0x1234;
        stack[5] = 0x5678;
        CONTEXT context{};
        context.Rip = reinterpret_cast<DWORD64>(framedHook.trampoline()) + 5;
        context.Rsp = reinterpret_cast<DWORD64>(stack);
        void *handler{};
        DWORD64 establisher{};
        RtlVirtualUnwind(UNW_FLAG_NHANDLER, unwindBase, context.Rip, metadata, &context, &handler, &establisher,
                         nullptr);
        expect(context.Rbx == 0x1234 && context.Rip == 0x5678 && context.Rsp == reinterpret_cast<DWORD64>(stack + 6));
        Detour::change({&framedHook}, false);
        expect(fixture(1, 2.0, 3, 4.0, 5) == 15);
        DWORD64 base{};
        expect(RtlLookupFunctionEntry(reinterpret_cast<DWORD64>(hook.trampoline()), &base, nullptr) != nullptr);
        std::atomic<bool> stop{}, failed{};
        std::thread worker([&] {
            while (!stop) {
                int result = function(5);
                if (result != 12 && result != 112)
                    failed = true;
            }
        });
        for (int i = 0; i < 100; i++) {
            Detour::change({&hook}, true);
            expect(function(5) == 112);
            Detour::change({&hook}, false);
            expect(function(5) == 12);
        }
        stop = true;
        worker.join();
        expect(!failed);
        expect(RtlDeleteFunctionTable(&crossingFunction));
        expect(RtlDeleteFunctionTable(functions));
        std::puts("PASS: decoder rejection, RIP relocation, transaction rollback, ABI/unwind, concurrent "
                  "install/remove, reuse");
        return 0;
    } catch (const std::exception &e) {
        std::fprintf(stderr, "FAIL: %s\n", e.what());
        return 1;
    }
}
