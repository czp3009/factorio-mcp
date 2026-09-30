#include "empty_string_fixture.h"
#include <cassert>

unsigned consumed = 0;
unsigned destroyed = 0;

String::~String() {
    assert(data == local && length <= 1 && data[length] == 0);
    ++destroyed;
}

extern "C" bool fixture_lookup(void* a, void* b, void* c, void* d, void* e) {
    assert(a == b && a == c && a == d && a == e);
    return a != nullptr;
}

extern "C" void fixture_borrow(Borrowed* value) {
    assert(value->self == value);
    for (void* entry : value->values) {
        assert(entry != nullptr);
    }
}

extern "C" void fixture_consume(const String* value) {
    assert(value->data == value->local && value->length <= 1);
    assert(value->data[0] == (value->length ? 'x' : 0));
    assert(value->data[value->length] == 0);
    ++consumed;
}
