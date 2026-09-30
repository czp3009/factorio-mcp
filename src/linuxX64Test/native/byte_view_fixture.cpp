#include "byte_view_fixture.h"
#include <cassert>
#include <initializer_list>

extern "C" __attribute__((noinline)) void fixture_optional(
    Output* output, Context* context, View view, const void*, std::size_t) {
    Locale* const selected = context->selected;
    fixture_touch(selected);
    fixture_provider(output, selected, view, nullptr, 0);
}

extern "C" {
extern const std::size_t fixture_lookup_data = FIXTURE_PADDING == 1 ? 6 : 2;
extern const std::size_t fixture_lookup_length = FIXTURE_PADDING == 1 ? 2 : 6;
extern const std::size_t fixture_provider_data = FIXTURE_PADDING == 1 ? 2 : 1;
extern const std::size_t fixture_provider_length = FIXTURE_PADDING == 1 ? 1 : 2;
}

int main() {
    const char key[] = {'a', 0, 'c'};
    const char other[] = {'a', 0, 'd'};
    Locale locale{};
    Context context{};
    locale.data = key;
    locale.length = sizeof(key);
    context.selected = &locale;
    for (const char* data : {key, other}) {
        View view{};
        view.data = data;
        view.length = sizeof(key);
        Output output{};
        fixture_optional(&output, &context, view, &context, 91);
        assert((output.first == 0) == (data == key));
        assert((output.second == 0) == (data == key));
    }
    assert(locale.calls == 8);
    View view{};
    view.data = key;
    view.length = 1;
    Output output{};
    fixture_optional(&output, &context, view, nullptr, 0);
    assert(output.first == 17 && output.second == 17);
}
