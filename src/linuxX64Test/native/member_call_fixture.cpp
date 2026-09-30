#include <cstddef>
#include <cstdint>

#ifndef FIXTURE_PADDING
#define FIXTURE_PADDING 1
#endif

volatile unsigned finished{};
volatile unsigned destroyed{};
volatile unsigned consumed{};

struct Leaf {
    unsigned value{1};

    __attribute__((noinline)) void finish() {
        finished = finished + value;
    }

    __attribute__((noinline)) void consume(const unsigned *input, unsigned count) {
        consumed = consumed + *input * count + value;
    }
};

struct Child {
    virtual void touch();
    virtual ~Child();
};

void Child::touch() {}
Child::~Child() {
    destroyed = destroyed + 1;
}

struct Other : Child {
    ~Other() override;
};

Other::~Other() = default;

struct Owner {
    unsigned char padding[FIXTURE_PADDING]{};
    Leaf *leaf{};
    Child *first{};
    Child *second{};
    uintptr_t cleared[2]{};
};

struct Input {
    const unsigned *data;
    unsigned count;
};

extern "C" {
extern const uint64_t fixture_owner_size = sizeof(Owner);
extern const uint64_t fixture_leaf_offset = offsetof(Owner, leaf);
extern const uint64_t fixture_first_offset = offsetof(Owner, first);
extern const uint64_t fixture_second_offset = offsetof(Owner, second);

__attribute__((noinline)) void fixture_member_calls(Owner *owner) {
    owner->leaf->finish();
    delete owner->first;
    delete owner->second;
    owner->cleared[0] = 0;
    owner->cleared[1] = 0;
    owner->leaf->finish();
    owner->cleared[0] = 0;
}

__attribute__((noinline)) void fixture_argument_reads(Owner *owner, const Input *input) {
    owner->leaf->consume(input->data, input->count);
    owner->cleared[0] = 0;
}
}

int main() {
    Leaf leaf;
    Owner owner{};
    owner.leaf = &leaf;
    owner.first = new Child;
    owner.second = new Other;
    owner.cleared[0] = 1;
    owner.cleared[1] = 2;
    fixture_member_calls(&owner);
    const unsigned number = 3;
    const Input input{&number, 7};
    fixture_argument_reads(&owner, &input);
    return finished == 2 && destroyed == 2 && consumed == 22 && !owner.cleared[0] && !owner.cleared[1] ? 0 : 1;
}
