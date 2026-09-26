#include "detour.h"
#include <algorithm>
#include <cstring>
#include <limits>
#include <stdexcept>
#include <tlhelp32.h>

static void check(bool ok, const char *text) {
    if (!ok)
        throw std::runtime_error(text);
}

Instruction decodePrologue(std::span<const unsigned char> bytes) {
    auto byte = [&](size_t offset) {
        check(offset < bytes.size(), "Truncated prologue instruction");
        return bytes[offset];
    };
    auto finish = [&](size_t length, size_t displacement = 0) -> Instruction {
        check(length <= bytes.size() && length <= 15, "Truncated or invalid prologue instruction");
        return {length, displacement};
    };
    size_t i = 0;
    bool wide = false, operand16 = false, rex = false;
    while (byte(i) == 0x66 || byte(i) == 0xf2 || byte(i) == 0xf3 || (byte(i) >= 0x40 && byte(i) <= 0x4f)) {
        check(!rex, "Unsupported prefix after REX");
        rex = byte(i) >= 0x40 && byte(i) <= 0x4f;
        if (byte(i) == 0x66)
            operand16 = true;
        if ((byte(i) & 0xf8) == 0x48)
            wide = true;
        check(++i < 8, "Unsupported instruction prefixes");
    }
    unsigned op = byte(i++);
    size_t immediate = 0;
    bool modrm = false;
    if ((op >= 0x50 && op <= 0x5f) || op == 0x90)
        return finish(i);
    if (op >= 0xb8 && op <= 0xbf)
        return finish(i + (wide ? 8 : operand16 ? 2 : 4));
    if (op == 0x68)
        return finish(i + (operand16 ? 2 : 4));
    if (op == 0x6a)
        return finish(i + 1);
    if (op == 0x0f) {
        unsigned second = byte(i++);
        check(second == 0x1f || second == 0x1e || second == 0x10 || second == 0x11 || second == 0x28 ||
                  second == 0x29 || second == 0x57 || second == 0x6f || second == 0x7f || second == 0xb6 ||
                  second == 0xb7 || second == 0xbe || second == 0xbf,
              "Unsupported two-byte prologue instruction");
        modrm = true;
    } else {
        switch (op) {
        case 0x88:
        case 0x89:
        case 0x8a:
        case 0x8b:
        case 0x8d:
        case 0x85:
        case 0x84:
        case 0x31:
        case 0x33:
        case 0x29:
        case 0x2b:
        case 0x39:
        case 0x3b:
        case 0x63:
            modrm = true;
            break;
        case 0x80:
        case 0x83:
        case 0xc6:
            modrm = true;
            immediate = 1;
            break;
        case 0x81:
        case 0xc7:
            modrm = true;
            immediate = operand16 && !wide ? 2 : 4;
            break;
        default:
            throw std::runtime_error("Unsupported prologue instruction; target was not patched");
        }
    }
    check(modrm, "Invalid decoder state");
    unsigned m = byte(i++), mode = m >> 6, rm = m & 7;
    if (op == 0xc6 || op == 0xc7)
        check((m & 0x38) == 0, "Unsupported immediate group instruction; transactional branches cannot be relocated");
    size_t displacement = 0;
    if (mode != 3) {
        if (rm == 4) {
            unsigned sib = byte(i++);
            if (mode == 0 && (sib & 7) == 5)
                i += 4;
        } else if (mode == 0 && rm == 5) {
            displacement = i;
            i += 4;
        }
        if (mode == 1)
            i++;
        else if (mode == 2)
            i += 4;
    }
    i += immediate;
    return finish(i, displacement);
}

static void jump(unsigned char *at, void *to) {
    at[0] = 0xff;
    at[1] = 0x25;
    memset(at + 2, 0, 4);
    memcpy(at + 6, &to, 8);
}

static unsigned slots(unsigned operation, unsigned info) {
    switch (operation) {
    case 0:
    case 2:
    case 3:
    case 10:
        return 1;
    case 1:
        return info ? 3 : 2;
    case 4:
    case 8:
        return 2;
    case 5:
    case 9:
        return 3;
    default:
        throw std::runtime_error("Unsupported unwind operation");
    }
}

void Detour::prepare(void *entry, void *destination) {
    check(!target, "Detour already prepared");
    if (code) {
        RtlDeleteFunctionTable(&unwind);
        VirtualFree(code, 0, MEM_RELEASE);
        code = nullptr;
    }
    length = 0;
    boundaries.clear();
    auto *source = static_cast<unsigned char *>(entry);
    DWORD64 imageBase{};
    const auto *originalFunction = RtlLookupFunctionEntry(reinterpret_cast<DWORD64>(entry), &imageBase, nullptr);
    check(originalFunction, "Detour requires a discoverable unwind function range");
    size_t bound = original.size();
    if (originalFunction) {
        check(imageBase + originalFunction->BeginAddress == reinterpret_cast<uint64_t>(entry),
              "Detour must start at an unwind function entry");
        check(originalFunction->EndAddress > originalFunction->BeginAddress, "Invalid unwind function range");
        bound = std::min(bound, size_t(originalFunction->EndAddress - originalFunction->BeginAddress));
    }
    // Snapshot only accessible bytes before decoding. A short function or an unreadable adjacent
    // page must fail without touching either the entry point or inaccessible memory.
    size_t available = 0;
    while (available < bound) {
        MEMORY_BASIC_INFORMATION region{};
        auto *at = source + available;
        if (!VirtualQuery(at, &region, sizeof(region)) || region.State != MEM_COMMIT ||
            (region.Protect & (PAGE_NOACCESS | PAGE_GUARD)))
            break;
        const auto regionEnd = reinterpret_cast<uintptr_t>(region.BaseAddress) + region.RegionSize;
        const auto count = std::min(bound - available, size_t(regionEnd - reinterpret_cast<uintptr_t>(at)));
        SIZE_T copied{};
        check(count && ReadProcessMemory(GetCurrentProcess(), at, original.data() + available, count, &copied) &&
                  copied == count,
              "Cannot read detour entry point");
        available += count;
    }
    const std::span<const unsigned char> prefix(original.data(), available);
    while (length < 14) {
        boundaries.push_back(length);
        length += decodePrologue(prefix.subspan(length)).length;
    }
    check(length <= original.size(), "Prologue exceeds detour bound");
    SYSTEM_INFO system{};
    GetSystemInfo(&system);
    uintptr_t center = reinterpret_cast<uintptr_t>(entry) & ~uintptr_t(system.dwAllocationGranularity - 1);
    for (uintptr_t delta = system.dwAllocationGranularity; delta < 0x70000000 && !code;
         delta += system.dwAllocationGranularity) {
        for (int direction : {1, -1}) {
            auto address = center + direction * delta;
            MEMORY_BASIC_INFORMATION info{};
            if (VirtualQuery(reinterpret_cast<void *>(address), &info, sizeof(info)) && info.State == MEM_FREE)
                code = static_cast<unsigned char *>(
                    VirtualAlloc(reinterpret_cast<void *>(address), 4096, MEM_RESERVE | MEM_COMMIT, PAGE_READWRITE));
            if (code)
                break;
        }
    }
    check(code, "Cannot allocate a nearby trampoline");
    memcpy(code, original.data(), length);
    for (size_t offset : boundaries) {
        auto instruction = decodePrologue(prefix.subspan(offset));
        if (instruction.displacement) {
            int32_t old{};
            memcpy(&old, original.data() + offset + instruction.displacement, 4);
            intptr_t next = reinterpret_cast<intptr_t>(source) - reinterpret_cast<intptr_t>(code) + old;
            check(next >= INT32_MIN && next <= INT32_MAX, "RIP-relative relocation is out of range");
            int32_t value = static_cast<int32_t>(next);
            memcpy(code + offset + instruction.displacement, &value, 4);
        }
    }
    jump(code + length, source + length);
    // Register unwind metadata for the copied prefix, not for instructions that have not run yet.
    auto *out = code + 256;
    out[0] = 1;
    out[1] = static_cast<unsigned char>(length);
    out[2] = 0;
    out[3] = 0;
    if (originalFunction) {
        const auto *in = reinterpret_cast<const unsigned char *>(imageBase + originalFunction->UnwindData);
        check((in[0] & 7) == 1 && !(in[0] & 0x20), "Unsupported chained unwind metadata");
        for (unsigned j = 0; j < in[2];) {
            unsigned op = in[5 + 2 * j] & 15, info = in[5 + 2 * j] >> 4, n = slots(op, info);
            check(j + n <= in[2], "Malformed unwind codes");
            if (in[4 + 2 * j] <= length) {
                memcpy(out + 4 + 2 * out[2], in + 4 + 2 * j, n * 2);
                out[2] += static_cast<unsigned char>(n);
                if (op == 3)
                    out[3] = in[3];
            }
            j += n;
        }
    }
    unwind.BeginAddress = 0;
    unwind.EndAddress = static_cast<DWORD>(length + 14);
    unwind.UnwindData = 256;
    check(RtlAddFunctionTable(&unwind, 1, reinterpret_cast<DWORD64>(code)) != FALSE,
          "Cannot register trampoline unwind data");
    DWORD protection{};
    check(VirtualProtect(code, 4096, PAGE_EXECUTE_READ, &protection) != FALSE, "Cannot protect trampoline");
    FlushInstructionCache(GetCurrentProcess(), code, 4096);
    target = entry;
    replacement = destination;
    // Trampolines and their unwind tables remain owned by the retained module across detach/reuse.
}

void Detour::change(const std::vector<Detour *> &hooks, bool enabled) {
    struct Protection {
        void *address;
        size_t length;
        DWORD original{};
    };

    // Allocate and query before suspending threads, which may own allocator locks.
    std::vector<Protection> protections;
    for (auto *hook : hooks) {
        check(hook && hook->target && hook->code, "Cannot change an unprepared detour");
        auto start = reinterpret_cast<uintptr_t>(hook->target);
        const auto end = start + hook->length;
        for (auto *other : hooks)
            if (other != hook && other && other->target) {
                const auto otherStart = reinterpret_cast<uintptr_t>(other->target);
                check(end <= otherStart || start >= otherStart + other->length, "Overlapping detour targets");
            }
        while (start < end) {
            MEMORY_BASIC_INFORMATION region{};
            check(VirtualQuery(reinterpret_cast<void *>(start), &region, sizeof(region)) && region.State == MEM_COMMIT,
                  "Detour target is not committed memory");
            const auto finish = std::min(end, reinterpret_cast<uintptr_t>(region.BaseAddress) + region.RegionSize);
            protections.push_back({reinterpret_cast<void *>(start), finish - start});
            start = finish;
        }
    }

    struct Thread {
        HANDLE handle;
        CONTEXT context;
        CONTEXT original;
        bool suspended;
        bool changed;
    };

    std::vector<Thread> threads;
    HANDLE snapshot = CreateToolhelp32Snapshot(TH32CS_SNAPTHREAD, 0);
    check(snapshot != INVALID_HANDLE_VALUE, "Cannot enumerate threads for patch transaction");
    THREADENTRY32 entry{};
    entry.dwSize = sizeof(entry);
    if (Thread32First(snapshot, &entry))
        do {
            if (entry.th32OwnerProcessID == GetCurrentProcessId() && entry.th32ThreadID != GetCurrentThreadId()) {
                HANDLE h = OpenThread(THREAD_SUSPEND_RESUME | THREAD_GET_CONTEXT | THREAD_SET_CONTEXT |
                                          THREAD_QUERY_INFORMATION,
                                      FALSE, entry.th32ThreadID);
                if (h)
                    threads.push_back({h, {}, {}, false, false});
                else if (GetLastError() != ERROR_INVALID_PARAMETER) {
                    CloseHandle(snapshot);
                    for (auto &t : threads)
                        CloseHandle(t.handle);
                    throw std::runtime_error("Cannot open every live thread for patching");
                }
            }
        } while (Thread32Next(snapshot, &entry));
    const DWORD enumerationError = GetLastError();
    CloseHandle(snapshot);
    if (enumerationError != ERROR_NO_MORE_FILES) {
        for (auto &t : threads)
            CloseHandle(t.handle);
        throw std::runtime_error("Incomplete thread enumeration; target was not patched");
    }
    bool success = true;
    for (auto &t : threads) {
        t.suspended = SuspendThread(t.handle) != DWORD(-1);
        if (t.suspended) {
            t.context.ContextFlags = CONTEXT_CONTROL;
            if (!GetThreadContext(t.handle, &t.context))
                success = false;
            else
                t.original = t.context;
        } else {
            DWORD exit{};
            if (!GetExitCodeThread(t.handle, &exit) || exit == STILL_ACTIVE)
                success = false;
        }
    }
    for (auto *h : hooks) {
        std::array<unsigned char, 64> expected = h->original;
        if (h->active) {
            jump(expected.data(), h->replacement);
            memset(expected.data() + 14, 0x90, h->length - 14);
        }
        // Another component may have changed page access since prepare. Reject it without
        // dereferencing inaccessible memory while the other threads are suspended.
        std::array<unsigned char, 64> current{};
        SIZE_T copied{};
        if (!ReadProcessMemory(GetCurrentProcess(), h->target, current.data(), h->length, &copied) ||
            copied != h->length || memcmp(current.data(), expected.data(), h->length))
            success = false;
        if (h->active == enabled)
            continue;
        for (auto &t : threads)
            if (t.suspended) {
                uintptr_t start = reinterpret_cast<uintptr_t>(enabled ? h->target : h->code), pc = t.context.Rip;
                if (pc >= start && pc < start + h->length) {
                    size_t offset = pc - start;
                    bool boundary = false;
                    for (auto b : h->boundaries)
                        if (b == offset)
                            boundary = true;
                    if (!boundary) {
                        success = false;
                        continue;
                    }
                    t.context.Rip = reinterpret_cast<uintptr_t>(enabled ? h->code : h->target) + offset;
                }
            }
    }
    // Change protections before writing so a failure cannot leave half an installed set.
    if (success)
        for (auto &region : protections)
            if (!VirtualProtect(region.address, region.length, PAGE_EXECUTE_READWRITE, &region.original))
                success = false;
    if (success) {
        for (auto &t : threads)
            if (t.suspended && t.context.Rip != t.original.Rip) {
                t.changed = SetThreadContext(t.handle, &t.context) != FALSE;
                if (!t.changed)
                    success = false;
            }
        if (success)
            for (auto *h : hooks) {
                if (enabled) {
                    jump(static_cast<unsigned char *>(h->target), h->replacement);
                    memset(static_cast<unsigned char *>(h->target) + 14, 0x90, h->length - 14);
                } else
                    memcpy(h->target, h->original.data(), h->length);
                h->active = enabled;
                FlushInstructionCache(GetCurrentProcess(), h->target, h->length);
            }
    }
    if (!success)
        for (auto &t : threads)
            if (t.changed)
                SetThreadContext(t.handle, &t.original);
    // Overlapping pages must unwind protections in reverse acquisition order.
    for (size_t i = protections.size(); i-- > 0;)
        if (protections[i].original) {
            DWORD unused;
            VirtualProtect(protections[i].address, protections[i].length, protections[i].original, &unused);
        }
    for (auto &t : threads) {
        if (t.suspended)
            ResumeThread(t.handle);
        CloseHandle(t.handle);
    }
    check(success, "Safe patch transaction failed");
}
