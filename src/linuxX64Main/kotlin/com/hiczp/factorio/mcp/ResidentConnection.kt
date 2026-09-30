@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.linuxbridge.*
import kotlinx.cinterop.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import platform.posix.EAGAIN
import platform.posix.ENOENT
import platform.posix._SC_PAGESIZE
import platform.posix.sysconf

/** Thread-affine owner of the process, borrowed trace, bootstrap storage and leased resident IPC. */
internal class ResidentConnection(pid: Int, private val residentPath: String) {
    private val process = ProcessHandle(pid)
    private var trace: ThreadTrace? = null
    private var loader: LibraryLoader? = null
    private var mapping: SharedMapping? = null
    private var channel: ResidentChannel? = null
    private var layout: WidgetLayout? = null
    private var worldMetadata: WorldQueryMetadata? = null
    private var inputContextMetadata: InputContextMetadata? = null
    private var retirementEntry: ViewRetirementMetadata.Entry? = null
    private var clickMetadata: UiClickMetadata? = null
    private var textMetadata: UiTextMetadata? = null
    private var controlLayouts: ControlLayouts? = null
    private var keyMetadata: UiKeyMetadata? = null
    private var chatMetadata: ChatMetadata? = null
    private var chatReadMetadata: ChatReadMetadata? = null
    private var frameMetadata: FrameContextMetadata? = null
    private var frameBinding: FrameHookBinding? = null
    private var runtimeApi: RuntimeApi? = null
    private var loadBias = 0L
    private var ready = false
    private var closed = false
    private var safeStop = false

    fun alive(): Boolean = !closed && process.alive()

    private fun storage(): FmLinuxShared = checkNotNull(mapping).memory.reinterpret<FmLinuxShared>().pointed

    private fun openMapping() {
        if (mapping == null) mapping = ResidentMapping.open(process, residentPath)
        if (channel == null) channel = ResidentChannel(process, checkNotNull(mapping))
    }

    private suspend fun borrow(site: FrontendSite): FrontendSite.Verified {
        check(trace == null) { "Previous trace cleanup is incomplete; retry detach" }
        val owner = ThreadTrace(process.pid)
        trace = owner
        owner.seize()
        owner.interrupt()
        return site.stopAfterLogic(owner).also { safeStop = true }
    }

    private suspend fun call(name: String): Long {
        check(safeStop) { "Resident call requires a verified frontend stop" }
        val function = ProcessModules(process).functions(setOf(name), residentPath).getValue(name)
        return checkNotNull(trace).call(function.address).toLong()
    }

    private suspend fun releaseTrace() {
        loader?.let {
            it.cleanup()
            loader = null
        }
        trace?.let {
            it.close()
            trace = null
            safeStop = false
        }
    }

    private suspend fun attach() {
        check(!closed && trace == null && channel == null) { "Previous attachment cleanup is incomplete; retry detach" }
        check(process.alive()) { "Factorio exited" }
        if (process.mappings().any { it.path == residentPath }) {
            // Validate initialized storage and acquire the lease before invoking any retained export.
            openMapping()
            checkNotNull(channel).reconcile()
            removeHook()
        }
        val site = FrontendSite.resolve(process)
        worldMetadata = null
        inputContextMetadata = null
        retirementEntry = null
        clickMetadata = null
        textMetadata = null
        keyMetadata = null
        controlLayouts = null
        frameMetadata = null
        frameBinding = null
        process.withExecutable { image ->
            loadBias = image.loadBias(process.executableMappings(), sysconf(_SC_PAGESIZE))
            layout = WidgetLayout.resolve(image).also { it.verifyLoaded(image, process, loadBias) }
        }
        val verified = borrow(site)
        if (mapping == null) {
            val bootstrap = LibraryLoader(checkNotNull(trace), process)
            loader = bootstrap
            bootstrap.load(residentPath)
            check(call("fm_linux_initialize") >= 0) { "Resident IPC initialization failed" }
            openMapping()
        }
        checkNotNull(channel).reconcile()
        verified.writeTo(storage().config)
        check(call("fm_linux_attach") == 0L) { "Resident frontend hook installation failed" }
        releaseTrace()
        ready = true
    }

    /** Also handles a failed install/removal whose pointer is gone but page protection is still owned. */
    private suspend fun removeHook() {
        val connection = channel ?: return
        if (!process.alive()) return
        if (trace != null) {
            loader?.cleanup()
            checkNotNull(trace).completePendingCall()
        }
        if (!connection.attached) return
        if (trace == null && fm_ipc_load(fm_linux_pointer_word(storage().ptr)) != 0u) {
            connection.reconcile()
            val result = connection.execute(FM_LINUX_DETACH)
            check(result.code == 0) { "Resident hook cleanup failed: ${result.code}; retry detach" }
        } else {
            if (trace == null) borrow(FrontendSite.resolve(process))
            check(call("fm_linux_cleanup") == 0L) { "Resident hook cleanup failed; retry detach" }
        }
        check(!connection.attached) { "Resident cleanup still owns hook resources" }
        releaseTrace()
    }

    suspend fun execute(operation: Int, limit: Int, action: UiAction?): GameSnapshot {
        if (operation == 4) {
            withContext(NonCancellable) {
                removeHook()
                releaseTrace()
            }
            ready = false
            return GameSnapshot("detached", false, 0)
        }
        if (operation == 1 && !ready) attach()
        check(ready && process.alive()) { "Linux attachment is not ready" }
        if (operation == 2) return observeState()
        if (operation == 7) return inputBindings()
        if (operation == 6) return screenshot()
        if (operation == 10) return readChat()
        if (operation == 5) return when (checkNotNull(action).kind) {
            1 -> click(action)
            2 -> setText(action)
            3 -> pressKey(action)
            else -> error("This UI action is not implemented by the Linux adapter yet")
        }
        require(action == null || operation == 3 && action.kind == 0) { "Linux UI actions are not implemented yet" }
        require(operation in 1..3) { "This operation is not implemented by the Linux adapter yet" }
        val connection = checkNotNull(channel)
        return if (operation == 3) {
            require(limit in 1..FM_LINUX_MAX_NODES)
            connection.execute(FM_LINUX_UI, {
                checkNotNull(layout).writeTo(it.ui, loadBias)
                it.nodeLimit = limit.toUInt()
                it.selector.writePath(action?.path)
            }, { shared, result ->
                check(result.code == 0) { "Native UI read failed: ${result.code}" }
                GameSnapshot(
                    "unknown", true, result.frame.toLong(), shared.snapshot.readWidgets(),
                    truncated = shared.snapshot.truncated != 0u,
                    propertiesUnavailableReason = "Linux currently reads toggled, raw check_state, slider, progress and dropdown values for verified controls; enum names and other optional properties are not implemented yet"
                )
            })
        } else {
            val result = connection.execute(FM_LINUX_FRAME)
            check(result.code == 0) { "Native frontend observation failed: ${result.code}" }
            GameSnapshot("unknown", true, result.frame.toLong())
        }
    }

    private suspend fun screenshot(): GameSnapshot {
        val metadata = frameMetadata ?: process.withExecutable { image ->
            FrameContextMetadata.resolve(image).also { it.verifyLoaded(image, process, loadBias) }
        }.also { frameMetadata = it }
        val mappings = process.mappings()
        val site = frameBinding ?: FrameHookBinding.bind(
            metadata.swap, metadata.backends, loadBias,
            sysconf(_SC_PAGESIZE), mappings, process::readMemory
        ).also { frameBinding = it }
        val api = FrameApiBinding.bind(metadata.apiSlots, loadBias, mappings, process::readMemory)
        return checkNotNull(channel).execute(FM_LINUX_SCREENSHOT, { shared ->
            metadata.writeTo(shared.frameContext, loadBias)
            site.writeTo(shared.frameSite)
            FrameApiBinding.writeTo(api, shared.frameApi)
        }, { shared, result ->
            check(result.code == 0) { "Native screenshot failed: ${result.code} (OpenGL ${shared.frameGlError})" }
            val width = shared.frameSize.width.toInt()
            val height = shared.frameSize.height.toInt()
            require(width in 1..8192 && height in 1..8192 && width.toLong() * height <= 16_777_216)
            val bytes = width * height * 3
            require(shared.frameBytes.toLong() == bytes.toLong()) { "Incomplete native screenshot" }
            GameSnapshot(
                "unknown", true, result.frame.toLong(), image = PngEncoder.encode(
                    width, height,
                    shared.framePixels.readBytes(bytes)
                ), imageWidth = width, imageHeight = height
            )
        })
    }

    private suspend fun inputBindings(): GameSnapshot {
        val metadata = controlLayouts ?: process.withExecutable { image ->
            ControlLayouts.resolve(image).also { it.verifyLoaded(image, process, loadBias) }
        }.also { controlLayouts = it }
        return checkNotNull(channel).execute(FM_LINUX_CONTROLS, { shared ->
            metadata.writeTo(shared.controlsLayout, loadBias)
        }, { shared, result ->
            check(result.code == 0) { "Native input binding read failed: ${result.code}" }
            metadata.read(shared.controlsSnapshot, result.frame.toLong())
        })
    }

    private suspend fun observeState(): GameSnapshot {
        val metadata = inputContextMetadata ?: process.withExecutable { image ->
            InputContextMetadata.resolve(image).also { it.verifyLoaded(image, process, loadBias) }
        }.also { inputContextMetadata = it }
        val retirement = retirementEntry ?: process.withExecutable { image ->
            ViewRetirementMetadata.resolve(
                image, metadata.source.gameSize, metadata.source.viewSize,
                metadata.source.gameView
            ).verifyLoaded(image, process, loadBias)
        }.also { retirementEntry = it }
        return checkNotNull(channel).execute(FM_LINUX_INPUT_CONTEXT, { shared ->
            metadata.writeTo(shared.inputContextConfig, loadBias)
            shared.retirementConfig.entry = retirement.address.toULong()
            shared.retirementConfig.original = retirement.original.toULong()
            shared.retirementConfig.protection = retirement.protection.toUInt()
        }, { shared, result ->
            if (result.code in setOf(-ENOENT, -EAGAIN)) {
                GameSnapshot("unknown", true, result.frame.toLong())
            } else {
                check(result.code == 0) { "Native input context read failed: ${result.code}" }
                val observation = shared.inputContextSnapshot
                require(observation.paused <= 1u && observation.stopped <= 255u)
                val paused = observation.paused != 0u || observation.stopped != 0u
                GameSnapshot(if (paused) "paused" else "in_game", true, result.frame.toLong(), paused = paused)
            }
        })
    }

    private suspend fun click(action: UiAction): GameSnapshot {
        require(action.kind == 1) {
            "The Linux adapter currently implements only click actions"
        }
        require(
            action.button in 0..2 && action.x.isFinite() && action.y.isFinite() &&
                    action.x in 0.0..1.0 && action.y in 0.0..1.0
        )
        val connection = checkNotNull(channel)
        check(!connection.actionOwned) { "Previous action cleanup is incomplete; retry detach" }
        val metadata = clickMetadata ?: process.withExecutable { image ->
            UiClickMetadata.resolve(image, process, loadBias)
        }.also { clickMetadata = it }
        return connection.execute(FM_LINUX_CLICK, { shared ->
            checkNotNull(layout).writeTo(shared.ui, loadBias)
            metadata.writeTo(shared.clickConfig, loadBias)
            shared.selector.writePath(action.path)
            shared.clickRequest.button = action.button.toUInt()
            shared.clickRequest.x = action.x
            shared.clickRequest.y = action.y
            shared.clickRequest.control = if (action.control) 1u else 0u
            shared.clickRequest.shift = if (action.shift) 1u else 0u
            shared.clickRequest.alt = if (action.alt) 1u else 0u
        }, { _, result ->
            check(result.code == 0 && !connection.actionOwned) {
                "Native UI click failed: ${result.code}" +
                        if (connection.actionOwned) "; cleanup is incomplete, retry detach" else ""
            }
            GameSnapshot("unknown", true, result.frame.toLong())
        })
    }

    private suspend fun setText(action: UiAction): GameSnapshot {
        require(
            action.kind == 2 && action.text.size <= FM_LINUX_TEXT_SCALARS &&
                    action.text.all { it in 0..0x10ffff && it !in 0xd800..0xdfff })
        val connection = checkNotNull(channel)
        check(!connection.actionOwned) { "Previous action cleanup is incomplete; retry detach" }
        val metadata = textMetadata ?: process.withExecutable { image ->
            UiTextMetadata.resolve(image).also { it.verifyLoaded(image, process, loadBias) }
        }.also { textMetadata = it }
        return connection.execute(FM_LINUX_TEXT, { shared ->
            checkNotNull(layout).writeTo(shared.ui, loadBias)
            metadata.writeTo(shared.textConfig, loadBias)
            shared.selector.writePath(action.path)
            shared.textRequest.count = action.text.size.toUInt()
            action.text.forEachIndexed { index, value -> shared.textRequest.text[index] = value.toUInt() }
        }, { _, result ->
            check(result.code == 0 && !connection.actionOwned) {
                "Native UI text replacement failed: ${result.code}" +
                        if (connection.actionOwned) "; cleanup is incomplete, retry detach" else ""
            }
            GameSnapshot("unknown", true, result.frame.toLong())
        })
    }

    private suspend fun pressKey(action: UiAction): GameSnapshot {
        require(action.kind == 3 && action.path.size <= FM_LINUX_UI_PATH)
        val keys = SdlScancodes.chord(action.keys)
        val connection = checkNotNull(channel)
        check(!connection.actionOwned) { "Previous action cleanup is incomplete; retry detach" }
        val metadata = keyMetadata ?: process.withExecutable { image ->
            UiKeyMetadata.resolve(image, process, loadBias)
        }.also { keyMetadata = it }
        return connection.execute(FM_LINUX_KEY, { shared ->
            checkNotNull(layout).writeTo(shared.ui, loadBias)
            metadata.writeTo(shared.keyConfig, shared.pollConfig, loadBias)
            shared.selector.writePath(action.path)
            shared.keyRequest.count = keys.size.toUInt()
            keys.forEachIndexed { index, key -> shared.keyRequest.keys[index] = key }
        }, { _, result ->
            check(result.code == 0 && !connection.actionOwned) {
                "Native key gesture failed: ${result.code}" +
                        if (connection.actionOwned) "; cleanup is incomplete, retry detach" else ""
            }
            GameSnapshot("unknown", true, result.frame.toLong())
        })
    }

    private suspend fun readChat(): GameSnapshot {
        val connection = checkNotNull(channel)
        check(!connection.actionOwned) { "Previous native cleanup is incomplete; retry detach" }
        val metadata = chatReadMetadata ?: process.withExecutable { image ->
            ChatReadMetadata.resolve(image).also { it.verifyLoaded(image, process, loadBias) }
        }.also { chatReadMetadata = it }
        return connection.execute(FM_LINUX_CHAT_READ, { shared ->
            metadata.writeTo(shared.chatReadConfig, loadBias)
            shared.chatSnapshot.count = 0u
        }, { shared, result ->
            check(result.code == 0 && !connection.actionOwned) {
                "Native chat observation failed: ${result.code}" +
                        if (connection.actionOwned) "; cleanup is incomplete, retry detach" else ""
            }
            val output = shared.chatSnapshot
            require(output.count <= FM_LINUX_CHAT_RECORDS.toUInt() && output.consoleIdentity != 0uL)
            val totals = List(2) { output.totals[it].also { count -> require(count <= 65536uL) } }
            val records = List(output.count.toInt()) { index ->
                val row = output.records[index]
                require(
                    row.identity != 0uL && row.stream <= 1u && row.truncated <= 3u &&
                            row.playerIndex < (1u shl (metadata.player.index.width * 8)) &&
                            row.textSize < FM_LINUX_CHAT_RECORD_BYTES.toUInt() && row.rawSize < FM_LINUX_CHAT_RECORD_BYTES.toUInt()
                )
                ChatRecord(
                    row.identity, row.tick, row.stream.toInt(), row.playerIndex.toInt(),
                    row.text.readBytes(row.textSize.toInt()).decodeToString(),
                    row.raw.readBytes(row.rawSize.toInt()).decodeToString(),
                    row.truncated and 1u != 0u, row.truncated and 2u != 0u
                )
            }
            require(records.map { it.identity }.distinct().size == records.size && (0..1).all { stream ->
                records.count { it.stream == stream }.toULong() == minOf(
                    totals[stream],
                    (FM_LINUX_CHAT_RECORDS / 2).toULong()
                )
            })
            GameSnapshot(
                "unknown",
                true,
                result.frame.toLong(),
                chat = ChatSnapshot(output.consoleIdentity, totals, records)
            )
        })
    }

    suspend fun sendChat(text: String): GameSnapshot {
        check(ready && process.alive()) { "Linux attachment is not ready" }
        val bytes = validateChatMessage(text).encodeToByteArray()
        require(bytes.size <= FM_LINUX_CHAT_TEXT_BYTES)
        val connection = checkNotNull(channel)
        check(!connection.actionOwned) { "Previous action cleanup is incomplete; retry detach" }
        val metadata = chatMetadata ?: process.withExecutable { image ->
            ChatMetadata.resolve(image).also { it.verifyLoaded(image, process, loadBias) }
        }.also { chatMetadata = it }
        return connection.execute(FM_LINUX_CHAT, { shared ->
            metadata.writeTo(shared.chatConfig, loadBias)
            shared.chatRequest.size = bytes.size.toUInt()
            bytes.forEachIndexed { index, byte -> shared.chatRequest.text[index] = byte.toUByte() }
            shared.chatProgress.entered = 0u
            shared.chatProgress.returned = 0u
        }, { shared, result ->
            val progress = shared.chatProgress
            check(progress.entered <= 1u && progress.returned <= progress.entered) {
                "Invalid native chat submission progress"
            }
            check(result.code == 0 && progress.returned == 1u && !connection.actionOwned) {
                "Native chat submission failed: ${result.code}; entered=${progress.entered}, returned=${progress.returned}" +
                        if (connection.actionOwned) "; cleanup is incomplete, retry detach" else ""
            }
            GameSnapshot("unknown", true, result.frame.toLong())
        })
    }

    suspend fun query(query: WorldQuery): GameSnapshot {
        check(ready && process.alive()) { "Linux attachment is not ready" }
        val metadata = worldMetadata ?: process.withExecutable { image ->
            WorldQueryMetadata.resolve(image).also { it.verifyLoaded(image, process, loadBias) }
        }.also { worldMetadata = it }
        val prepared = if ("inspection" in query.arguments) {
            val api = runtimeApi ?: readRuntimeApi(process.executablePath()).also { runtimeApi = it }
            api.prepare(query)
        } else query
        val source = worldQueryLua.encodeToByteArray()
        val arguments = prepared.arguments.toString().encodeToByteArray()
        require(source.size in 1 until FM_LINUX_LUA_SOURCE && arguments.size in 1 until FM_LINUX_QUERY_ARGUMENTS) {
            "World query payload exceeds bound"
        }
        return checkNotNull(channel).execute(FM_LINUX_QUERY, { shared ->
            shared.queryResult.size = 0u
            shared.queryResult.errorSize = 0u
            metadata.writeTo(shared.worldConfig, loadBias)
            shared.query.sourceSize = source.size.toUInt()
            shared.query.argumentsSize = arguments.size.toUInt()
            shared.query.includeViewport = if (query.includeViewport) 1u else 0u
            source.forEachIndexed { index, byte -> shared.query.source[index] = byte }
            arguments.forEachIndexed { index, byte -> shared.query.arguments[index] = byte }
        }, { shared, result ->
            val output = shared.queryResult
            require(output.size < FM_LINUX_QUERY_RESULT.toUInt() && output.errorSize < FM_LINUX_QUERY_ERROR.toUInt()) {
                "Native world-query result exceeds bounds"
            }
            check(result.code == 0) {
                val detail = output.error.readBytes(output.errorSize.toInt()).decodeToString()
                "Native world query failed: ${result.code}${if (detail.isEmpty()) "" else ": $detail"}"
            }
            require(output.size > 0u && output.errorSize == 0u) { "Native world query returned no valid result" }
            GameSnapshot(
                "unknown", true, result.frame.toLong(),
                worldJson = output.text.readBytes(output.size.toInt()).decodeToString()
            )
        })
    }

    suspend fun close() = withContext(NonCancellable) {
        if (closed) return@withContext
        removeHook()
        releaseTrace()
        mapping?.let {
            it.close()
            mapping = null
            channel = null
        }
        process.close()
        ready = false
        closed = true
    }
}
