@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.wic.*
import kotlin.test.assertTrue
import kotlinx.cinterop.*
import platform.windows.*

internal actual fun decodeImage(bytes: ByteArray): RgbImage = memScoped {
    fun checked(status: Int) = assertTrue(status >= 0, "WIC decoder HRESULT $status")
    fun release(value: CPointer<*>?) {
        val unknown = value?.reinterpret<IUnknown>() ?: return
        unknown.pointed.lpVtbl!!.pointed.Release!!(unknown)
    }
    val initialized = CoInitializeEx(null, COINIT_MULTITHREADED.toUInt())
    assertTrue(initialized >= 0 || initialized == RPC_E_CHANGED_MODE)
    val factory = alloc<CPointerVar<IWICImagingFactory>>()
    val stream = alloc<CPointerVar<IStream>>()
    val decoder = alloc<CPointerVar<IWICBitmapDecoder>>()
    val frame = alloc<CPointerVar<IWICBitmapFrameDecode>>()
    val converter = alloc<CPointerVar<IWICFormatConverter>>()
    factory.value = null
    stream.value = null
    decoder.value = null
    frame.value = null
    converter.value = null
    try {
        checked(
            CoCreateInstance(
                CLSID_WICImagingFactory.ptr,
                null,
                CLSCTX_INPROC_SERVER.toUInt(),
                IID_IWICImagingFactory.ptr,
                factory.ptr.reinterpret(),
            )
        )
        checked(CreateStreamOnHGlobal(null, TRUE, stream.ptr))
        val s = checkNotNull(stream.value)
        bytes.usePinned {
            checked(s.pointed.lpVtbl!!.pointed.Write!!(s, it.addressOf(0), bytes.size.toUInt(), null))
        }
        val zero = alloc<LARGE_INTEGER>()
        zero.QuadPart = 0
        checked(
            s.pointed.lpVtbl!!.pointed.Seek!!(s, zero.readValue(), STREAM_SEEK_SET.toUInt(), null)
        )
        val f = checkNotNull(factory.value)
        checked(
            f.pointed.lpVtbl!!.pointed.CreateDecoderFromStream!!(
                f,
                s,
                null,
                WICDecodeMetadataCacheOnLoad,
                decoder.ptr,
            )
        )
        val d = checkNotNull(decoder.value)
        checked(d.pointed.lpVtbl!!.pointed.GetFrame!!(d, 0u, frame.ptr))
        val bitmap = checkNotNull(frame.value)
        val width = alloc<UIntVar>()
        val height = alloc<UIntVar>()
        checked(bitmap.pointed.lpVtbl!!.pointed.GetSize!!(bitmap, width.ptr, height.ptr))
        checked(f.pointed.lpVtbl!!.pointed.CreateFormatConverter!!(f, converter.ptr))
        val converted = checkNotNull(converter.value)
        checked(
            converted.pointed.lpVtbl!!.pointed.Initialize!!(
                converted,
                bitmap.reinterpret(),
                GUID_WICPixelFormat24bppRGB.ptr,
                WICBitmapDitherTypeNone,
                null,
                0.0,
                WICBitmapPaletteTypeCustom,
            )
        )
        val rgb = ByteArray(width.value.toInt() * height.value.toInt() * 3)
        rgb.usePinned {
            checked(
                converted.pointed.lpVtbl!!.pointed.CopyPixels!!(
                    converted,
                    null,
                    width.value * 3u,
                    rgb.size.toUInt(),
                    it.addressOf(0).reinterpret(),
                )
            )
        }
        RgbImage(width.value.toInt(), height.value.toInt(), rgb)
    } finally {
        release(converter.value)
        release(frame.value)
        release(decoder.value)
        release(stream.value)
        release(factory.value)
        if (initialized >= 0) CoUninitialize()
    }
}
