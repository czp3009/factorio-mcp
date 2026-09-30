@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.nativebridge.*
import kotlinx.cinterop.*
import platform.posix.memset
import platform.windows.*

private val symbolPrefixes =
    listOf(
        "?updateGui@GlobalContext@@",
        "?prepare@MainLoop@@",
        "?instance@Gui@agui@@",
        "?global@@",
        "?getGui@Widget@agui@@",
        "?callRecursively@Widget@agui@@",
        "?getGameSafe@GlobalContext@@",
        "?isLoadingMap@GlobalContext@@",
        "__RTtypeid",
        "?name@type_info@@",
        "?getText@Widget@agui@@",
        "?getText@Label@agui@@",
        "?getText@TextBox@agui@@",
        "?c_str@?\$basic_string@D",
        "?size@?\$basic_string@D",
        "?getAbsoluteRectangle@Widget@agui@@",
        "?isEnabled@Widget@agui@@",
        "?isFlaggedForDestruction@Widget@agui@@",
        "?mainLoopStep@MainLoop@@",
        "?dxgiSwapChainPresent@@",
        "?swapBuffers@GraphicsInterfaceOpenGL@@",
        "?dispatchMouseEnter@Widget@agui@@",
        "?dispatchMouseDown@Widget@agui@@",
        "?dispatchMouseUp@Widget@agui@@",
        "?dispatchMouseLeave@Widget@agui@@",
        "?widgetIsModalChild@Gui@agui@@",
        "__RTDynamicCast",
        "??_R0?AVWidget@agui@@@8",
        "??_R0?AVTextBox@agui@@@8",
        "?focus@Widget@agui@@",
        "?keyDown@TextBox@agui@@",
        "?dispatchClick@Widget@agui@@",
        "?convertMouseEventToRelative@Gui@agui@@",
        "?forceReleaseControlWithLock@Gui@agui@@",
        "?getControlInputList@ControlInput@@",
        "?loadingCustomInputs@ControlInput@@",
        "??1?\$basic_string@DU?\$char_traits@D@std@@V?\$allocator@D@2@@std@@",
        "?LEFT@MouseButton@ControlInputValue@@",
        "?RIGHT@MouseButton@ControlInputValue@@",
        "?MIDDLE@MouseButton@ControlInputValue@@",
        "?BUTTON_4@MouseButton@ControlInputValue@@",
        "?BUTTON_5@MouseButton@ControlInputValue@@",
        "?modifierStringForm@ControlInputValue@@",
        "?nextEvent@GlobalContext@@",
        "?processEvents@MainLoop@@",
        "??0Event@@QEAA@NW4Type@0@@Z",
        "SDL_GetTicks",
        "??_R0?AVToggleButton@agui@@@8",
        "??_R0?AVButton@agui@@@8",
        "??_R0?AVDropDown@agui@@@8",
        "??_R0?AVSlider@agui@@@8",
        "?getLocalPlayer@Game@@QEAAPEAV",
        "?getLuaContext@Scenario@@",
        "?getDefaultScript@LuaContext@@",
        "??BLuaState@@",
        "lua_gettop",
        "lua_settop",
        "lua_checkstack",
        "luaL_loadbufferx",
        "lua_pcallk",
        "lua_tolstring",
        "lua_pushnumber",
        "lua_pushlstring",
        "??_GEvent@@QEAAPEAXI@Z",
        "?update@InputState@@",
        "?postUpdate@InputState@@",
        "?processEvent@MainLoop@@",
        "?sendStateChanges@PlayerInputSource@@",
        "??1Game@@QEAA@XZ",
        "?getGameView@Player@@QEBAPEAVGameView@@XZ",
        "?getDisplaySize@GameView@@QEBA?AVPixelSize@@XZ",
        "?getMapPosition@GameView@@QEBA?AVMapPosition@@VPixelPosition@@@Z",
        "?str_raw@LocalisedString@@",
        "??0?\$basic_string@DU?\$char_traits@D@std@@V?\$allocator@D@2@@std@@QEAA@QEBD@Z",
        "?send@GuiContext@@QEBAX\$\$QEAVInputAction@@@Z",
        "?destroyValue@InputAction@@",
        "?noData@InputAction@@",
        "?isCorrectDataTypeForAction@InputAction@@",
        "??_R0?AV?\$basic_string@DU?\$char_traits@D@std@@V?\$allocator@D@2@@std@@@8",
    )

internal class ResolvedSymbols(
    val addresses: List<ULong>,
    val prepareEnd: ULong,
    val mainEnd: ULong,
    val rootOffset: UInt,
    val pauseOffsets: List<UInt>?,
    val events: EventLayouts,
    val parentOffset: UInt,
    val controls: Result<ControlLayouts>,
    val input: Result<FrontendInputLayouts>,
    val processEventsEnd: ULong,
    val properties: Result<WidgetPropertyLayouts>,
    val world: Result<WorldLayouts>,
    val timedInput: Result<TimedInputLayouts>,
    val viewport: Result<ViewportLayouts>,
    val slots: Result<SlotIdentityLayouts>,
    val numbers: Result<NumberLayouts>,
    val visibility: Result<VisibilityLayouts>,
    val progress: Result<ProgressLayouts>,
    val elements: Result<ElementLayouts>,
    val sprites: Result<SpriteLayouts>,
    val conditions: Result<QualityConditionLayouts>,
    val switches: Result<SwitchLayouts>,
    val chat: Result<ChatLayouts>,
    val inputTransfer: Result<InputTransferLayouts>,
) {
    fun write(target: Symbols) {
        memset(target.ptr, 0, sizeOf<Symbols>().toULong())
        addresses.forEachIndexed { index, address -> target.address[index] = address }
        target.prepareEnd = prepareEnd
        target.mainEnd = mainEnd
        target.guiRootOffset = rootOffset
        target.widgetParentOffset = parentOffset
        target.pauseSupported = if (pauseOffsets == null) 0u else 1u
        events.write(target.events)
        controls.getOrNull()?.write(target.controls)
        input.getOrNull()?.write(target.input)
        properties.getOrNull()?.write(target.properties)
        world.getOrNull()?.write(target.world)
        viewport.getOrNull()?.write(target.viewport)
        slots.getOrNull()?.write(target.slots)
        numbers.getOrNull()?.write(target.numbers)
        visibility.getOrNull()?.write(target.visibility)
        progress.getOrNull()?.write(target.progress)
        elements.getOrNull()?.write(target.elements)
        sprites.getOrNull()?.write(target.sprites)
        conditions.getOrNull()?.write(target.conditions)
        switches.getOrNull()?.write(target.switches)
        chat.getOrNull()?.write(target.chat)
        inputTransfer.getOrNull()?.write(target.inputTransfer)
        if (world.isSuccess && controls.isSuccess && pauseOffsets != null)
            timedInput.getOrNull()?.write(target.timedInput)
        target.processEventsEnd = processEventsEnd
        pauseOffsets?.let {
            target.gameMapOffset = it[0]
            target.mapPausedOffset = it[1]
            target.mapStopLevelOffset = it[2]
        }
    }
}

private class SymbolCollector {
    val addresses = MutableList(symbolPrefixes.size) { 0uL }
    val counts = IntArray(symbolPrefixes.size)
    val providerTypes = mutableListOf<ULong>()
    val numberTypes = mutableListOf<ULong>()
    val progressTypes = mutableListOf<ULong>()
    val spriteTypes = mutableListOf<ULong>()
    val conditionTypes = mutableListOf<ULong>()
    val qualityRegistries = mutableListOf<ULong>()
    val switchTypes = mutableListOf<ULong>()
    val elementTypes = mutableMapOf<String, MutableList<ULong>>()
}

internal fun resolveSymbols(process: HANDLE, module: ProcessModule): ResolvedSymbols = memScoped {
    val image = PeImage(module.path)
    image.verifyLoadedHeaders { rva, size -> readModule(process, module, rva, size) }
    SymSetOptions(
        (SYMOPT_DEFERRED_LOADS or
                SYMOPT_EXACT_SYMBOLS or
                SYMOPT_FAIL_CRITICAL_ERRORS or
                SYMOPT_PUBLICS_ONLY)
            .toUInt()
    )
    check(
        SymInitializeW(process, module.path.substringBeforeLast('\\').wcstr.getPointer(this), 0) !=
                0
    ) {
        "Cannot initialize Windows symbol reader"
    }
    try {
        check(
            SymLoadModuleExW(
                process,
                null,
                module.path.wcstr.getPointer(this),
                null,
                module.base,
                module.size,
                null,
                0u,
            ) != 0uL
        ) {
            "Cannot load target symbols"
        }
        val collector = SymbolCollector()
        val reference = StableRef.create(collector)
        try {
            check(
                SymEnumSymbols(
                    process,
                    module.base,
                    null,
                    staticCFunction { info: CPointer<SYMBOL_INFO>?,
                                      _: UInt,
                                      context: COpaquePointer? ->
                        val result = context!!.asStableRef<SymbolCollector>().get()
                        val name = info!!.pointed.Name.toKString()
                        if (name == "??_R0?AVPrototypeProvider@@@8")
                            result.providerTypes.add(info.pointed.Address)
                        if (name == "??_R0?AVButtonNumber@@@8")
                            result.numberTypes.add(info.pointed.Address)
                        if (name == "??_R0?AVProgressBar@agui@@@8")
                            result.progressTypes.add(info.pointed.Address)
                        if (name == "??_R0?AVSwitch@agui@@@8")
                            result.switchTypes.add(info.pointed.Address)
                        if (name == "??_R0?AVIconButton@@@8")
                            result.spriteTypes.add(info.pointed.Address)
                        if (
                            name ==
                            "?indexToPrototype@?\$PrototypeList@VQualityPrototype@@@@2V?\$vector@PEAVQualityPrototype@@V?\$allocator@PEAVQualityPrototype@@@std@@@std@@A"
                        )
                            result.qualityRegistries.add(info.pointed.Address)
                        if (
                            name ==
                            "??_R0?AV?\$IDButtonProvider@V?\$IDWithQualityFilter@V?\$ID@VItemPrototype@@G@@@@@@@8"
                        )
                            result.conditionTypes.add(info.pointed.Address)
                        if (
                            name.startsWith("??_R0?AV?\$ElemProvider@") ||
                            name == "??_R0?AVItem@@@8" ||
                            name == "??_R0?AVTool@@@8" ||
                            name == "??_R0?AVAmmoItem@@@8"
                        )
                            result.elementTypes
                                .getOrPut(name) { mutableListOf() }
                                .add(info.pointed.Address)
                        symbolPrefixes.forEachIndexed { index, prefix ->
                            if (
                                if (prefix == "SDL_GetTicks") name == prefix
                                else name.startsWith(prefix)
                            ) {
                                result.addresses[index] = info.pointed.Address
                                result.counts[index]++
                            }
                        }
                        1
                    },
                    reference.asCPointer(),
                ) != 0
            ) {
                "Cannot enumerate target PDB"
            }
        } finally {
            reference.dispose()
        }
        collector.counts.take(FmSymbol.ControlList.value.toInt()).forEachIndexed { index, count ->
            check(count == 1) {
                "Expected one PDB match for ${symbolPrefixes[index]}; found $count"
            }
        }
        val info = alloc<IMAGEHLP_MODULE64>()
        memset(info.ptr, 0, sizeOf<IMAGEHLP_MODULE64>().toULong())
        info.SizeOfStruct = sizeOf<IMAGEHLP_MODULE64>().toUInt()
        check(
            SymGetModuleInfo64(process, module.base, info.ptr) != 0 &&
                    info.SymType == SymPdb &&
                    info.PdbUnmatched == 0
        ) {
            "Matching developer PDB is required"
        }
        val (guid, age) = image.debugIdentity()
        check(
            info.PdbSig70.ptr.reinterpret<ByteVar>().readBytes(16).contentEquals(guid) &&
                    info.PdbAge.toInt() == age
        ) {
            "Executable/PDB identity mismatch"
        }

        fun end(index: Int) =
            module.base +
                    image.functionEnd((collector.addresses[index] - module.base).toLong()).toULong()

        val types = DebugTypes(process, module.base)
        val root = types.pointerMember("agui::Gui", "baseWidget", "agui::TopContainer")
        val pause =
            runCatching {
                listOf(
                    types.pointerMember("Game", "map", "Map"),
                    types.byteMember("Map", "paused", true),
                    types.byteMember("Map", "stopLevel", false),
                )
            }
                .getOrNull()
        val input = runCatching {
            for (index in
            FmSymbol.NextEvent.value.toInt() until FmSymbol.ToggleButtonType.value.toInt()) {
                check(collector.counts[index] == 1) {
                    "Frontend keyboard unavailable: missing or ambiguous ${symbolPrefixes[index]}"
                }
            }
            FrontendInputLayouts(types) to end(FmSymbol.ProcessEvents.value.toInt())
        }
        ResolvedSymbols(
            collector.addresses,
            end(FmSymbol.Prepare.value.toInt()),
            end(FmSymbol.MainStep.value.toInt()),
            root,
            pause,
            EventLayouts(types),
            types.pointerMember("agui::Widget", "parentWidget", "agui::Widget"),
            runCatching {
                for (index in
                FmSymbol.ControlList.value.toInt() until FmSymbol.NextEvent.value.toInt()) {
                    check(collector.counts[index] == 1) {
                        "input_bindings unavailable: missing or ambiguous ${symbolPrefixes[index]}"
                    }
                }
                ControlLayouts(types)
            },
            input.map { it.first },
            input.getOrNull()?.second ?: 0uL,
            runCatching {
                for (index in
                FmSymbol.ToggleButtonType.value.toInt() until
                        FmSymbol.LocalPlayer.value.toInt()) {
                    check(collector.counts[index] == 1) {
                        "Widget properties unavailable: missing or ambiguous ${symbolPrefixes[index]}"
                    }
                }
                WidgetPropertyLayouts(types)
            },
            runCatching {
                for (index in
                FmSymbol.LocalPlayer.value.toInt() until
                        FmSymbol.EventDestructor.value.toInt()) {
                    check(collector.counts[index] == 1) {
                        "World query unavailable: missing or ambiguous ${symbolPrefixes[index]}"
                    }
                }
                WorldLayouts(types)
            },
            runCatching {
                for (index in
                FmSymbol.EventDestructor.value.toInt() until
                        FmSymbol.PlayerGameView.value.toInt()) {
                    check(collector.counts[index] == 1) {
                        "Timed input unavailable: missing or ambiguous ${symbolPrefixes[index]}"
                    }
                }
                input.getOrThrow()
                TimedInputLayouts(types)
            },
            runCatching {
                for (index in
                FmSymbol.PlayerGameView.value.toInt() until
                        FmSymbol.LocalisedRaw.value.toInt()) {
                    check(collector.counts[index] == 1) {
                        "Viewport unavailable: missing or ambiguous ${symbolPrefixes[index]}"
                    }
                }
                ViewportLayouts(types)
            },
            runCatching {
                SlotIdentityLayouts(
                    types,
                    info.LoadedPdbName.toKString(),
                    guid,
                    age,
                    collector.providerTypes,
                )
            },
            runCatching {
                NumberLayouts(info.LoadedPdbName.toKString(), guid, age, collector.numberTypes)
            },
            runCatching { VisibilityLayouts(types) },
            runCatching { ProgressLayouts(types, collector.progressTypes) },
            runCatching {
                ElementLayouts(
                    types,
                    info.LoadedPdbName.toKString(),
                    guid,
                    age,
                    collector.elementTypes,
                )
            },
            runCatching { SpriteLayouts(types, collector.spriteTypes) },
            runCatching {
                QualityConditionLayouts(
                    types,
                    info.LoadedPdbName.toKString(),
                    guid,
                    age,
                    collector.conditionTypes,
                    collector.qualityRegistries,
                )
            },
            runCatching { SwitchLayouts(types, collector.switchTypes) },
            runCatching {
                for (index in FmSymbol.LocalisedRaw.value.toInt() until symbolPrefixes.size) {
                    check(collector.counts[index] == 1) {
                        "Chat unavailable: missing or ambiguous ${symbolPrefixes[index]}"
                    }
                }
                ChatLayouts(types)
            },
            runCatching { InputTransferLayouts(types) },
        )
    } finally {
        SymCleanup(process)
    }
}

/**
 * A sampled main-loop stack proves initial loading has ended without reading undocumented fields.
 */
internal fun frontendRunning(process: HANDLE, pid: UInt, symbols: ResolvedSymbols): Boolean =
    memScoped {
        check(SymInitialize(process, null, 1) != 0) { "Cannot inspect target readiness" }
        try {
            val snapshot = CreateToolhelp32Snapshot(TH32CS_SNAPTHREAD.toUInt(), 0u)
            check(snapshot != INVALID_HANDLE_VALUE) { "Cannot inspect target threads" }
            try {
                val entry = alloc<THREADENTRY32>()
                entry.dwSize = sizeOf<THREADENTRY32>().toUInt()
                var available = Thread32First(snapshot, entry.ptr) != 0
                while (available) {
                    if (entry.th32OwnerProcessID == pid) {
                        val thread =
                            OpenThread(
                                (THREAD_SUSPEND_RESUME or
                                        THREAD_GET_CONTEXT or
                                        THREAD_QUERY_INFORMATION)
                                    .toUInt(),
                                0,
                                entry.th32ThreadID,
                            )
                        if (thread != null)
                            try {
                                if (SuspendThread(thread) != UInt.MAX_VALUE)
                                    try {
                                        val context = alloc<CONTEXT>()
                                        context.ContextFlags = CONTEXT_FULL.toUInt()
                                        if (GetThreadContext(thread, context.ptr) != 0) {
                                            val frame = alloc<STACKFRAME64>()
                                            memset(frame.ptr, 0, sizeOf<STACKFRAME64>().toULong())
                                            frame.AddrPC.Offset = context.Rip
                                            frame.AddrPC.Mode = ADDRESS_MODE.AddrModeFlat
                                            frame.AddrStack.Offset = context.Rsp
                                            frame.AddrStack.Mode = ADDRESS_MODE.AddrModeFlat
                                            frame.AddrFrame.Offset = context.Rbp
                                            frame.AddrFrame.Mode = ADDRESS_MODE.AddrModeFlat
                                            for (index in 0 until 128) {
                                                val pc = frame.AddrPC.Offset
                                                if (
                                                    pc >=
                                                    symbols.addresses[
                                                        FmSymbol.MainStep.value.toInt()] &&
                                                    pc < symbols.mainEnd
                                                )
                                                    return true
                                                if (
                                                    StackWalk64(
                                                        IMAGE_FILE_MACHINE_AMD64.toUInt(),
                                                        process,
                                                        thread,
                                                        frame.ptr,
                                                        context.ptr,
                                                        null,
                                                        staticCFunction { p: HANDLE?, address: ULong
                                                            ->
                                                            SymFunctionTableAccess64(p, address)
                                                        },
                                                        staticCFunction { p: HANDLE?, address: ULong
                                                            ->
                                                            SymGetModuleBase64(p, address)
                                                        },
                                                        null,
                                                    ) == 0
                                                )
                                                    break
                                            }
                                        }
                                    } finally {
                                        ResumeThread(thread)
                                    }
                            } finally {
                                CloseHandle(thread)
                            }
                    }
                    available = Thread32Next(snapshot, entry.ptr) != 0
                }
                false
            } finally {
                CloseHandle(snapshot)
            }
        } finally {
            SymCleanup(process)
        }
    }
