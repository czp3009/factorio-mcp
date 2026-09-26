#pragma once
#include <array>
#include <cstdint>
#include <span>
#include <vector>
#include <windows.h>

struct Instruction {
    size_t length;
    size_t displacement;
};

Instruction decodePrologue(std::span<const unsigned char> bytes);

class Detour {
  public:
    Detour() = default;
    Detour(const Detour &) = delete;
    Detour &operator=(const Detour &) = delete;

    void prepare(void *target, void *replacement);

    void *trampoline() const {
        return target ? code : nullptr;
    }

    static void change(const std::vector<Detour *> &hooks, bool enabled);

    bool installed() const {
        return active;
    }

  private:
    void *target{};
    void *replacement{};
    unsigned char *code{};
    size_t length{};
    bool active{};
    std::array<unsigned char, 64> original{};
    std::vector<size_t> boundaries;
    RUNTIME_FUNCTION unwind{};
};
