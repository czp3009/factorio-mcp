#include "resident_hooks.h"
#include <algorithm>
#include <capstone/capstone.h>
#include <cstring>
#include <fstream>
#include <limits>
#include <sstream>
#include <stdexcept>
#include <sys/mman.h>
#include <unistd.h>
#include <vector>

namespace {
// Linux UAPI MAP_FIXED_NOREPLACE, absent from the Kotlin/Native sysroot headers.
constexpr int map_fixed_noreplace = 0x100000;
constexpr size_t jump_size =
    14; // x86-64 indirect RIP-relative jump, no register clobber.
void absolute_jump(unsigned char *to, uintptr_t target) {
    const unsigned char code[] = {0xff, 0x25, 0, 0, 0, 0};
    memcpy(to, code, sizeof code);
    memcpy(to + sizeof code, &target, sizeof target);
}
void *near_page(uintptr_t target) {
    auto page = static_cast<uintptr_t>(sysconf(_SC_PAGESIZE));
    uintptr_t previous = page;
    std::ifstream maps("/proc/self/maps");
    std::string line;
    std::vector<uintptr_t> candidates;
    while (std::getline(maps, line)) {
        uintptr_t start, end;
        if (sscanf(line.c_str(), "%lx-%lx", &start, &end) != 2)
            continue;
        auto low = target > INT32_MAX ? target - INT32_MAX + page : page;
        auto first = (std::max(previous, low) + page - 1) & ~(page - 1);
        auto last =
            std::min(start - page, target + INT32_MAX - page) & ~(page - 1);
        if (first <= last)
            candidates.push_back(std::clamp(target & ~(page - 1), first, last));
        previous = end;
    }
    auto distance = [target](uintptr_t value) {
        return value > target ? value - target : target - value;
    };
    std::sort(
        candidates.begin(), candidates.end(),
        [&](uintptr_t a, uintptr_t b) { return distance(a) < distance(b); });
    for (auto candidate : candidates) {
        auto memory = mmap(
            reinterpret_cast<void *>(candidate), page, PROT_READ | PROT_WRITE,
            MAP_PRIVATE | MAP_ANONYMOUS | map_fixed_noreplace, -1, 0);
        if (memory == reinterpret_cast<void *>(candidate))
            return memory;
        // Older kernels may ignore the flag and return an unrelated address.
        if (memory != MAP_FAILED)
            munmap(memory, page);
    }
    throw std::runtime_error("No free page within range of game code");
}
} // namespace

void resident_prepare_hook(FrPatch &patch, const FrFunctionInfo &function,
                           unsigned id) {
    if (function.size < jump_size)
        throw std::runtime_error("Game entry is too short for a detour");
    csh decoder;
    if (cs_open(CS_ARCH_X86, CS_MODE_64, &decoder) != CS_ERR_OK)
        throw std::runtime_error("Cannot initialize x86 decoder");
    cs_option(decoder, CS_OPT_DETAIL, CS_OPT_ON);
    cs_insn *instructions = nullptr;
    size_t count =
        cs_disasm(decoder, reinterpret_cast<const uint8_t *>(function.address),
                  std::min<size_t>(function.size, sizeof patch.original),
                  function.address, 0, &instructions);
    unsigned char *memory = nullptr;
    try {
        memory = static_cast<unsigned char *>(near_page(function.address));
        size_t length = 0;
        uint64_t starts = 0;
        for (size_t i = 0; i < count && length < jump_size; ++i) {
            const auto &instruction = instructions[i];
            starts |= uint64_t(1) << length;
            if (cs_insn_group(decoder, &instruction, CS_GRP_JUMP) ||
                cs_insn_group(decoder, &instruction, CS_GRP_CALL) ||
                cs_insn_group(decoder, &instruction, CS_GRP_RET) ||
                cs_insn_group(decoder, &instruction, CS_GRP_INT))
                throw std::runtime_error("Unsupported control transfer in game "
                                         "entry; refusing to patch");
            memcpy(memory + length, instruction.bytes, instruction.size);
            const auto &x86 = instruction.detail->x86;
            for (unsigned n = 0; n < x86.op_count; ++n) {
                if (x86.operands[n].type != X86_OP_MEM ||
                    x86.operands[n].mem.base != X86_REG_RIP)
                    continue;
                auto displacement =
                    static_cast<int64_t>(instruction.address +
                                         instruction.size +
                                         x86.operands[n].mem.disp) -
                    static_cast<int64_t>(reinterpret_cast<uintptr_t>(memory) +
                                         length + instruction.size);
                if (x86.encoding.disp_size != sizeof(int32_t) ||
                    displacement < INT32_MIN || displacement > INT32_MAX)
                    throw std::runtime_error(
                        "RIP-relative operand cannot be relocated safely");
                int32_t value = displacement;
                memcpy(memory + length + x86.encoding.disp_offset, &value,
                       sizeof value);
            }
            length += instruction.size;
        }
        if (length < jump_size || length > sizeof patch.original)
            throw std::runtime_error("Cannot decode complete game entry");
        absolute_jump(memory + length, function.address + length);
        auto stub = memory + length + jump_size;
        stub[0] = 0x68; // push imm32; the assembly wrapper consumes this
                        // private hook index.
        memcpy(stub + 1, &id, sizeof(uint32_t));
        absolute_jump(stub + 5, reinterpret_cast<uintptr_t>(resident_before));
        patch.address = function.address;
        patch.trampoline = reinterpret_cast<uintptr_t>(memory);
        patch.length = length;
        patch.instruction_starts = starts;
        memcpy(patch.original, reinterpret_cast<void *>(function.address),
               length);
        memset(patch.replacement, 0x90, length);
        absolute_jump(patch.replacement, reinterpret_cast<uintptr_t>(stub));
        if (mprotect(memory, sysconf(_SC_PAGESIZE), PROT_READ | PROT_EXEC))
            throw std::runtime_error("Cannot protect trampoline");
    } catch (...) {
        if (memory)
            munmap(memory, sysconf(_SC_PAGESIZE));
        patch = {};
        cs_free(instructions, count);
        cs_close(&decoder);
        throw;
    }
    cs_free(instructions, count);
    cs_close(&decoder);
}
void resident_discard_hook(FrPatch &patch) {
    if (patch.trampoline)
        munmap(reinterpret_cast<void *>(patch.trampoline),
               sysconf(_SC_PAGESIZE));
    patch = {};
}
