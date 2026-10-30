@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*
import com.hiczp.factorio.mcp.linuxbridge.FmLinuxIconNamesLayout

internal data class WidgetIconNamesLayout(
    val global: Long,
    val registry: Long,
    val lookup: Long,
    val string: NativeStringLayout,
    val evidence: ElfEvidence,
) {
    fun writeTo(target: FmLinuxIconNamesLayout, bias: Long) {
        target.global = (global + bias).toULong()
        target.registry = registry.toUInt()
        target.lookup = (lookup + bias).toULong()
        target.destroy = (string.destructor.address + bias).toULong()
        target.stringSize = string.size.toUInt()
        target.stringData = string.data.toUInt()
        target.stringLength = string.length.toUInt()
    }

    companion object {
        fun resolve(image: ElfImage): WidgetIconNamesLayout {
            val (resolved, readonly) =
                image.withReadonlyEvidence {
                    image.withFunctionEvidence {
                        val string = NativeStringLayout.resolve(image)
                        val entry =
                            image.symbol("_ZNK14UtilitySprites16getNameForSpriteB5cxx11ERK6Sprite")
                        EhFrames(image).function(entry)
                        SpriteNameAbi.verify(
                            image.functionBytes(entry, 65536),
                            6,
                            7,
                            2,
                            SysVObjectSize.resolve(image, "14UtilitySprites"),
                            string.size,
                            setOf(0, 1, 2, 6, 7, 8, 9, 10, 11) + (16..31),
                        )
                        val global = image.symbol("global")
                        require(global.type == 1 && global.size == 8L)
                        val extent =
                            SysVGlobalAllocation.resolve(
                                    image,
                                    "_ZN9MainTasks6createERK13ParsedOptions",
                                    "_ZN13GlobalContextC2ERKN10Filesystem4PathES3_",
                                    "global",
                                )
                                .size
                        val caller = image.symbol("_ZN8AtlasGui20updateVisibleWidgetsEv")
                        val flow = X64ControlFlow.resolve(image, caller)
                        val calls =
                            StringResultStorage.caller(
                                flow,
                                entry.address - caller.address,
                                7,
                                string.size,
                            )
                        val locations =
                            calls.map {
                                GlobalPointerLoad(flow, caller.address, global.address, extent)
                                    .at(it, 6)
                            }
                        WidgetIconNamesLayout(
                            global.address,
                            locations.distinct().single(),
                            entry.address,
                            string,
                            ElfEvidence(emptyList(), emptyList(), emptyMap(), emptyMap()),
                        )
                    }
                }
            return resolved.first.copy(
                evidence = ElfEvidence(resolved.second, readonly, emptyMap(), emptyMap())
            )
        }
    }
}
