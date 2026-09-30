package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FrameHookBindingTest {
    private val swap = GraphicsSwapCall(0x1000, 32, 0x3010)
    private val backends = listOf(SdlDeviceAllocation(128, 32, 0x1010, 0x3000))
    private val mappings = ProcMapping.parse(
        "1000-2000 rw-p 00000000 00:00 0\n" +
                "3000-4000 r-xp 00000000 00:00 0\n5000-6000 rw-p 00000000 00:00 0\n"
    )

    private fun read(address: Long, size: Int): ByteArray {
        assertEquals(8, size)
        val value = when (address) {
            0x1000L -> 0x5000L
            0x5020L -> 0x3000L
            else -> error("Unexpected read")
        }
        return ByteArray(8) { (value ushr (it * 8)).toByte() }
    }

    @Test
    fun bindsOnlyProvenCurrentBackend() {
        val result = FrameHookBinding.bind(swap, backends, 0, 4096, mappings, ::read)
        assertEquals(FrameHookBinding(0x1000, 0x5000, 0x3000, 128, 32, 3), result)
        assertFailsWith<IllegalStateException> {
            FrameHookBinding.bind(swap, backends.map { it.copy(backend = 0x3010) }, 0, 4096, mappings, ::read)
        }
        assertFailsWith<IllegalStateException> {
            FrameHookBinding.bind(swap, backends + backends, 0, 4096, mappings, ::read)
        }
    }

    @Test
    fun rejectsChangedDeviceAndUnsafeMappings() {
        var globalReads = 0
        assertFailsWith<IllegalArgumentException> {
            FrameHookBinding.bind(swap, backends, 0, 4096, mappings) { address, size ->
                if (address == swap.device && ++globalReads == 2) ByteArray(8) else read(address, size)
            }
        }
        for (permissions in listOf("rw-s", "rwxp", "---p")) {
            assertFailsWith<IllegalArgumentException> {
                FrameHookBinding.bind(
                    swap, backends, 0, 4096,
                    mappings.map { if (it.start == 0x5000L) it.copy(permissions = permissions) else it }, ::read
                )
            }
        }
        assertFailsWith<IllegalArgumentException> {
            FrameHookBinding.bind(swap, backends.map { it.copy(size = 16) }, 0, 4096, mappings, ::read)
        }
    }
}
