#pragma once
#include <stdint.h>

// Text-only owned metadata. No borrowed game objects or rendered media cross the wire.
typedef struct FmUiMediaBuffer {
    uint64_t address, size;
} FmUiMediaBuffer;

typedef struct FmUiImage {
    uint32_t status, spriteObserved;
    int32_t reference;
    uint32_t nameTruncated;
    FmUiMediaBuffer type, name;
} FmUiImage;

// Locations and extents come from matching metadata or decoded executable evidence.
typedef struct FmUiImageLayout {
    uint64_t type, imageType, staticType;
    uint32_t supported, widgetSize, image, imageSize, staticSize, sprite;
} FmUiImageLayout;

#ifdef __cplusplus
inline bool validUiImageLayout(const FmUiImageLayout &value) {
    if (!value.supported)
        return !value.type && !value.imageType && !value.staticType && !value.widgetSize && !value.image &&
               !value.imageSize && !value.staticSize && !value.sprite;
    auto member = [](uint32_t offset, uint32_t size) {
        return offset >= 8 && offset % 8 == 0 && offset <= size && 8 <= size - offset;
    };
    return value.supported == 1 && value.type && value.imageType && value.staticType &&
           value.type % 8 == 0 && value.imageType % 8 == 0 && value.staticType % 8 == 0 &&
           value.type != value.imageType && value.type != value.staticType && value.imageType != value.staticType &&
           value.widgetSize >= 8 && value.widgetSize <= 16 * 1024 * 1024 && member(value.image, value.widgetSize) &&
           value.imageSize >= 8 && value.imageSize <= value.staticSize && value.staticSize <= 65536 &&
           member(value.sprite, value.staticSize);
}
#endif
