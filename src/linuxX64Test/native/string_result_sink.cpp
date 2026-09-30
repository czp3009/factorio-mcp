#include "string_result_fixture.h"
#include <cassert>

unsigned consumed = 0;
unsigned destroyed = 0;

String::~String() {
    assert(data == local && length == 1 && data[1] == 0);
    ++destroyed;
}

extern "C" void fixture_consume(const String* value) {
    assert(value->data == value->local && value->length == 1);
    assert(value->data[0] == 'a' || value->data[0] == 'z');
    ++consumed;
}
