@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.linuxbridge.FmLinuxHookConfig
import kotlinx.cinterop.useContents
import kotlinx.coroutines.delay
import platform.posix.PROT_READ
import platform.posix.PROT_WRITE
import platform.posix._SC_PAGESIZE
import platform.posix.sysconf

/** A primary virtual dispatch identified in the selected ELF and verified on the main thread. */
internal class FrontendSite
private constructor(
    private val process: ProcessHandle,
    val loadBias: Long,
    val logic: Long,
    private val prepareStart: Long,
    private val prepareEnd: Long,
    private val instance: Long,
    private val table: Long,
    private val slot: Int,
    private val protection: Int,
    private val prepareBytes: ByteArray,
) {
    data class Verified(
        val entry: Long,
        val original: Long,
        val guiInstance: Long,
        val caller: Long,
        val callerStart: Long,
        val callerEnd: Long,
        val protection: Int,
    ) {
        fun writeTo(config: FmLinuxHookConfig) {
            config.entry = entry.toULong()
            config.original = original.toULong()
            config.guiInstance = guiInstance.toULong()
            config.caller = caller.toULong()
            config.callerStart = callerStart.toULong()
            config.callerEnd = callerEnd.toULong()
            config.protection = protection.toUInt()
        }
    }

    private fun word(address: Long): Long =
        BinaryView(process.readMemory(address, 8)).unsigned(0, 8)

    /**
     * Leaves the supplied trace stopped immediately after logic, with its owned breakpoint removed.
     */
    suspend fun stopAfterLogic(trace: ThreadTrace): Verified {
        check(word(instance) != 0L) { "Factorio GUI is not initialized" }
        trace.breakAt(logic)
        trace.resume()
        while (true) {
            waitBreakpoint(trace)
            val registers = trace.registers().general
            val stack = registers.useContents { rsp.toLong() }
            val caller = word(stack)
            if (caller !in prepareStart until prepareEnd) {
                trace.resume()
                continue
            }
            val receiver = registers.useContents { rdi.toLong() }
            require(
                receiver != 0L &&
                    word(instance) == receiver &&
                    word(receiver) == table &&
                    registers.useContents { rsi and 255uL } <= 1uL
            ) {
                "Unsupported optimized Gui::logic receiver/argument ABI"
            }
            val tableRegisters =
                registers.useContents {
                    listOf(
                            rax,
                            rcx,
                            rdx,
                            rbx,
                            rsp,
                            rbp,
                            rsi,
                            rdi,
                            r8,
                            r9,
                            r10,
                            r11,
                            r12,
                            r13,
                            r14,
                            r15,
                        )
                        .mapIndexedNotNull { register, value ->
                            register.takeIf { value.toLong() == table }
                        }
                        .toSet()
                }
            val length = minOf(15L, caller - prepareStart).toInt()
            val bytes = process.readMemory(caller - length, length)
            val offset = (caller - length - prepareStart).toInt()
            require(bytes.contentEquals(prepareBytes.copyOfRange(offset, offset + length))) {
                "Live frontend call differs from the selected executable"
            }
            FrontendVirtualCall.verify(BinaryView(bytes), slot, tableRegisters)
            trace.removeBreakpoint()
            trace.breakAt(caller)
            trace.resume()
            waitBreakpoint(trace)
            val returned = trace.registers().general
            require(
                returned.useContents { rip.toLong() == caller && rsp.toLong() == stack + 8 } &&
                    word(instance) == receiver &&
                    word(receiver) == table
            ) {
                "Frontend return did not preserve the verified GUI receiver and caller stack"
            }
            trace.removeBreakpoint()
            return Verified(
                table + slot * 8L,
                logic,
                instance,
                caller,
                prepareStart,
                prepareEnd,
                protection,
            )
        }
    }

    private suspend fun waitBreakpoint(trace: ThreadTrace) {
        while (true) {
            val stop = trace.pollStop()
            check(!trace.hasExited) { "Factorio exited before reaching the frontend safe point" }
            when (stop?.kind) {
                null -> delay(1)
                ThreadTrace.StopKind.BREAKPOINT -> return
                ThreadTrace.StopKind.GROUP -> trace.listen()
                else -> trace.resume()
            }
        }
    }

    companion object {
        fun resolve(process: ProcessHandle): FrontendSite {
            var result: FrontendSite? = null
            process.withExecutable { image ->
                val mappings = process.mappings()
                val maps = process.executableMappings(mappings)
                val pageSize = sysconf(_SC_PAGESIZE)
                val bias = image.loadBias(maps, pageSize)
                val prepare = image.symbol("_ZN8MainLoop7prepareEv")
                EhFrames(image).function(prepare)
                val method =
                    ItaniumVtable.resolve(image, "_ZTVN4agui3GuiE")
                        .method(image, "_ZN4agui3Gui5logicEb")
                val instance = image.symbol("_ZN4agui3Gui8instanceE")
                require(instance.type == 1 && instance.size == 8L && instance.address % 8 == 0L) {
                    "Unexpected GUI instance symbol"
                }
                fun verifyFunction(function: ElfImage.Symbol): ByteArray {
                    require(function.size in 1..(16 * 1024 * 1024)) {
                        "Frontend function exceeds bounds"
                    }
                    val file = image.functionBytes(function, function.size.toInt())
                    val bytes = file.bytes(0, file.size.toInt())
                    require(
                        process.readMemory(bias + function.address, bytes.size).contentEquals(bytes)
                    ) {
                        "Live frontend function differs from the selected executable: ${function.name}"
                    }
                    return bytes
                }
                val prepareBytes = verifyFunction(prepare)
                verifyFunction(method.function)
                val entry = bias + method.entryAddress
                val page = entry and -pageSize
                val region =
                    maps.singleOrNull {
                        it.start <= page &&
                            it.end >= page + pageSize &&
                            it.readable &&
                            !it.executable &&
                            it.permissions[3] == 'p'
                    }
                        ?: error(
                            "GUI virtual entry is not private readable data within one mapped page"
                        )
                require(
                    BinaryView(process.readMemory(entry, 8)).unsigned(0, 8) ==
                        bias + method.function.address
                ) {
                    "GUI virtual entry is already modified"
                }
                require(
                    image.segments.any {
                        it.type == 1L &&
                            it.flags == 6L &&
                            instance.address >= it.address &&
                            instance.address - it.address <= it.memorySize - instance.size
                    }
                ) {
                    "GUI instance is outside an ELF writable data segment"
                }
                // The zero-filled tail of an ELF data segment can be an anonymous mapping without
                // the file's inode.
                require(
                    mappings.any {
                        it.start <= bias + instance.address &&
                            it.end >= bias + instance.address + 8 &&
                            it.readable &&
                            it.writable &&
                            !it.executable &&
                            it.permissions[3] == 'p'
                    }
                ) {
                    "GUI instance is not mapped as private writable data"
                }
                result =
                    FrontendSite(
                        process,
                        bias,
                        bias + method.function.address,
                        bias + prepare.address,
                        bias + prepare.address + prepare.size,
                        bias + instance.address,
                        bias + method.addressPoint,
                        method.slot,
                        PROT_READ or if (region.writable) PROT_WRITE else 0,
                        prepareBytes,
                    )
            }
            return checkNotNull(result)
        }
    }
}
