#include "frame_readback.h"
#include <array>
#include <cassert>
#include <cerrno>
#include <cstring>
#include <map>

namespace {
std::map<uint32_t, int32_t> state;
uint32_t error;
bool failRead;
unsigned reads, changes;

void integer(uint32_t name, int32_t *value) {
    *value = state.at(name);
    if (name == 0x0c02)
        assert(state.at(0x8caa) == 0);
}

void framebuffer(uint32_t target, uint32_t value) {
    assert(target == 0x8ca8);
    state[0x8caa] = value;
    ++changes;
}

void buffer(uint32_t target, uint32_t value) {
    assert(target == 0x88eb);
    state[0x88ed] = value;
    ++changes;
}

void store(uint32_t name, int32_t value) {
    state.at(name) = value;
    ++changes;
}

uint32_t getError() {
    const auto value = error;
    error = 0;
    return value;
}

void read(int32_t x, int32_t y, int32_t width, int32_t height, uint32_t format, uint32_t type, void *pixels) {
    assert(!x && !y && width == 2 && height == 3 && format == 0x1907 && type == 0x1401);
    assert(!state.at(0x8caa) && !state.at(0x88ed) && state.at(0x0d05) == 1);
    assert(!state.at(0x0d02) && !state.at(0x0d03) && !state.at(0x0d04));
    ++reads;
    if (failRead) {
        error = 0x0502;
        return;
    }
    for (unsigned i = 0; i < 18; ++i)
        static_cast<unsigned char *>(pixels)[i] = i;
}

const FrameReadbackApi api{integer, framebuffer, buffer, store, read, getError};

void reset() {
    state = {{0x8caa, 17}, {0x88ed, 23}, {0x0c02, 0x0405}, {0x0d05, 8},
             {0x0d02, 64}, {0x0d03, 3}, {0x0d04, 7}};
    error = reads = changes = 0;
    failRead = false;
}
}

int main() {
    for (unsigned mode = 0; mode < 4; ++mode) {
        reset();
        if (mode == 1) failRead = true;
        if (mode == 2) state[0x0c02] = 0x0404;
        if (mode == 3) error = 0x0500;
        const auto original = state;
        std::array<unsigned char, 20> pixels{};
        pixels.front() = pixels.back() = 0xa5;
        size_t written = 99;
        uint32_t reported = 99;
        const int result = readFrame(api, 2, 3, pixels.data() + 1, 18, written, reported);
        assert(state == original && pixels.front() == 0xa5 && pixels.back() == 0xa5);
        if (!mode) {
            assert(!result && !reported && written == 18 && reads == 1);
            for (unsigned row = 0; row < 3; ++row)
                for (unsigned column = 0; column < 6; ++column)
                    assert(pixels[1 + row * 6 + column] == (2 - row) * 6 + column);
        } else {
            assert(!written);
            assert(result == (mode == 1 ? EIO : mode == 2 ? ENOTSUP : EPROTO));
            assert(reported == (mode == 1 ? 0x0502u : mode == 3 ? 0x0500u : 0u));
            if (mode == 3) assert(!changes && !reads);
        }
    }
    reset();
    unsigned char pixel[18]{};
    size_t written;
    uint32_t reported;
    assert(readFrame(api, 2, 3, pixel, 17, written, reported) == ENOBUFS);
    assert(readFrame(api, 0, 3, pixel, sizeof(pixel), written, reported) == EINVAL);
    assert(readFrame(api, 8192, 8192, pixel, sizeof(pixel), written, reported) == EINVAL);
    assert(readFrame({}, 2, 3, pixel, sizeof(pixel), written, reported) == EINVAL);
    assert(!reads && !changes && !written);
}
