package com.hiczp.factorio.mcp

import kotlin.test.*

class FrameHookBindingTest {
    private val swap = GraphicsSwapCall(SdlDynamicApi(0x1000, 0x3000), 0x3010)
    private val mappings =
        ProcMapping.parse("1000-2000 rw-p 00000000 00:00 0\n3000-4000 r-xp 00000000 00:00 0\n")

    private fun read(address: Long, size: Int): ByteArray {
        assertEquals(0x1000, address)
        assertEquals(8, size)
        return ByteArray(8) { (0x3000L ushr (it * 8)).toByte() }
    }

    @Test
    fun bindsOnlyInitializedNamedApi() {
        assertEquals(
            FrameHookBinding(0x1000, 0x3000, 3),
            FrameHookBinding.bind(swap, 0, 4096, mappings, ::read),
        )
        assertFails {
            FrameHookBinding.bind(
                swap.copy(api = swap.api.copy(implementation = 0x3010)),
                0,
                4096,
                mappings,
                ::read,
            )
        }
    }

    @Test
    fun rejectsChangedEntryAndUnsafeMappings() {
        var reads = 0
        assertFails {
            FrameHookBinding.bind(swap, 0, 4096, mappings) { address, size ->
                if (++reads == 2) ByteArray(8) else read(address, size)
            }
        }
        for (permissions in listOf("rw-s", "rwxp", "---p")) assertFails {
            FrameHookBinding.bind(
                swap,
                0,
                4096,
                mappings.map {
                    if (it.start == 0x1000L) it.copy(permissions = permissions) else it
                },
                ::read,
            )
        }
    }
}
