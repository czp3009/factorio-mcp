#include "screenshot.h"
#include <d3d11.h>
#include <cstdio>
#include <memory>

static int verifyCapture(DXGI_FORMAT format) {
    HWND window = CreateWindowExW(0, L"STATIC", L"factorio-mcp capture fixture", WS_OVERLAPPEDWINDOW, 0, 0, 160, 120,
                                  nullptr, nullptr, GetModuleHandleW(nullptr), nullptr);
    DXGI_SWAP_CHAIN_DESC description{};
    description.BufferDesc.Width = 64;
    description.BufferDesc.Height = 48;
    description.BufferDesc.Format = format;
    description.SampleDesc.Count = 1;
    description.BufferUsage = DXGI_USAGE_RENDER_TARGET_OUTPUT;
    description.BufferCount = 1;
    description.OutputWindow = window;
    description.Windowed = TRUE;
    IDXGISwapChain *chain{};
    ID3D11Device *device{};
    ID3D11DeviceContext *context{};
    ID3D11Texture2D *buffer{};
    ID3D11RenderTargetView *view{};
    int code = 1;
    if (SUCCEEDED(D3D11CreateDeviceAndSwapChain(nullptr, D3D_DRIVER_TYPE_WARP, nullptr, 0, nullptr, 0,
                                                D3D11_SDK_VERSION, &description, &chain, &device, nullptr, &context)) &&
        SUCCEEDED(chain->GetBuffer(0, IID_PPV_ARGS(&buffer))) &&
        SUCCEEDED(device->CreateRenderTargetView(buffer, nullptr, &view))) {
        std::vector<unsigned char> source(64 * 48 * 4);
        const bool rgba = format == DXGI_FORMAT_R8G8B8A8_UNORM;
        for (unsigned y = 0; y < 48; ++y) {
            for (unsigned x = 0; x < 64; ++x) {
                auto *pixel = source.data() + (y * 64 + x) * 4;
                pixel[rgba ? 0 : 2] = static_cast<unsigned char>(x);
                pixel[1] = static_cast<unsigned char>(y);
                pixel[rgba ? 2 : 0] = 173;
                pixel[3] = 255;
            }
        }
        context->UpdateSubresource(buffer, 0, nullptr, source.data(), 64 * 4, 0);
        try {
            auto result = std::make_unique<FmResult>();
            ObservationStorage storage;
            captureFrame(chain, *result, storage);
            if (result->imageWidth == 64 && result->imageHeight == 48 && storage.framePixels.size() == 64 * 48 * 3 &&
                result->framePixels.address == reinterpret_cast<uintptr_t>(storage.framePixels.data()) &&
                result->framePixels.size == storage.framePixels.size()) {
                code = 0;
                for (size_t at = 0; at < storage.framePixels.size(); at += 3) {
                    const size_t index = at / 3;
                    if (storage.framePixels[at] != index % 64 || storage.framePixels[at + 1] != index / 64 ||
                        storage.framePixels[at + 2] != 173)
                        code = 1;
                }
                storage = ObservationStorage{};
                storage.publish(*result);
                if (result->framePixels.address || result->framePixels.size)
                    code = 1;
            }
        } catch (const std::exception &error) {
            fprintf(stderr, "%s\n", error.what());
        }
    }
    if (view)
        view->Release();
    if (buffer)
        buffer->Release();
    if (context)
        context->Release();
    if (device)
        device->Release();
    if (chain)
        chain->Release();
    DestroyWindow(window);
    return code;
}

int main() {
    return verifyCapture(DXGI_FORMAT_R8G8B8A8_UNORM) || verifyCapture(DXGI_FORMAT_B8G8R8A8_UNORM);
}
