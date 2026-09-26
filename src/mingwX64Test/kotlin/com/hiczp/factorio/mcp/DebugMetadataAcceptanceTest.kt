@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.nativebridge.TimedInputLayout
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.getenv
import platform.windows.CloseHandle
import platform.windows.OpenProcess
import platform.windows.PROCESS_QUERY_INFORMATION
import platform.windows.PROCESS_VM_READ
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/** Read-only acceptance for optional adapters against an explicitly selected local client. */
class DebugMetadataAcceptanceTest {
    @Test
    fun virtualInterfaceMetadataResolvesFromAnExplicitPdb() {
        val path =
            getenv("FACTORIO_MCP_TEST_PDB")?.toKString()?.takeIf { it.isNotBlank() } ?: return
        require(path.endsWith(".pdb", ignoreCase = true))
        val started = TimeSource.Monotonic.markNow()
        val (guid, age) = PeImage(path.dropLast(4) + ".exe").debugIdentity()
        val imageElapsed = started.elapsedNow()
        val signatures =
            mapOf(
                "getCount" to 0x41u,
                "shouldDrawNumber" to 0x30u,
                "shouldShowZero" to 0x30u,
                "isUnknown" to 0x30u,
                "isInfinite" to 0x30u,
                "getAbilityCount" to 0x74u,
            )
        val methods = readPdbVirtualMethods(path, guid, age, "ButtonNumber", signatures)
        println(
            "factorio-mcp: metadata acceptance image=$imageElapsed pdb=${started.elapsedNow() - imageElapsed}"
        )
        assertEquals(signatures.keys, methods.keys)
        assertEquals(methods.size, methods.values.map { it.offset }.distinct().size)
        assertTrue(methods.values.all { it.offset % 8u == 0u })
        val providers =
            readPdbVirtualMethods(
                path,
                guid,
                age,
                "PrototypeProvider",
                emptyMap(),
                mapOf(
                    "getBasePrototype" to "PrototypeBase",
                    "getQualityPrototype" to "QualityPrototype",
                ),
            )
        assertEquals(2, providers.size)
        assertEquals(2, providers.values.map { it.offset }.distinct().size)
        val conditions =
            readPdbVirtualMethods(
                path,
                guid,
                age,
                "IDButtonProvider<IDWithQualityFilter<ID<ItemPrototype,unsigned short> > >",
                emptyMap(),
                aggregateSignatures =
                    mapOf("getID" to "IDWithQualityFilter<ID<ItemPrototype,unsigned short> >"),
                primaryBase = "PrototypeProvider",
            )
        assertEquals(setOf("getID"), conditions.keys)
        assertEquals(0u, conditions.getValue("getID").offset % 8u)
    }

    @Test
    fun inputMetadataResolvesFromTheLoadedTarget() {
        val pid =
            getenv("FACTORIO_MCP_TEST_PID")?.toKString()?.takeIf { it.isNotBlank() }?.toUInt()
                ?: return
        val process =
            checkNotNull(
                OpenProcess((PROCESS_QUERY_INFORMATION or PROCESS_VM_READ).toUInt(), 0, pid)
            )
        try {
            val symbols = resolveSymbols(process, checkNotNull(processModule(pid)))
            symbols.viewport.getOrThrow()
            symbols.properties.getOrThrow().options.getOrThrow()
            symbols.slots.getOrThrow()
            symbols.numbers.getOrThrow()
            symbols.visibility.getOrThrow()
            symbols.progress.getOrThrow()
            symbols.elements.getOrThrow()
            symbols.sprites.getOrThrow()
            symbols.conditions.getOrThrow()
            symbols.switches.getOrThrow()
            memScoped {
                val layout = alloc<TimedInputLayout>()
                symbols.timedInput.getOrThrow().write(layout)
                assertEquals(1u, layout.supported)
                assertTrue(layout.events.eventSize in 1u..256u)
                assertTrue(layout.events.scancode + 4u <= layout.events.eventSize)
                assertTrue(layout.events.mouseButton + 4u <= layout.events.eventSize)
                assertTrue(layout.events.stateMouseX + 4u <= layout.events.stateSize)
                assertNotEquals(layout.events.keyDown, layout.events.keyUp)
                assertNotEquals(layout.events.mouseDown, layout.events.mouseUp)
            }
        } finally {
            CloseHandle(process)
        }
    }
}
