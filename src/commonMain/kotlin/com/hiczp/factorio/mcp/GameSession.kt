package com.hiczp.factorio.mcp

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** All transports share one attachment; detach and process exit share resource cleanup. */
internal class GameSession(private val open: (Int) -> GameConnection = ::GameProcess) {
    private class Attachment(
        val pid: Int,
        val connection: GameConnection,
        var unhooked: Boolean = false,
    ) {
        var observer: Job? = null
        val chat = ChatHistory()
    }

    private val lifecycle = Mutex()
    private val command = Mutex()
    private val registry = Mutex()
    private val observations = mutableSetOf<Job>()
    private val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var attachment: Attachment? = null
    private var exitedPid: Int? = null
    private var detaching = false

    suspend fun attach(selected: Int): JsonObject = observe {
        attachment?.let { if (!it.connection.isAlive()) processExited(it) }
        val existing = attachment
        check(existing == null || existing.pid == selected) {
            "Detach from PID ${existing?.pid} before attaching to another process"
        }
        if (existing != null) {
            useConnection(existing) { it.execute(1).statusJson(selected) }
        } else {
            val target = open(selected)
            try {
                val result = target.execute(1).statusJson(selected)
                check(target.isAlive()) { "Factorio process $selected exited during attach" }
                val current = Attachment(selected, target)
                attachment = current
                exitedPid = null
                current.observer =
                    observerScope.launch(start = CoroutineStart.LAZY) {
                        try {
                            target.awaitExit()
                            command.withLock { if (attachment === current) processExited(current) }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            Platform.writeError(
                                "factorio-mcp: process exit observer: ${failure.message}"
                            )
                        }
                    }
                current.observer!!.start()
                result
            } catch (failure: Throwable) {
                withContext(NonCancellable) {
                    var unhooked = false
                    try {
                        if (target.isAlive()) {
                            target.execute(4)
                            unhooked = true
                        }
                        target.close()
                    } catch (cleanupFailure: Exception) {
                        attachment = Attachment(selected, target, unhooked)
                        failure.addSuppressed(cleanupFailure)
                        Platform.writeError(
                            "factorio-mcp: attach cleanup failed; retry detach: ${cleanupFailure.message}"
                        )
                        throw failure
                    }
                }
                throw failure
            }
        }
    }

    private suspend fun <T> observe(serialized: Boolean = true, block: suspend () -> T): T =
        supervisorScope {
            val task =
                async(start = CoroutineStart.LAZY) {
                    if (serialized) command.withLock { block() } else block()
                }
            try {
                registry.withLock {
                    check(!detaching) { "Attachment is being detached" }
                    observations += task
                }
                task.start()
                task.await()
            } finally {
                task.cancel()
                withContext(NonCancellable) {
                    task.join()
                    registry.withLock { observations -= task }
                }
            }
        }

    /** Called with command held; never joins the observation that discovered the exit. */
    private suspend fun processExited(target: Attachment, discoveringJob: Job? = null) {
        val caller = discoveringJob ?: currentCoroutineContext()[Job]
        withContext(NonCancellable) {
            if (attachment !== target) return@withContext
            exitedPid = target.pid
            val otherTasks =
                registry.withLock {
                    observations
                        .filter { it !== caller }
                        .also { tasks ->
                            tasks.forEach { task ->
                                task.cancel(
                                    CancellationException(
                                        "Tool aborted: Factorio process ${target.pid} exited"
                                    )
                                )
                            }
                        }
                }
            otherTasks.joinAll()
            release(target, unhook = false)
        }
    }

    /** Covers both the preflight check and exit between that check and a native response. */
    private suspend fun <T> useConnection(
        target: Attachment,
        block: suspend (GameConnection) -> T,
    ): T {
        check(!target.unhooked) { "Attachment cleanup is incomplete; retry detach" }
        if (!target.connection.isAlive()) {
            processExited(target)
            error("Factorio process ${target.pid} exited; call attach to select a running client")
        }
        try {
            return block(target.connection)
        } catch (failure: Exception) {
            val caller = currentCoroutineContext()[Job]
            withContext(NonCancellable) {
                try {
                    if (!target.connection.isAlive()) {
                        processExited(target, caller)
                    }
                } catch (cleanup: Exception) {
                    failure.addSuppressed(cleanup)
                }
            }
            throw failure
        }
    }

    suspend fun status(): JsonObject = observe {
        val target = attachment
        if (target != null && !target.connection.isAlive()) processExited(target)
        val current = attachment
        if (current == null) {
            exitedPid?.let { exitedStatus(it) } ?: detachedStatus()
        } else {
            useConnection(current) { it.execute(2).statusJson(current.pid) }
        }
    }

    suspend fun read(limit: Int, bounds: Boolean, selector: List<UiStep>? = null): JsonObject =
        observe {
            val target =
                attachment
                    ?: error(
                        exitedPid?.let {
                            "Factorio process $it exited; call attach to select a running client"
                        } ?: "Call attach before ui_read"
                    )
            useConnection(target) {
                it.execute(3, limit, selector?.let { path -> UiAction(path) })
                    .uiJson(bounds, selector != null)
            }
        }

    suspend fun action(action: UiAction): JsonObject = observe {
        val target = attachment ?: error("Call attach before ui_action")
        useConnection(target) { it.execute(5, action = action) }
        buildJsonObject { put("dispatch", "completed") }
    }

    suspend fun screenshot(): GameSnapshot = observe {
        val target = attachment ?: error("Call attach before screenshot")
        useConnection(target) { it.execute(6) }
    }

    suspend fun input(request: InputSequenceRequest): JsonObject {
        var target: Attachment? = null
        var completion: InputSequenceResult? = null
        var failure: Throwable? = null
        try {
            return observe(serialized = false) {
                val task =
                    command.withLock {
                        val current = attachment ?: error("Call attach before input")
                        target = current
                        useConnection(current) { it.beginInput(request) }
                    }
                var inputFailure: Throwable? = null
                try {
                    task.awaitResult().json()
                } catch (error: Throwable) {
                    inputFailure = error
                    throw error
                } finally {
                    withContext(NonCancellable) {
                        try {
                            completion = task.close()
                        } catch (cleanup: Throwable) {
                            if (inputFailure == null) throw cleanup
                            inputFailure.addSuppressed(cleanup)
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            failure = error
            // Detach cancels the child operation, but a still-live tool caller needs its final
            // progress.
            if (error is CancellationException && currentCoroutineContext().isActive)
                completion?.let {
                    failure = null
                    return it.json()
                }
            throw error
        } finally {
            // Run after this observation has left the registry. Exit cleanup may hold command while
            // waiting for admitted tasks to close, so their own cleanup must never acquire command.
            withContext(NonCancellable) {
                try {
                    command.withLock {
                        target?.let {
                            if (attachment === it && !it.connection.isAlive()) processExited(it)
                        }
                    }
                } catch (cleanup: Throwable) {
                    if (failure == null) throw cleanup
                    failure.addSuppressed(cleanup)
                }
            }
        }
    }

    suspend fun query(query: WorldQuery): JsonObject = observe {
        val target = attachment ?: error("Call attach before world_query")
        val snapshot = useConnection(target) { it.query(query) }
        val value = decodeWorldQuery(checkNotNull(snapshot.worldJson))
        buildJsonObject {
            value.forEach { (name, item) -> put(name, item) }
            put("ui_frame", snapshot.frame)
            put("state", snapshot.state)
        }
    }

    suspend fun readChat(after: String?, limit: Int): JsonObject = observe {
        require(limit in 1..128) { "Chat limit must be 1..128" }
        val target = attachment ?: error("Call attach before chat_read")
        val snapshot = useConnection(target) { it.execute(10) }
        val result = target.chat.read(checkNotNull(snapshot.chat), after, limit)
        JsonObject(
            result +
                    mapOf(
                        "ui_frame" to JsonPrimitive(snapshot.frame),
                        "state" to JsonPrimitive(snapshot.state),
                    )
        )
    }

    suspend fun sendChat(text: String): JsonObject = observe {
        val target = attachment ?: error("Call attach before chat_send")
        useConnection(target) { it.sendChat(text) }
        buildJsonObject { put("dispatch", "completed") }
    }

    suspend fun bindings(ids: Set<String>?, search: String?, offset: Int, limit: Int): JsonObject =
        observe {
            val target = attachment ?: error("Call attach before input_bindings")
            useConnection(target) { it.execute(7).bindingsJson(ids, search, offset, limit) }
        }

    private suspend fun drain() {
        val pending =
            registry.withLock {
                detaching = true
                observations.toList().also { tasks ->
                    tasks.forEach {
                        it.cancel(CancellationException("Tool aborted: detach requested"))
                    }
                }
            }
        pending.joinAll()
    }

    /**
     * Requires command ownership. Failed unhook on a live process keeps the attachment retryable.
     */
    private suspend fun release(target: Attachment, unhook: Boolean) =
        withContext(NonCancellable) {
            if (unhook && !target.unhooked && target.connection.isAlive()) {
                try {
                    target.connection.execute(4)
                    target.unhooked = true
                } catch (failure: Exception) {
                    if (target.connection.isAlive()) throw failure
                }
            }
            target.observer?.cancel()
            target.connection.close()
            // Keep the owner reachable if local cleanup failed, just as for a failed unhook.
            if (attachment === target) attachment = null
        }

    suspend fun detach(): JsonObject =
        lifecycle.withLock {
            withContext(NonCancellable) {
                try {
                    drain()
                    command.withLock {
                        attachment?.let { release(it, unhook = true) }
                        exitedPid = null
                    }
                    detachedStatus()
                } finally {
                    registry.withLock { detaching = false }
                }
            }
        }

    suspend fun close() =
        lifecycle.withLock {
            withContext(NonCancellable) {
                try {
                    drain()
                    command.withLock {
                        attachment?.let { release(it, unhook = false) }
                        exitedPid = null
                    }
                } finally {
                    observerScope.cancel()
                }
            }
        }
}
