#include "table_payload_fixture.h"

extern "C" void fixture_table_grow(TableQueue *queue) {
    queue->capacity = 2;
}
