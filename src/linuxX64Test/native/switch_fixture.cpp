#include <cstdint>

static volatile std::uint32_t observed;

template<unsigned Value>
__attribute__((noinline)) static std::uint32_t record(std::uint32_t input) {
    observed = input + Value;
    return observed;
}

struct Event {
    std::uint32_t type;
};

template<int Value>
__attribute__((always_inline)) static int converted() {
    int value = Value;
    asm volatile("" : "+r"(value));
    return value;
}

extern "C" __attribute__((noinline)) int fixture_key_conversion(int input) {
    switch (input) {
    case 11: return converted<419>();
    case 12: return converted<237>();
    case 13: return converted<813>();
    case 14: return converted<129>();
    case 15: return converted<731>();
    case 16: return converted<617>();
    case 17: return converted<523>();
    case 18: return converted<941>();
    default: return converted<-97>();
    }
}

extern "C" __attribute__((noinline)) std::uint32_t fixture_switch(const Event &event) {
    switch (event.type) {
    case 0: return record<19>(event.type);
    case 1: return record<37>(event.type);
    case 2: return record<13>(event.type);
    case 3: return record<29>(event.type);
    case 4: return record<31>(event.type);
    case 5: return record<17>(event.type);
    case 6: return record<23>(event.type);
    case 7: return record<41>(event.type);
    default: return record<97>(event.type);
    }
}

int main() {
    constexpr int convertedValues[] = {-97, 419, 237, 813, 129, 731, 617, 523, 941, -97};
    for (unsigned index = 0; index < 10; ++index)
        if (fixture_key_conversion(index + 10) != convertedValues[index])
            return 2;
    constexpr unsigned values[] = {19, 37, 13, 29, 31, 17, 23, 41, 97};
    for (unsigned index = 0; index < 9; ++index) {
        Event event{index};
        if (fixture_switch(event) != index + values[index]) return 1;
    }
    return 0;
}
