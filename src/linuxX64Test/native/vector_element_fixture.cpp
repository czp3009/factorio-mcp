#include <cassert>
#include <cstddef>
#include <cstdint>
#include <new>
#include <vector>

static unsigned destroyed;

struct FixtureElement {
    uint64_t padding[FIXTURE_PADDING];
    unsigned value = 0;
    __attribute__((noinline)) ~FixtureElement();
};

FixtureElement::~FixtureElement() {
    destroyed += value;
}

using Elements = std::vector<FixtureElement>;

extern "C" {
extern const size_t fixture_element_size = sizeof(FixtureElement);

__attribute__((noinline)) void fixture_destroy_elements(Elements *elements) {
    elements->~Elements();
}
}

int main() {
    alignas(Elements) unsigned char storage[sizeof(Elements)];
    auto *elements = new (storage) Elements(3);
    for (auto &element : *elements)
        element.value = 2;
    fixture_destroy_elements(elements);
    assert(destroyed == 6);
    elements = new (storage) Elements();
    fixture_destroy_elements(elements);
    assert(destroyed == 6);
}
