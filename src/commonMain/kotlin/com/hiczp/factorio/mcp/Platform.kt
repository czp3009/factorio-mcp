package com.hiczp.factorio.mcp

import kotlinx.coroutines.awaitCancellation
import kotlinx.io.Sink
import kotlinx.io.Source

internal expect object Platform {
    fun standardInput(): Source

    fun standardOutput(): Sink

    fun findProcesses(name: String): List<Int>

    fun initializeCancellation()

    val cancelled: Boolean

    fun writeError(message: String)
}

internal data class GameSnapshot(
    val state: String,
    val attached: Boolean,
    val frame: Long,
    val nodes: List<WidgetSnapshot> = emptyList(),
    val truncated: Boolean = false,
    val paused: Boolean? = null,
    val image: ByteArray? = null,
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    val controls: List<ControlSnapshot> = emptyList(),
    val registryCount: Int = 0,
    val propertiesUnavailableReason: String? = null,
    val worldJson: String? = null,
    val slotIdentityUnavailableReason: String? = null,
    val numberUnavailableReason: String? = null,
    val visibilityUnavailableReason: String? = null,
    val progressUnavailableReason: String? = null,
    val elementUnavailableReason: String? = null,
    val iconsUnavailableReason: String? = null,
    val qualityConditionUnavailableReason: String? = null,
    val switchUnavailableReason: String? = null,
    val chat: ChatSnapshot? = null,
)

/** Native commands finish at a frontend safe point; cancellation is cooperative. */
internal interface GameConnection {
    suspend fun execute(operation: Int, limit: Int = 4096, action: UiAction? = null): GameSnapshot

    suspend fun isAlive(): Boolean

    suspend fun query(query: WorldQuery): GameSnapshot =
        error("World queries are unavailable on this adapter")

    suspend fun sendChat(text: String): GameSnapshot =
        error("Chat submission is unavailable on this adapter")

    suspend fun beginInput(request: InputSequenceRequest): GameInputTask =
        error("Timed input is unavailable on this adapter")

    /** Optional event-driven notification; preflight checks remain required on every platform. */
    suspend fun awaitExit(): Unit = awaitCancellation()

    suspend fun close()
}

internal expect class GameProcess(pid: Int) : GameConnection {
    override suspend fun execute(operation: Int, limit: Int, action: UiAction?): GameSnapshot

    override suspend fun isAlive(): Boolean

    override suspend fun query(query: WorldQuery): GameSnapshot

    override suspend fun sendChat(text: String): GameSnapshot

    override suspend fun beginInput(request: InputSequenceRequest): GameInputTask

    override suspend fun awaitExit()

    override suspend fun close()
}
