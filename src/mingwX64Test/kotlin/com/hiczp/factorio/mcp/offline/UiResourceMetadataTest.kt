@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp.offline

import com.hiczp.factorio.mcp.*
import com.hiczp.factorio.mcp.nativebridge.*
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.cinterop.*
import platform.posix.getenv
import platform.windows.GetCurrentProcess

class UiResourceMetadataTest {
    @Test
    fun resolvesStoredNamesAndTooltips() = memScoped {
        val path = checkNotNull(getenv("FACTORIO_MCP_TEST_EXE")).toKString()
        val image = PeImage(path)
        val process = checkNotNull(GetCurrentProcess())
        SymSetOptions(
            (SYMOPT_EXACT_SYMBOLS or SYMOPT_FAIL_CRITICAL_ERRORS or SYMOPT_PUBLICS_ONLY).toUInt()
        )
        check(SymInitializeW(process, null, 0) != 0)
        try {
            val base =
                SymLoadModuleExW(process, null, path.wcstr.ptr, null, 0x180000000uL, 0u, null, 0u)
            check(base != 0uL)
            val info = alloc<IMAGEHLP_MODULE64>()
            info.SizeOfStruct = sizeOf<IMAGEHLP_MODULE64>().toUInt()
            check(SymGetModuleInfo64(process, base, info.ptr) != 0 && info.PdbUnmatched == 0)
            val (guid, age) = image.debugIdentity()
            check(
                info.PdbSig70.ptr.reinterpret<ByteVar>().readBytes(16).contentEquals(guid) &&
                    info.PdbAge.toInt() == age
            )
            val names = mutableMapOf<String, MutableList<ULong>>()
            val reference = StableRef.create(names)
            try {
                check(
                    SymEnumSymbols(
                        process,
                        base,
                        null,
                        staticCFunction {
                            info: CPointer<SYMBOL_INFO>?,
                            _: UInt,
                            context: COpaquePointer? ->
                            val name = info!!.pointed.Name.toKString()
                            if (
                                name.startsWith("?getNameForSprite@UtilitySprites@@") ||
                                    name == "??_R0?AVToolTipCreatorBase@agui@@@8" ||
                                    name == "??_R0?AVPlainToolTipCreator@agui@@@8"
                            ) {
                                context!!
                                    .asStableRef<MutableMap<String, MutableList<ULong>>>()
                                    .get()
                                    .getOrPut(name) { mutableListOf() }
                                    .add(info.pointed.Address)
                            }
                            1
                        },
                        reference.asCPointer(),
                    ) != 0
                )
            } finally {
                reference.dispose()
            }
            val types = DebugTypes(process, base)
            val icons = alloc<FmIconNamesLayout>()
            IconNamesLayout(
                    types,
                    names.filterKeys { it.startsWith("?getNameForSprite") }.values.flatten(),
                    image,
                    ProcessModule(path, base, info.ImageSize),
                    image::readonlyBytes,
                )
                .write(icons)
            val tips = alloc<FmTooltipLayout>()
            TooltipLayout(
                    types,
                    names.getValue("??_R0?AVToolTipCreatorBase@agui@@@8"),
                    names.getValue("??_R0?AVPlainToolTipCreator@agui@@@8"),
                )
                .write(tips)
            assertTrue(tips.title != tips.text && icons.stringSize > 0u)
            println(
                "Sprite-name registry member=${icons.registry}, stored tooltip creator=${tips.creator}, title=${tips.title}, text=${tips.text}"
            )
        } finally {
            SymCleanup(process)
        }
    }
}
