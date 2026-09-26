#include "screenshot.h"
#include "protocol.h"
#include <d3d11.h>
#include <wincodec.h>

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

void captureFrame(IDXGISwapChain *swapChain, FmResult &result) {
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
    std::vector<unsigned char> pixels;
    try {
        pixels.resize(size_t(description.Width) * description.Height * 3);
        for (unsigned y = 0; y < description.Height; ++y) {
            const auto *row = static_cast<const unsigned char *>(mapped.pData) + size_t(y) * mapped.RowPitch;
            auto *out = pixels.data() + size_t(y) * description.Width * 3;
            for (unsigned x = 0; x < description.Width; ++x) {
                out[x * 3] = row[x * 4 + (rgba ? 2 : 0)];
                out[x * 3 + 1] = row[x * 4 + 1];
                out[x * 3 + 2] = row[x * 4 + (rgba ? 0 : 2)];
            }
        }
    } catch (...) {
        context->Unmap(staging.Get(), 0);
        throw;
    }
    context->Unmap(staging.Get(), 0);

    const HRESULT initialized = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    require(SUCCEEDED(initialized) || initialized == RPC_E_CHANGED_MODE, "Cannot initialize image encoder");

    struct Apartment {
        HRESULT initialized;

        ~Apartment() {
            if (SUCCEEDED(initialized))
                CoUninitialize();
        }
    } apartment{initialized};

    ComPtr<IWICImagingFactory> factory;
    checked(CoCreateInstance(CLSID_WICImagingFactory, nullptr, CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&factory)),
            "Windows PNG encoder is unavailable");
    ComPtr<IStream> stream;
    checked(CreateStreamOnHGlobal(nullptr, TRUE, &stream), "Cannot allocate PNG stream");
    ComPtr<IWICBitmapEncoder> encoder;
    checked(factory->CreateEncoder(GUID_ContainerFormatPng, nullptr, &encoder), "Cannot create PNG encoder");
    checked(encoder->Initialize(stream.Get(), WICBitmapEncoderNoCache), "Cannot initialize PNG stream");
    ComPtr<IWICBitmapFrameEncode> frame;
    checked(encoder->CreateNewFrame(&frame, nullptr), "Cannot create PNG frame");
    checked(frame->Initialize(nullptr), "Cannot initialize PNG frame");
    checked(frame->SetSize(description.Width, description.Height), "Cannot set PNG size");
    auto format = GUID_WICPixelFormat24bppBGR;
    checked(frame->SetPixelFormat(&format), "Cannot set PNG pixel format");
    require(format == GUID_WICPixelFormat24bppBGR, "PNG encoder rejected BGR format");
    checked(
        frame->WritePixels(description.Height, description.Width * 3, static_cast<UINT>(pixels.size()), pixels.data()),
        "Cannot encode PNG pixels");
    checked(frame->Commit(), "Cannot finish PNG frame");
    checked(encoder->Commit(), "Cannot finish PNG image");
    STATSTG stat{};
    checked(stream->Stat(&stat, STATFLAG_NONAME), "Cannot read PNG size");
    require(stat.cbSize.QuadPart > 0 && stat.cbSize.QuadPart <= FM_MAX_IMAGE, "PNG exceeds output bound");
    checked(stream->Seek({}, STREAM_SEEK_SET, nullptr), "Cannot seek PNG stream");
    ULONG read{};
    checked(stream->Read(result.image, static_cast<ULONG>(stat.cbSize.QuadPart), &read), "Cannot read encoded PNG");
    require(read == stat.cbSize.QuadPart, "Incomplete PNG output");
    result.imageSize = read;
    result.imageWidth = description.Width;
    result.imageHeight = description.Height;
}
