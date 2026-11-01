#include "screenshot.h"
#include "protocol.h"
#include <d3d11.h>

template <class T> class ComPtr {
    T *value{};

  public:
    ComPtr() = default;
    ComPtr(const ComPtr &) = delete;
    ComPtr &operator=(const ComPtr &) = delete;

    ~ComPtr() {
        if (value)
            value->Release();
    }

    T *Get() const {
        return value;
    }

    T *operator->() const {
        return value;
    }

    T **operator&() {
        return &value;
    }
};

static void checked(HRESULT result, const char *message) {
    require(SUCCEEDED(result), message);
}

void captureFrame(IDXGISwapChain *swapChain, FmResult &result, ObservationStorage &storage) {
    ComPtr<ID3D11Texture2D> buffer;
    checked(swapChain->GetBuffer(0, IID_PPV_ARGS(&buffer)), "Cannot access the current DirectX frame");
    D3D11_TEXTURE2D_DESC description{};
    buffer->GetDesc(&description);
    require(description.Width && description.Height && description.Width <= 8192 && description.Height <= 8192 &&
                uint64_t(description.Width) * description.Height <= 16777216,
            "Frame dimensions exceed capture bounds");
    const bool rgba =
        description.Format == DXGI_FORMAT_R8G8B8A8_UNORM || description.Format == DXGI_FORMAT_R8G8B8A8_UNORM_SRGB;
    require(rgba || description.Format == DXGI_FORMAT_B8G8R8A8_UNORM ||
                description.Format == DXGI_FORMAT_B8G8R8A8_UNORM_SRGB,
            "Unsupported framebuffer pixel format");
    require(description.SampleDesc.Count == 1, "Multisampled frame capture is unsupported");
    ComPtr<ID3D11Device> device;
    buffer->GetDevice(&device);
    ComPtr<ID3D11DeviceContext> context;
    device->GetImmediateContext(&context);
    description.Usage = D3D11_USAGE_STAGING;
    description.BindFlags = 0;
    description.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
    description.MiscFlags = 0;
    ComPtr<ID3D11Texture2D> staging;
    checked(device->CreateTexture2D(&description, nullptr, &staging), "Cannot allocate frame readback");
    context->CopyResource(staging.Get(), buffer.Get());
    D3D11_MAPPED_SUBRESOURCE mapped{};
    checked(context->Map(staging.Get(), 0, D3D11_MAP_READ, 0, &mapped), "Cannot read the current frame");
    auto &pixels = storage.framePixels;
    try {
        pixels.resize(size_t(description.Width) * description.Height * 3);
        for (unsigned y = 0; y < description.Height; ++y) {
            const auto *row = static_cast<const unsigned char *>(mapped.pData) + size_t(y) * mapped.RowPitch;
            auto *out = pixels.data() + size_t(y) * description.Width * 3;
            for (unsigned x = 0; x < description.Width; ++x) {
                out[x * 3] = row[x * 4 + (rgba ? 0 : 2)];
                out[x * 3 + 1] = row[x * 4 + 1];
                out[x * 3 + 2] = row[x * 4 + (rgba ? 2 : 0)];
            }
        }
    } catch (...) {
        context->Unmap(staging.Get(), 0);
        throw;
    }
    context->Unmap(staging.Get(), 0);

    result.imageWidth = description.Width;
    result.imageHeight = description.Height;
    storage.publish(result);
}
