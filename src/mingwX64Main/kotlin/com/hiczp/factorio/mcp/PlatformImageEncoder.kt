@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.wic.*
import kotlinx.cinterop.*
import platform.windows.*
import platform.posix.memset

internal actual object PlatformImageEncoder {
    actual fun encode(image: RgbImage, format: ImageFormat): ByteArray = memScoped {
        val (width, height, rgb) = image
        fun checked(status: Int, message: String) {
            check(status >= 0) { "$message (HRESULT $status)" }
        }

        fun release(value: CPointer<*>?) {
            val unknown = value?.reinterpret<IUnknown>() ?: return
            unknown.pointed.lpVtbl!!.pointed.Release!!(unknown)
        }

        val initialized = CoInitializeEx(null, COINIT_MULTITHREADED.toUInt())
        check(initialized >= 0 || initialized == RPC_E_CHANGED_MODE) {
            "Cannot initialize image encoder"
        }
        val factory = alloc<CPointerVar<IWICImagingFactory>>()
        val stream = alloc<CPointerVar<IStream>>()
        val encoder = alloc<CPointerVar<IWICBitmapEncoder>>()
        val frame = alloc<CPointerVar<IWICBitmapFrameEncode>>()
        val options = alloc<CPointerVar<IPropertyBag2>>()
        factory.value = null
        stream.value = null
        encoder.value = null
        frame.value = null
        options.value = null
        try {
            checked(
                CoCreateInstance(
                    CLSID_WICImagingFactory.ptr,
                    null,
                    CLSCTX_INPROC_SERVER.toUInt(),
                    IID_IWICImagingFactory.ptr,
                    factory.ptr.reinterpret(),
                ),
                "Windows image encoder is unavailable",
            )
            checked(CreateStreamOnHGlobal(null, TRUE, stream.ptr), "Cannot allocate image stream")
            val f = checkNotNull(factory.value)
            checked(
                f.pointed.lpVtbl!!.pointed.CreateEncoder!!(
                    f,
                    (if (format == ImageFormat.PNG) GUID_ContainerFormatPng else GUID_ContainerFormatJpeg).ptr,
                    null,
                    encoder.ptr,
                ),
                "Cannot create image encoder",
            )
            val e = checkNotNull(encoder.value)
            checked(
                e.pointed.lpVtbl!!.pointed.Initialize!!(e, stream.value, WICBitmapEncoderNoCache),
                "Cannot initialize image stream",
            )
            checked(
                e.pointed.lpVtbl!!.pointed.CreateNewFrame!!(e, frame.ptr, options.ptr),
                "Cannot create image frame",
            )
            val bitmap = checkNotNull(frame.value)
            val methods = bitmap.pointed.lpVtbl!!.pointed
            if (format == ImageFormat.JPG) {
                val properties = checkNotNull(options.value)
                val property = alloc<PROPBAG2>()
                memset(property.ptr, 0, sizeOf<PROPBAG2>().toULong())
                property.pstrName = "ImageQuality".wcstr.ptr
                val value = alloc<VARIANT>()
                VariantInit(value.ptr)
                value.vt = VT_R4.toUShort()
                value.fltVal = ImageEncoder.JPEG_QUALITY / 100f
                checked(properties.pointed.lpVtbl!!.pointed.Write!!(properties, 1u, property.ptr, value.ptr),
                    "Cannot set JPEG quality")
            }
            checked(methods.Initialize!!(bitmap, options.value), "Cannot initialize image frame")
            checked(
                methods.SetSize!!(bitmap, width.toUInt(), height.toUInt()),
                "Cannot set image size",
            )
            val pixelFormat = GUID_WICPixelFormat24bppBGR.readValue().getPointer(this)
            checked(methods.SetPixelFormat!!(bitmap, pixelFormat), "Cannot set image pixel format")
            val expected = GUID_WICPixelFormat24bppBGR
            check(
                pixelFormat.pointed.Data1 == expected.Data1 &&
                    pixelFormat.pointed.Data2 == expected.Data2 &&
                    pixelFormat.pointed.Data3 == expected.Data3 &&
                    (0..7).all { pixelFormat.pointed.Data4[it] == expected.Data4[it] }
            ) {
                "Image encoder rejected BGR format"
            }
            val bgr = ByteArray(rgb.size)
            var at = 0
            while (at < rgb.size) {
                bgr[at] = rgb[at + 2]
                bgr[at + 1] = rgb[at + 1]
                bgr[at + 2] = rgb[at]
                at += 3
            }
            bgr.usePinned {
                checked(
                    methods.WritePixels!!(
                        bitmap,
                        height.toUInt(),
                        (width * 3).toUInt(),
                        rgb.size.toUInt(),
                        it.addressOf(0).reinterpret(),
                    ),
                    "Cannot encode image pixels",
                )
            }
            checked(methods.Commit!!(bitmap), "Cannot finish image frame")
            checked(e.pointed.lpVtbl!!.pointed.Commit!!(e), "Cannot finish image image")
            val s = checkNotNull(stream.value)
            val stat = alloc<STATSTG>()
            checked(
                s.pointed.lpVtbl!!.pointed.Stat!!(s, stat.ptr, STATFLAG_NONAME.toUInt()),
                "Cannot read image size",
            )
            check(stat.cbSize.QuadPart in 1uL..ImageEncoder.MAX_BYTES.toULong()) {
                "image exceeds output bound"
            }
            val zero = alloc<LARGE_INTEGER>()
            zero.QuadPart = 0
            checked(
                s.pointed.lpVtbl!!.pointed.Seek!!(
                    s,
                    zero.readValue(),
                    STREAM_SEEK_SET.toUInt(),
                    null,
                ),
                "Cannot seek image stream",
            )
            val output = ByteArray(stat.cbSize.QuadPart.toInt())
            val read = alloc<ULONGVar>()
            output.usePinned {
                checked(
                    s.pointed.lpVtbl!!.pointed.Read!!(
                        s,
                        it.addressOf(0),
                        output.size.toUInt(),
                        read.ptr,
                    ),
                    "Cannot read encoded image",
                )
            }
            check(read.value == output.size.toUInt()) { "Incomplete image output" }
            output
        } finally {
            release(options.value)
            release(frame.value)
            release(encoder.value)
            release(stream.value)
            release(factory.value)
            if (initialized >= 0) CoUninitialize()
        }
    }
}
