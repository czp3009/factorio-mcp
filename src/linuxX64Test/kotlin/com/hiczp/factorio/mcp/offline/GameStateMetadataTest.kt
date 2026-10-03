@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp.offline

import com.hiczp.factorio.mcp.ElfImage
import com.hiczp.factorio.mcp.MappedBinary
import com.hiczp.factorio.mcp.OwnedMemberSize
import com.hiczp.factorio.mcp.SysVGlobalAllocation
import com.hiczp.factorio.mcp.LoadingPredicate
import com.hiczp.factorio.mcp.GameStateMetadata
import com.hiczp.factorio.mcp.linuxbridge.FmLinuxGameStateConfig
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/** Explicit installed-file analysis. No game process is accessed. */
class GameStateMetadataTest {
    @Test
    fun resolvesNativeAppManagerOwnership() {
        val path = checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")) { "Select an installed ELF" }.toKString()
        MappedBinary(path).use { file ->
            val start = TimeSource.Monotonic.markNow()
            val image = ElfImage(file.view)
            val global = SysVGlobalAllocation.resolve(image, "_ZN9MainTasks6createERK13ParsedOptions",
                "_ZN13GlobalContextC2ERKN10Filesystem4PathES3_", "global")
            val manager = OwnedMemberSize.resolve(image, "_ZN13GlobalContextD2Ev", "_ZN10AppManagerD2Ev", global.size)
            assertTrue(manager.size >= 8 && manager.pointer <= global.size - 8)
            println("Native AppManager ownership: $manager")
            val predicate = LoadingPredicate.resolve(image, image.symbol("global").address, global.size)
            println("Native loading predicate: ${predicate.states.size} state tables, ${predicate.multiplayer.size} multiplayer bindings")
            println("Native loading predicate resolved in ${start.elapsedNow()}")
            val metadata = GameStateMetadata.resolve(image)
            memScoped {
                val config = alloc<FmLinuxGameStateConfig>()
                metadata.writeTo(config, 0)
                assertTrue(config.global != 0uL && config.stateCount > 0u && config.managerCount > 0u)
            }
            println("Native game state metadata resolved in ${start.elapsedNow()}")
        }
    }
}
