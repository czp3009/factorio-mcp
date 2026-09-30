#include <cstddef>
#include <cstdint>
#include <cstring>

#ifndef FIXTURE_PADDING
#define FIXTURE_PADDING 1
#endif

struct Text { const char *data; uint64_t length; };
struct Element {
    unsigned char padding[FIXTURE_PADDING];
    Text name;
};
struct Owner {
    unsigned char padding[FIXTURE_PADDING];
    Element **begin;
    Element **end;
};

static Element *observed;
__attribute__((noinline)) void consume(Element *value) { observed = value; }

extern "C" {
#if FIXTURE_PADDING == 1
extern const char fixture_expected[] = "alpha";
#else
extern const char fixture_expected[] = "zebra";
#endif
extern const uint64_t fixture_owner_size = sizeof(Owner);
extern const uint64_t fixture_element_size = sizeof(Element);
extern const uint64_t fixture_begin = offsetof(Owner, begin);
extern const uint64_t fixture_end = offsetof(Owner, end);
extern const uint64_t fixture_name = offsetof(Element, name);
extern const uint64_t fixture_text_data = offsetof(Text, data);
extern const uint64_t fixture_text_length = offsetof(Text, length);

__attribute__((noinline)) void fixture_select(Owner *owner, const Text *argument) {
    Element *volatile selected;
    if (argument->length < 2) {
        if (owner->begin != owner->end && (*owner->begin)->name.length == 5) {
            auto *element = *owner->begin;
            selected = std::memcmp(element->name.data, fixture_expected, 5) == 0 ? element : nullptr;
        } else {
            selected = nullptr;
        }
    } else {
        consume(nullptr);
        selected = owner->begin == owner->end ? nullptr : *owner->begin;
    }
    consume(selected);
}
}

int main() {
    Element first{{}, {fixture_expected, 5}}, other{{}, {"bravo", 5}};
    Element *elements[] = {&first, &other};
    Owner owner{{}, elements, elements + 2};
    const Text empty{"", 0};
    fixture_select(&owner, &empty);
    if (observed != &first) return 1;
    first.name = {"xxxxx", 5};
    fixture_select(&owner, &empty);
    if (observed) return 2;
    first.name = {"alpha-extra", 11};
    fixture_select(&owner, &empty);
    if (observed) return 3;
    owner.end = owner.begin;
    fixture_select(&owner, &empty);
    return observed ? 4 : 0;
}
