#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include "targeter_fixture.h"

void Targeter::attachTo(Targetable *value) {
    if (target) {
        if (previous)
            previous->next = next;
        else
            target->head = next;
        if (next)
            next->previous = previous;
        target = nullptr;
        previous = nullptr;
        next = nullptr;
    }
    if (value) {
        next = value->head;
        if (next)
            next->previous = this;
        target = value;
        value->head = this;
    }
}

void Targetable::clear() {
    while (head) {
        Targeter *record = head;
        if (record->target) {
            if (record->previous)
                record->previous->next = record->next;
            else
                record->target->head = record->next;
            if (record->next)
                record->next->previous = record->previous;
            record->target = nullptr;
            record->previous = nullptr;
            record->next = nullptr;
        }
    }
}

extern "C" {
size_t fixture_target = offsetof(Targeter, target);
size_t fixture_previous = offsetof(Targeter, previous);
size_t fixture_next = offsetof(Targeter, next);
size_t fixture_head = offsetof(Targetable, head);
}

#ifndef FM_TARGETER_ADAPTER_FIXTURE
int main() {
    Targetable owner;
    Targeter first, middle, last;
    first.attachTo(&owner);
    middle.attachTo(&owner);
    last.attachTo(&owner);
    middle.attachTo(nullptr);
    if (last.next != &first || first.previous != &last || middle.target || middle.previous || middle.next)
        std::abort();
    last.attachTo(nullptr);
    if (owner.head != &first || first.previous)
        std::abort();
    first.attachTo(nullptr);
    if (owner.head)
        std::abort();
    first.attachTo(nullptr);
}
#endif
