#include "screenshot.h"
#include <d3d11.h>
#include <cstdio>
#include <memory>

int main() {
    HWND window = CreateWindowExW(0, L"STATIC", L"factorio-mcp capture fixture", WS_OVERLAPPEDWINDOW, 0, 0, 160, 120,
                                  nullptr, nullptr, GetModuleHandleW(nullptr), nullptr);
    DXGI_SWAP_CHAIN_DESC description{};
    description.BufferDesc.Width = 64;
    description.BufferDesc.Height = 48;
    description.BufferDesc.Format = DXGI_FORMAT_R8G8B8A8_UNORM;
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
        const float color[] = {1, 0, 0, 1};
        context->ClearRenderTargetView(view, color);
        try {
            auto result = std::make_unique<FmResult>();
            captureFrame(chain, *result);
            const unsigned char signature[] = {137, 80, 78, 71, 13, 10, 26, 10};
            if (result->imageWidth == 64 && result->imageHeight == 48 && result->imageSize > 40 &&
                !memcmp(result->image, signature, sizeof(signature))) {
                FILE *file{};
                if (!fopen_s(&file, "screenshot-fixture.png", "wb")) {
                    fwrite(result->image, 1, result->imageSize, file);
                    fclose(file);
                    code = 0;
                }
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
