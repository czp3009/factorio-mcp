@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.linuxbridge.FmLinuxPollHookConfig
import com.hiczp.factorio.mcp.linuxbridge.FmLinuxUiKeyConfig
import platform.posix.PROT_READ
import platform.posix.PROT_WRITE
import platform.posix._SC_PAGESIZE
import platform.posix.sysconf

internal data class UiKeyMetadata(
    val event: KeyboardEventMetadata,
    val focus: Long,
    val capture: UiCaptureMetadata,
    val modal: UiModalMetadata,
    val protection: Int,
) {
    fun writeTo(output: FmLinuxUiKeyConfig, site: FmLinuxPollHookConfig, bias: Long) {
        fun address(value: Long): ULong {
            require(bias >= 0 && value > 0 && value <= Long.MAX_VALUE - bias)
            return (value + bias).toULong()
        }
        event.writeTo(output.event)
        event.writeTo(output.clock, bias)
        capture.writeTo(output.capture, bias)
        modal.writeTo(output.modal)
        output.focus = address(focus)
        val poll = event.poll
        site.entry = address(poll.table + poll.slot * 8L)
        site.original = address(poll.poll.address)
        site.table = address(poll.table)
        site.caller = address(poll.caller.address + poll.returnOffset)
        site.pumpCaller = address(poll.pump.address + poll.pumpReturnOffset)
        site.frameReturn = poll.callerReturnFromFrame.toUInt()
        val local = poll.frame + poll.callerReturnFromFrame
        require(local in -16384..16384)
        site.eventFromFrame = local.toInt()
        site.eventExtent = event.header.extent.toUInt()
        site.protection = protection.toUInt()
    }

    companion object {
        fun resolve(image: ElfImage, process: ProcessHandle, bias: Long): UiKeyMetadata {
            val event = KeyboardEventMetadata.resolve(image).also { it.verifyLoaded(image, process, bias) }
            val (focus, functions) = image.withFunctionEvidence {
                WidgetFocusEntry.verify(image, SysVObjectSize.resolve(image, "4agui6Widget"))
            }
            for (function in functions) {
                val bytes = image.functionBytes(function, function.size.toInt())
                require(
                    process.readMemory(bias + function.address, bytes.size.toInt())
                        .contentEquals(bytes.bytes(0, bytes.size.toInt()))
                ) { "Live widget focus evidence differs from the selected executable" }
            }
            val capture = UiCaptureMetadata.resolve(image).also { it.verify(image, bias, process::readMemory) }
            val modal = UiModalMetadata.resolve(image).also { it.verify(image, bias, process::readMemory) }
            require(
                capture.guiSize == modal.guiSize && capture.widgetSize == modal.widgetSize &&
                        capture.widgetTargetable == modal.widgetTargetable
            )
            val entry = bias + event.poll.table + event.poll.slot * 8L
            val pageSize = sysconf(_SC_PAGESIZE)
            val page = entry and -pageSize
            val region = process.executableMappings().singleOrNull {
                it.start <= page && it.end >= page + pageSize &&
                        it.readable && !it.executable && it.permissions[3] == 'p'
            }
                ?: error("Event poll entry is not private readable data within one mapped page")
            require(BinaryView(process.readMemory(entry, 8)).unsigned(0, 8) == bias + event.poll.poll.address) {
                "Event poll virtual entry is already modified"
            }
            return UiKeyMetadata(
                event,
                focus.address,
                capture,
                modal,
                PROT_READ or if (region.writable) PROT_WRITE else 0
            )
        }
    }
}
