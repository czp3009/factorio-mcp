@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.nativebridge.*
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import platform.windows.*

internal data class ProcessModule(val path: String, val base: ULong, val size: UInt)

private val emptyWidgetProperties = WidgetProperties()

internal fun readModule(process: HANDLE, module: ProcessModule, rva: Int, size: Int): ByteArray =
    memScoped {
        check(rva >= 0 && size > 0 && rva.toLong() + size <= module.size.toLong()) {
            "Requested range exceeds the loaded image"
        }
        val bytes = ByteArray(size)
        val count = alloc<ULongVar>()
        check(
            bytes.usePinned {
                ReadProcessMemory(
                    process,
                    (module.base + rva.toULong()).toLong().toCPointer<ByteVar>(),
                    it.addressOf(0),
                    size.toULong(),
                    count.ptr,
                )
            } != 0 && count.value == size.toULong()
        ) {
            "Cannot read loaded image"
        }
        bytes
    }

internal fun processModule(pid: UInt, name: String? = null): ProcessModule? = memScoped {
    val snapshot =
        CreateToolhelp32Snapshot((TH32CS_SNAPMODULE or TH32CS_SNAPMODULE32).toUInt(), pid)
    check(snapshot != INVALID_HANDLE_VALUE) { "Cannot enumerate process modules" }
    try {
        val entry = alloc<MODULEENTRY32W>()
        entry.dwSize = sizeOf<MODULEENTRY32W>().toUInt()
        var available = Module32FirstW(snapshot, entry.ptr) != 0
        while (available) {
            if (name == null || name.equals(entry.szModule.toKString(), true))
                return ProcessModule(
                    entry.szExePath.toKString(),
                    entry.modBaseAddr.toLong().toULong(),
                    entry.modBaseSize,
                )
            available = Module32NextW(snapshot, entry.ptr) != 0
        }
        val error = GetLastError()
        check(error == ERROR_NO_MORE_FILES.toUInt()) { "Cannot enumerate process modules: $error" }
        null
    } finally {
        CloseHandle(snapshot)
    }
}

internal fun discoverProcesses(name: String): List<Int> = memScoped {
    require(name.isNotBlank() && '\u0000' !in name && '/' !in name && '\\' !in name) {
        "Expected an executable name"
    }
    val snapshot = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS.toUInt(), 0u)
    check(snapshot != INVALID_HANDLE_VALUE) { "Cannot enumerate processes" }
    try {
        val entry = alloc<PROCESSENTRY32W>()
        entry.dwSize = sizeOf<PROCESSENTRY32W>().toUInt()
        buildList {
            var available = Process32FirstW(snapshot, entry.ptr) != 0
            while (available) {
                if (name.equals(entry.szExeFile.toKString(), true)) add(entry.th32ProcessID.toInt())
                available = Process32NextW(snapshot, entry.ptr) != 0
            }
            val error = GetLastError()
            check(error == ERROR_NO_MORE_FILES.toUInt()) { "Cannot enumerate processes: $error" }
        }
    } finally {
        CloseHandle(snapshot)
    }
}

/**
 * Windows resources are used on one dedicated dispatcher; no request owns a remote bootstrap
 * buffer.
 */
internal class ResidentConnection(private val pid: UInt) {
    private val resources =
        ConnectionResources(
            OpenProcess(
                (PROCESS_QUERY_INFORMATION or
                        PROCESS_VM_READ or
                        PROCESS_VM_WRITE or
                        PROCESS_VM_OPERATION or
                        PROCESS_CREATE_THREAD or
                        SYNCHRONIZE)
                    .toUInt(),
                0,
                pid,
            ) ?: error("Cannot open target process")
        )
    private val process: HANDLE
        get() = checkNotNull(resources.process) { "Target connection is closed" }

    private val mappingName = "Local\\factorio-mcp-$pid"
    private var exitWait: ProcessExitWait? = null
    private var bootstrap: RemoteBootstrap? = null
    private val inputTasks = InputTaskOwner()
    private val residentPath: String
    private val residentImage: PeImage
    private val symbols: ResolvedSymbols
    private var runtimeApi: RuntimeApi? = null
    private var executablePath: String = ""

    init {
        try {
            val image = processModule(pid) ?: error("Target image is absent")
            executablePath = image.path
            check(image.path.substringAfterLast('\\').equals("factorio.exe", true)) {
                "Target is not Factorio"
            }
            symbols = resolveSymbols(process, image)
            check(frontendRunning(process, pid, symbols)) {
                "Factorio frontend is not initialized; retry after initial loading"
            }
            residentPath = memScoped {
                val path = allocArray<UShortVar>(32768)
                check(GetModuleFileNameW(null, path, 32768u) > 0u) {
                    "Cannot locate MCP executable"
                }
                path.toKString().substringBeforeLast('\\') + "\\factorio_bridge.dll"
            }
            residentImage = PeImage(residentPath)
            processModule(pid, "factorio_bridge.dll")?.let {
                verifyResident(it)
                check(mapResident() != null) {
                    "Existing resident initialization is incomplete; restart Factorio"
                }
            }
            resources.gate =
                CreateMutexW(null, 0, "$mappingName-client") ?: error("Cannot create process lock")
            mapResident()
            exitWait =
                try {
                    ProcessExitWait(process)
                } catch (failure: Exception) {
                    Platform.writeError(
                        "factorio-mcp: ${failure.message}; exit checks remain active on tool calls"
                    )
                    null
                }
        } catch (failure: Throwable) {
            try {
                closeResources()
            } catch (cleanup: Throwable) {
                failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    fun alive() = resources.process?.let(::processIsAlive) ?: false

    suspend fun awaitExit() {
        val observer = exitWait ?: awaitCancellation()
        observer.await()
    }

    private fun mapResident(): CPointer<Shared>? {
        resources.shared?.let {
            return it
        }
        val file = OpenFileMappingW(FILE_MAP_ALL_ACCESS.toUInt(), 0, mappingName)
        if (file == null) {
            val error = GetLastError()
            check(error == ERROR_FILE_NOT_FOUND.toUInt()) { "Cannot open resident IPC: $error" }
            return null
        }
        var state: CPointer<Shared>? = null
        try {
            state =
                MapViewOfFile(
                        file,
                        FILE_MAP_ALL_ACCESS.toUInt(),
                        0u,
                        0u,
                        sizeOf<Shared>().toULong(),
                    )
                    ?.reinterpret<Shared>() ?: error("Cannot map resident IPC: ${GetLastError()}")
            resources.mapping = file
            resources.shared = state
            return state
        } finally {
            if (resources.shared == null) {
                state?.let { UnmapViewOfFile(it) }
                CloseHandle(file)
            }
        }
    }

    private suspend fun finishBootstrap(): UInt? =
        withContext(NonCancellable) {
            val call = bootstrap ?: return@withContext null
            val result = call.await()
            call.close()
            bootstrap = null
            result
        }

    private suspend fun remoteCall(
        address: ULong,
        data: CValuesRef<*>? = null,
        length: Long = 0,
    ): UInt {
        check(bootstrap == null) { "Previous resident bootstrap has not been reconciled" }
        val argument =
            if (data == null) null
            else
                VirtualAllocEx(
                    process,
                    null,
                    length.toULong(),
                    (MEM_RESERVE or MEM_COMMIT).toUInt(),
                    PAGE_READWRITE.toUInt(),
                ) ?: error("Cannot allocate bootstrap argument")
        var admitted = false
        try {
            if (data != null)
                memScoped {
                    val written = alloc<ULongVar>()
                    check(
                        WriteProcessMemory(
                            process,
                            argument,
                            data.getPointer(this),
                            length.toULong(),
                            written.ptr,
                        ) != 0 && written.value == length.toULong()
                    ) {
                        "Cannot copy bootstrap argument"
                    }
                }
            val thread =
                CreateRemoteThread(
                    process,
                    null,
                    0u,
                    address.toLong().toCPointer<CFunction<(COpaquePointer?) -> UInt>>(),
                    argument,
                    0u,
                    null,
                ) ?: error("Cannot start resident bootstrap")
            bootstrap = RemoteBootstrap(process, thread, argument)
            admitted = true
            // DLL initialization must finish before freeing its argument, including when the caller
            // is cancelled.
            val result = checkNotNull(finishBootstrap())
            check(alive()) { "Factorio process exited" }
            return result
        } finally {
            if (!admitted && argument != null)
                VirtualFreeEx(process, argument, 0u, MEM_RELEASE.toUInt())
        }
    }

    private suspend fun attach() {
        var resident = processModule(pid, "factorio_bridge.dll")
        if (resident != null) {
            verifyResident(resident)
            check(mapResident() != null) {
                "Existing resident initialization is incomplete; restart Factorio"
            }
        }
        if (resident == null) {
            val loader =
                GetProcAddress(GetModuleHandleW("kernel32.dll"), "LoadLibraryW")
                    ?: error("Windows loader is absent")
            val loaderAddress = memScoped {
                val owner =
                    fm_module_for_address(loader) ?: error("Cannot locate Windows loader owner")
                val path = allocArray<UShortVar>(32768)
                check(GetModuleFileNameW(owner, path, 32768u) > 0u)
                val remote =
                    processModule(pid, path.toKString().substringAfterLast('\\'))
                        ?: error("Target loader module is absent")
                remote.base + loader.toLong().toULong() - owner.toLong().toULong()
            }
            val path = residentPath.wcstr
            remoteCall(loaderAddress, path, (residentPath.length + 1L) * 2)
            resident =
                processModule(pid, "factorio_bridge.dll")
                    ?: error("Resident library failed to load")
        }
        verifyResident(resident)
        memScoped {
            val argument = alloc<Bootstrap>()
            symbols.write(argument.symbols)
            val code =
                remoteCall(
                    resident.base + residentImage.export("fm_bootstrap").toULong(),
                    argument.ptr,
                    sizeOf<Bootstrap>(),
                )
            val state = mapResident() ?: error("Resident IPC did not initialize")
            check(code == 0u) {
                state.pointed.result.message.toKString().ifEmpty {
                    "Resident initialization failed"
                }
            }
        }
    }

    private fun verifyResident(module: ProcessModule) {
        check(module.path.equals(residentPath, true)) {
            "A resident from another path is loaded; restart Factorio after changing artifacts"
        }
    }

    suspend fun execute(
        operation: Int,
        limit: Int,
        action: UiAction?,
        query: WorldQuery? = null,
        inputName: String? = null,
        chatText: String? = null,
    ): GameSnapshot {
        if (operation == 7) symbols.controls.getOrThrow()
        if (operation == 8) symbols.world.getOrThrow()
        if (operation == 10 || operation == 11) {
            symbols.chat.getOrThrow()
            symbols.world.getOrThrow()
        }
        if (operation == 9) {
            symbols.timedInput.getOrThrow()
            symbols.world.getOrThrow()
            require(symbols.pauseOffsets != null) { "Timed input requires a pause adapter" }
        }
        while (true) {
            currentCoroutineContext().ensureActive()
            check(alive()) { "Factorio process exited" }
            when (WaitForSingleObject(resources.gate, 0u)) {
                WAIT_OBJECT_0,
                WAIT_ABANDONED -> break

                WAIT_TIMEOUT.toUInt() -> delay(10)
                else -> error("Cannot acquire resident lock")
            }
        }
        try {
            finishBootstrap()
            // A dead MCP may have released the mutex while its resident task was still running.
            // Never overwrite that task or interpret its result as this request's response.
            mapResident()?.let { state -> awaitResidentIdle(state, operation == 4, ::alive) }
            if (operation == 1) attach()
            if (operation == 4) {
                inputTasks.close()
                val module =
                    processModule(pid, "factorio_bridge.dll")
                        ?: return GameSnapshot("detached", false, 0)
                verifyResident(module)
                check(mapResident() != null) { "Cannot detach an uninitialized resident" }
                val state = checkNotNull(mapResident())
                fm_cancel_input(state)
                while (fm_input_active(state) != 0) {
                    check(alive()) { "Factorio process exited" }
                    delay(10)
                }
                val code = remoteCall(module.base + residentImage.export("fm_unhook").toULong())
                check(code == 0u) {
                    mapResident()?.pointed?.result?.message?.toKString()
                        ?: "Cannot remove resident hooks"
                }
                return GameSnapshot("detached", false, 0)
            }
            val state = mapResident() ?: error("Resident is absent; call attach")
            check(state.pointed.attached != 0) { "Resident is detached; call attach" }
            state.pointed.operation = if (operation == 1) 2u else operation.toUInt()
            state.pointed.limit = limit.toUInt()
            writeAction(
                state.pointed.action,
                action,
                when (action?.kind) {
                    3 -> symbols.input.getOrThrow().keys(action.keys)
                    1 ->
                        symbols.input
                            .getOrThrow()
                            .keys(
                                buildList {
                                    if (action.control) add("LCTRL")
                                    if (action.shift) add("LSHIFT")
                                    if (action.alt) add("LALT")
                                }
                            )

                    else -> emptyList()
                },
            )
            if (chatText != null) {
                val bytes = chatText.encodeToByteArray()
                require(bytes.size in 1..4096)
                state.pointed.chat.size = bytes.size.toUInt()
                bytes.forEachIndexed { index, byte -> state.pointed.chat.text[index] = byte }
                state.pointed.chat.text[bytes.size] = 0
            }
            if (query != null) {
                state.pointed.worldQuery.includeViewport = if (query.includeViewport) 1u else 0u
                val source = worldQueryLua.encodeToByteArray()
                val prepared =
                    if ("inspection" in query.arguments) {
                        val api =
                            runtimeApi ?: readRuntimeApi(executablePath).also { runtimeApi = it }
                        api.prepare(query)
                    } else query
                val arguments = prepared.arguments.toString().encodeToByteArray()
                require(
                    source.size < FM_MAX_LUA_SOURCE && arguments.size < FM_MAX_QUERY_ARGUMENTS
                ) {
                    "World query payload exceeds bound"
                }
                state.pointed.worldQuery.sourceSize = source.size.toUInt()
                state.pointed.worldQuery.argumentsSize = arguments.size.toUInt()
                source.forEachIndexed { index, byte ->
                    state.pointed.worldQuery.source[index] = byte
                }
                arguments.forEachIndexed { index, byte ->
                    state.pointed.worldQuery.arguments[index] = byte
                }
            }
            if (inputName != null) {
                val bytes = inputName.encodeToByteArray()
                require(bytes.size < FM_INPUT_NAME_SIZE && '\u0000' !in inputName)
                bytes.forEachIndexed { index, value -> state.pointed.inputName[index] = value }
                state.pointed.inputName[bytes.size] = 0
                state.pointed.inputCancel = 0
            }
            state.pointed.cancel = 0
            fm_submit(state)
            try {
                while (fm_take_result(state) == 0) {
                    check(alive()) { "Factorio process exited" }
                    delay(10)
                }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    fm_cancel_request(state)
                    while (alive() && fm_take_result(state) == 0) delay(10)
                }
                throw cancelled
            }
            val result = state.pointed.result
            check(result.error == 0) { result.message.toKString() }
            check(result.count in 0..4096) { "Invalid resident node count" }
            check(result.controlCount <= FM_MAX_CONTROLS.toUInt()) {
                "Invalid resident control count"
            }
            check(result.optionCount <= FM_MAX_OPTIONS.toUInt()) { "Invalid resident option count" }
            return GameSnapshot(
                when (result.state) {
                    1 -> "main_menu"
                    2 -> "in_game"
                    3 -> "loading"
                    4 -> "paused"
                    else -> "unknown"
                },
                result.attached != 0,
                result.frame.toLong(),
                (0 until result.count).map { i ->
                    val node = result.nodes[i]
                    WidgetSnapshot(
                        node.depth,
                        node.type.toKString(),
                        node.text.toKString(),
                        node.enabled != 0,
                        node.x,
                        node.y,
                        node.width,
                        node.height,
                        node.truncated and 1 != 0,
                        node.selected != 0,
                        node.truncated and 2 != 0,
                        WidgetProperties(
                                if (node.properties and 1u != 0u) node.checkState else null,
                                if (node.properties and 2u != 0u) node.toggled != 0 else null,
                                if (node.properties and 4u != 0u) node.selectedIndex else null,
                                if (node.properties and 8u != 0u)
                                    SliderProperties(
                                        node.value,
                                        node.minimum,
                                        node.maximum,
                                        node.valueStep,
                                    )
                                else null,
                                if (node.properties and 16u != 0u) {
                                    check(
                                        node.optionFirst <= result.optionCount &&
                                            node.optionCount <=
                                                result.optionCount - node.optionFirst &&
                                            node.optionCount <= node.optionTotal &&
                                            node.optionTotal <= 65536u
                                    )
                                    WidgetOptions(
                                        (0 until node.optionCount.toInt()).map { index ->
                                            val option =
                                                result.options[node.optionFirst.toInt() + index]
                                            WidgetOption(
                                                option.text.toKString(),
                                                option.truncated != 0u,
                                            )
                                        },
                                        node.optionTotal.toInt(),
                                    )
                                } else null,
                                if (node.properties and 4u != 0u)
                                    symbols.properties
                                        .getOrThrow()
                                        .options
                                        .exceptionOrNull()
                                        ?.message
                                else null,
                                prototype =
                                    if (node.properties and 32u != 0u)
                                        WidgetPrototype(
                                            node.prototypeName.toKString().takeIf {
                                                it.isNotEmpty()
                                            },
                                            node.prototypeType
                                                .toKString()
                                                .takeIf { it.isNotEmpty() }
                                                ?.removePrefix("class "),
                                            node.identityTruncated and 1u != 0u,
                                            node.identityTruncated and 2u != 0u,
                                        )
                                    else null,
                                element =
                                    if (node.properties and 1024u != 0u)
                                        WidgetElement(
                                            node.elementFlags and 2u != 0u,
                                            node.elementCount.toLong().takeIf {
                                                node.elementFlags and 3u == 3u
                                            },
                                            if (node.elementFlags and 4u != 0u)
                                                WidgetItem(
                                                    node.itemType
                                                        .toKString()
                                                        .removePrefix("class "),
                                                    node.elementFlags and 32u != 0u,
                                                    node.itemHealth.toDouble(),
                                                    node.durabilityLeft.takeIf {
                                                        node.elementFlags and 8u != 0u
                                                    },
                                                    node.magazineLeft.toDouble().takeIf {
                                                        node.elementFlags and 16u != 0u
                                                    },
                                                )
                                            else null,
                                        )
                                    else null,
                                quality =
                                    if (node.properties and 512u != 0u)
                                        WidgetPrototype(
                                            node.qualityName.toKString().takeIf { it.isNotEmpty() },
                                            node.qualityType
                                                .toKString()
                                                .takeIf { it.isNotEmpty() }
                                                ?.removePrefix("class "),
                                            node.identityTruncated and 4u != 0u,
                                            node.identityTruncated and 8u != 0u,
                                        )
                                    else null,
                                icons =
                                    if (node.properties and 2048u != 0u)
                                        WidgetIcons(
                                            widgetIconReference(node.iconNormal),
                                            widgetIconReference(node.iconHovered),
                                            widgetIconReference(node.iconDisabled),
                                        )
                                    else null,
                                qualityCondition =
                                    if (node.properties and 4096u != 0u)
                                        WidgetQualityCondition(
                                            node.conditionQuality.toInt(),
                                            node.conditionComparison.toInt(),
                                            symbols.conditions
                                                .getOrThrow()
                                                .comparison(node.conditionComparison.toInt()),
                                            if (node.conditionLookup == 1u)
                                                node.conditionName.toKString()
                                            else null,
                                            node.conditionNameTruncated != 0u,
                                            when (node.conditionLookup) {
                                                0u -> "null"
                                                1u -> "present"
                                                2u -> "index_out_of_range"
                                                else ->
                                                    error("Invalid quality condition lookup state")
                                            },
                                        )
                                    else null,
                                progress =
                                    if (node.properties and 256u != 0u)
                                        WidgetProgress(
                                            node.progressValue,
                                            symbols.progress
                                                .getOrThrow()
                                                .direction(node.progressDirection.toInt()),
                                            node.progressHasText != 0u,
                                        )
                                    else null,
                                switch =
                                    if (node.properties and 8192u != 0u)
                                        WidgetSwitch(
                                            node.switchState.toInt(),
                                            symbols.switches
                                                .getOrThrow()
                                                .state(node.switchState.toInt()),
                                            node.switchAllowNone != 0u,
                                        )
                                    else null,
                                number =
                                    if (node.properties and 64u != 0u)
                                        WidgetNumber(
                                            node.numberFlags and 1u != 0u,
                                            node.numberValue.takeIf {
                                                node.numberFlags and 16u != 0u
                                            },
                                            (node.numberFlags and 2u != 0u).takeIf {
                                                node.numberFlags and 1u != 0u
                                            },
                                            (node.numberFlags and 4u != 0u).takeIf {
                                                node.numberFlags and 1u != 0u
                                            },
                                            (node.numberFlags and 8u != 0u).takeIf {
                                                node.numberFlags and 1u != 0u
                                            },
                                        )
                                    else null,
                            )
                            .takeUnless { it == emptyWidgetProperties },
                        visible = if (node.properties and 128u != 0u) node.visible != 0 else null,
                        renderEnabled =
                            if (node.properties and 128u != 0u) node.renderEnabled != 0 else null,
                        hiddenBySearch =
                            if (node.properties and 128u != 0u) node.hiddenBySearch != 0 else null,
                    )
                },
                result.truncated != 0,
                if (result.paused < 0) null else result.paused != 0,
                if (operation == 6) {
                    check(result.imageSize in 1u..FM_MAX_IMAGE.toUInt()) { "Invalid image size" }
                    result.image.reinterpret<ByteVar>().readBytes(result.imageSize.toInt())
                } else null,
                result.imageWidth.toInt(),
                result.imageHeight.toInt(),
                (0 until result.controlCount.toInt()).map {
                    symbols.controls.getOrThrow().read(result.controls[it])
                },
                result.registryCount.toInt(),
                symbols.properties.exceptionOrNull()?.let {
                    it.message ?: "Widget property adapter is unavailable"
                },
                if (operation == 8) {
                    check(result.worldSize in 1u until FM_MAX_WORLD_JSON.toUInt()) {
                        "Invalid world query result size"
                    }
                    val text = result.worldJson.readBytes(result.worldSize.toInt()).decodeToString()
                    if (query != null && "inspection" in query.arguments) {
                        checkNotNull(runtimeApi).annotate(decodeWorldQuery(text)).toString()
                    } else text
                } else null,
                slotIdentityUnavailableReason = symbols.slots.exceptionOrNull()?.message,
                numberUnavailableReason = symbols.numbers.exceptionOrNull()?.message,
                visibilityUnavailableReason = symbols.visibility.exceptionOrNull()?.message,
                progressUnavailableReason = symbols.progress.exceptionOrNull()?.message,
                elementUnavailableReason = symbols.elements.exceptionOrNull()?.message,
                iconsUnavailableReason = symbols.icons.exceptionOrNull()?.message,
                qualityConditionUnavailableReason = symbols.conditions.exceptionOrNull()?.message,
                switchUnavailableReason = symbols.switches.exceptionOrNull()?.message,
                chat =
                    if (operation == 10) {
                        check(result.chat.count <= FM_MAX_CHAT.toUInt()) {
                            "Invalid chat result count"
                        }
                        ChatSnapshot(
                            result.chat.consoleIdentity,
                            List(2) { result.chat.totals[it] },
                            List(result.chat.count.toInt()) { index ->
                                val record = result.chat.records[index]
                                check(record.stream < 2u)
                                ChatRecord(
                                    record.identity,
                                    record.tick,
                                    record.stream.toInt(),
                                    record.playerIndex.toInt(),
                                    record.text.toKString(),
                                    record.raw.toKString(),
                                    record.truncated and 1u != 0u,
                                    record.truncated and 2u != 0u,
                                )
                            },
                        )
                    } else null,
            )
        } finally {
            ReleaseMutex(resources.gate)
        }
    }

    suspend fun close() {
        inputTasks.close()
        closeResources()
    }

    private fun closeResources() {
        bootstrap?.close()
        bootstrap = null
        exitWait?.close()
        exitWait = null
        resources.close()
    }

    suspend fun beginInput(request: InputSequenceRequest): GameInputTask {
        symbols.timedInput.getOrThrow()
        if (
            request.timeline.any { (it.control as? InputControl.Pointer)?.path?.space == "world" }
        ) {
            symbols.viewport.getOrThrow()
        }
        val pending =
            NativeInputTask(
                pid,
                request,
                { names -> names.map { symbols.input.getOrThrow().keys(listOf(it)).single() } },
                ::alive,
            )
        val task = inputTasks.retain(pending)
        try {
            execute(9, 4096, null, inputName = pending.name)
            return task
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                try {
                    task.close()
                } catch (cleanup: Throwable) {
                    failure.addSuppressed(cleanup)
                }
            }
            throw failure
        }
    }
}
