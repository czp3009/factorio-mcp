#include <cstddef>
#include <cstdint>
#include <cstring>

#ifndef FIXTURE_PADDING
#define FIXTURE_PADDING 1
#endif

using Reader = const char *(*)(void *, void *, size_t *);

struct Stream {
    unsigned char padding[FIXTURE_PADDING]{};
    size_t count{};
    const char *bytes{};
    Reader reader{};
    void *userdata{};
    void *state{};
};

struct Parser {
    unsigned char padding[FIXTURE_PADDING]{};
    Stream *stream{};
    const char *mode{};
    const char *name{};
};

struct State {
    unsigned char padding[FIXTURE_PADDING]{};
    char *top{};
    char *base{};
    intptr_t error{};
    uint16_t nonYield{};
    unsigned cleanups{};
};

extern "C" {
extern const uint64_t fixture_stream_offset = offsetof(Parser, stream);
extern const uint64_t fixture_count_offset = offsetof(Stream, count);
extern const uint64_t fixture_callback_offset = offsetof(Stream, reader);
extern const uint64_t fixture_state_offset = offsetof(Stream, state);
extern const uint64_t fixture_userdata_offset = offsetof(Stream, userdata);
extern const uint64_t fixture_mode_offset = offsetof(Parser, mode);
extern const uint64_t fixture_name_offset = offsetof(Parser, name);
extern const uint64_t fixture_top_offset = offsetof(State, top);
extern const uint64_t fixture_base_offset = offsetof(State, base);
extern const uint64_t fixture_error_offset = offsetof(State, error);
extern const uint64_t fixture_counter_offset = offsetof(State, nonYield);
extern const uint64_t fixture_state_size = sizeof(State);

__attribute__((noinline)) int fixture_parser(void *, void *userdata) {
    auto *parser = static_cast<Parser *>(userdata);
    auto *stream = parser->stream;
    int first;
    if (stream->count-- > 0) {
        first = static_cast<unsigned char>(*stream->bytes++);
    } else {
        size_t size;
        auto *bytes = stream->reader(stream->state, stream->userdata, &size);
        if (!bytes || !size)
            return -1;
        stream->count = size - 1;
        stream->bytes = bytes + 1;
        first = static_cast<unsigned char>(*bytes);
    }
    if (first == 27) {
        if (parser->mode && !std::strchr(parser->mode, 'b'))
            return -2;
        if (parser->name && *parser->name == '!')
            return -4;
    } else if (parser->mode && !std::strchr(parser->mode, 't')) {
        return -3;
    }
    return first;
}

__attribute__((noinline)) int fixture_protected(State *state, int (*callback)(void *, void *), void *userdata,
                                              intptr_t savedTop, intptr_t error) {
    const auto *parser = static_cast<Parser *>(userdata);
    if (savedTop != state->top - state->base || error != state->error ||
        std::strcmp(parser->mode, "t") || std::strcmp(parser->name, "fixture"))
        return -1;
    return callback(state, userdata);
}

__attribute__((noinline)) unsigned fixture_cleanup(State *state) {
    return ++state->cleanups + 29;
}

__attribute__((noinline)) int fixture_load(State *state, Reader reader, void *userdata, const char *name, const char *mode) {
    Stream stream{};
    stream.reader = reader;
    stream.userdata = userdata;
    stream.state = state;
    Parser parser{};
    parser.stream = &stream;
    parser.name = name ? name : "fixture";
    parser.mode = mode;
    state->nonYield++;
    const int result = fixture_protected(state, fixture_parser, &parser, state->top - state->base, state->error);
    fixture_cleanup(state);
    state->nonYield--;
    return result;
}
}

struct Input {
    void *expected;
    unsigned calls{};
    const char bytes[3]{'a', 0, 'b'};
};

const char *reader(void *state, void *userdata, size_t *size) {
    auto *input = static_cast<Input *>(userdata);
    input->calls++;
    *size = state == input->expected ? sizeof(input->bytes) : 0;
    return input->bytes;
}

int main() {
    State state;
    char stack[16]{};
    state.base = stack;
    state.top = stack + 3;
    state.error = 19;
    Input input{&state};
    Stream stream;
    stream.reader = reader;
    stream.state = &state;
    stream.userdata = &input;
    Parser parser;
    parser.stream = &stream;
    if (fixture_parser(&state, &parser) != 'a' || fixture_parser(&state, &parser) != 0 ||
        fixture_parser(&state, &parser) != 'b' || input.calls != 1 || stream.count != 0)
        return 1;
    input.calls = 0;
    if (fixture_load(&state, reader, &input, "fixture", "t") != 'a' || input.calls != 1 || state.nonYield || state.cleanups != 1)
        return 2;
    const auto parse = [&](Input &source, const char *mode, const char *name) {
        Stream current;
        current.reader = reader;
        current.state = &state;
        current.userdata = &source;
        Parser request;
        request.stream = &current;
        request.mode = mode;
        request.name = name;
        return fixture_parser(&state, &request);
    };
    Input binary{&state, 0, {27, 0, 'b'}};
    return parse(binary, "b", "fixture") == 27 && parse(binary, "t", "fixture") == -2 &&
                   parse(binary, "b", "!") == -4 && parse(input, "b", "fixture") == -3 ? 0 : 3;
}
