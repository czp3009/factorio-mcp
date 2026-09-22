package com.hiczp.factorio.mcp

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import kotlin.uuid.Uuid

internal data class ResidentPacket(val id: String, val failed: Boolean, val body: ByteArray, val worldChanged: Boolean)
internal data class ResidentState(
    val instance: ULong, val generation: ULong, val inGame: Boolean, val ready: Boolean,
    val mainMenu: Boolean, val descriptor: ULong, val actionsInstalled: Boolean, val bindingRequested: Boolean
)

internal enum class ResidentOperation { Status, Task, BindWorld }
internal interface ResidentWire {
    fun send(id: String, operation: ResidentOperation, source: String, timeoutMillis: Int, generation: ULong)
    suspend fun receive(): ResidentPacket
    fun decodeState(bytes: ByteArray): ResidentState
    fun close()
}

/** Request correlation belongs to MCP; unknown and expired IDs are simply discarded. */
internal class ResidentClient(private val wire: ResidentWire, private val pid: Int) {
    private val mutex = Mutex()

    private data class Pending(val reply: CompletableDeferred<ResidentPacket>, val operation: ResidentOperation)

    private val pending = mutableMapOf<String, Pending>()
    private var stopped = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reader = scope.launch {
        try {
            while (isActive) {
                val packet = wire.receive()
                mutex.withLock {
                    if (packet.worldChanged) {
                        val obsolete = pending.filterValues { it.operation == ResidentOperation.Task }.keys
                        obsolete.forEach { id ->
                            pending.remove(id)?.reply?.completeExceptionally(
                                IllegalStateException("The game world changed; call status and attach before issuing more game operations")
                            )
                        }
                    } else pending.remove(packet.id)?.reply?.complete(packet)
                }
            }
        } catch (error: Exception) {
            mutex.withLock {
                stopped = true
                pending.values.forEach { it.reply.completeExceptionally(error) }
                pending.clear()
            }
        }
    }

    private suspend fun request(
        operation: ResidentOperation,
        source: String,
        timeoutMillis: Int,
        generation: ULong = 0u
    ): ResidentPacket {
        val id = Uuid.random().toString()
        val reply = CompletableDeferred<ResidentPacket>()
        try {
            mutex.withLock {
                check(!stopped) { "Resident connection is closed" }
                pending[id] = Pending(reply, operation)
                wire.send(id, operation, source, timeoutMillis, generation)
            }
            val result = withTimeout(timeoutMillis.toLong()) { reply.await() }
            check(!result.failed) {
                val message = result.body.decodeToString()
                runCatching {
                    Json.parseToJsonElement(message).jsonObject["value"]?.jsonObject?.get("message")?.jsonPrimitive?.content
                }.getOrNull() ?: message
            }
            return result
        } finally {
            withContext(NonCancellable) { mutex.withLock { pending.remove(id) } }
        }
    }

    suspend fun submit(description: String, timeoutMillis: Int): String =
        request(ResidentOperation.Task, description, timeoutMillis).body.decodeToString(throwOnInvalidSequence = true)

    suspend fun state(timeoutMillis: Int): ResidentState =
        wire.decodeState(request(ResidentOperation.Status, "", timeoutMillis).body)

    suspend fun bindWorld(generation: ULong, timeoutMillis: Int) =
        wire.decodeState(request(ResidentOperation.BindWorld, "", timeoutMillis, generation).body)

    suspend fun status(timeoutMillis: Int): String {
        val state = state(timeoutMillis)
        return buildJsonObject {
            put("pid", pid)
            put(
                "state", when {
                    state.mainMenu -> "main_menu"; state.inGame -> "in_game"; else -> "transitioning"
                }
            )
            put("recognized", true)
            put("resident", true)
            put("ready", state.ready)
            put("actions_installed", state.actionsInstalled)
            put("stage", if (state.ready) "world_ready" else "resident_loaded")
            put(
                "waiting_for", when {
                    state.ready -> "none"; state.bindingRequested -> "world_callback"; state.inGame -> "attach"; else -> "active_world"
                }
            )
            put("instance", state.instance.toString())
            put("world_generation", state.generation.toString())
        }.toString()
    }

    suspend fun close() = withContext(NonCancellable) {
        mutex.withLock { stopped = true }
        reader.cancelAndJoin()
        mutex.withLock {
            pending.values.forEach { it.reply.completeExceptionally(IllegalStateException("Resident connection closed")) }
            pending.clear()
            wire.close()
        }
        scope.cancel()
    }
}
