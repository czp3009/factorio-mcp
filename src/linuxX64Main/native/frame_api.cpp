#include "frame_api.h"
#include "memory_read.h"
#include <array>
#include <cerrno>

int readFrameApi(const FmLinuxFrameApiConfig &config, FrameReadbackApi &output) {
    output = {};
    const std::array entries{config.getInteger, config.bindFramebuffer, config.bindBuffer,
        config.pixelStore, config.readPixels, config.getError};
    for (size_t index = 0; index < entries.size(); ++index) {
        const auto &entry = entries[index];
        if (!fm::addressRange(entry.storage, sizeof(uintptr_t)) || entry.storage % alignof(uintptr_t) ||
            !fm::addressRange(entry.function, 1))
            return EINVAL;
        for (size_t previous = 0; previous < index; ++previous) {
            if (entry.storage == entries[previous].storage)
                return EINVAL;
        }
    }
    for (const auto &entry : entries) {
        uintptr_t actual;
        if (!fm::read(entry.storage, actual))
            return EFAULT;
        if (actual != entry.function)
            return ESTALE;
    }
    output.getInteger = reinterpret_cast<decltype(output.getInteger)>(config.getInteger.function);
    output.bindFramebuffer = reinterpret_cast<decltype(output.bindFramebuffer)>(config.bindFramebuffer.function);
    output.bindBuffer = reinterpret_cast<decltype(output.bindBuffer)>(config.bindBuffer.function);
    output.pixelStore = reinterpret_cast<decltype(output.pixelStore)>(config.pixelStore.function);
    output.readPixels = reinterpret_cast<decltype(output.readPixels)>(config.readPixels.function);
    output.getError = reinterpret_cast<decltype(output.getError)>(config.getError.function);
    return 0;
}
