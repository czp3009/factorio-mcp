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

internal data class WidgetSnapshot(
    val depth: Int,
    val type: String,
    val text: String,
    val enabled: Boolean,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val truncated: Boolean,
    val selected: Boolean = false,
    val typeTruncated: Boolean = false,
    val properties: WidgetProperties? = null,
    val visible: Boolean? = null,
    val renderEnabled: Boolean? = null,
    val hiddenBySearch: Boolean? = null,
)

internal data class WidgetProperties(
    val checkState: String? = null,
    val toggled: Boolean? = null,
    val selectedIndex: Int? = null,
    val slider: SliderProperties? = null,
    val options: WidgetOptions? = null,
    val optionsUnavailableReason: String? = null,
    val prototype: WidgetPrototype? = null,
    val number: WidgetNumber? = null,
    val progress: WidgetProgress? = null,
    val quality: WidgetPrototype? = null,
    val element: WidgetElement? = null,
    val icons: WidgetIcons? = null,
    val qualityCondition: WidgetQualityCondition? = null,
    val switch: WidgetSwitch? = null,
)

internal data class WidgetSwitch(val stateValue: Int, val state: String, val allowNone: Boolean)

internal data class WidgetQualityCondition(
    val qualityIndex: Int,
    val comparisonValue: Int,
    val comparison: String,
    val qualityName: String? = null,
    val qualityNameTruncated: Boolean = false,
    val qualityLookup: String = "null",
)

/** References use snapshot-local sprite indices; -1 is null and -2 is truncated. */
internal data class WidgetIcons(val normal: Int, val hovered: Int, val disabled: Int)

internal data class WidgetSprite(
    val filename: String?,
    val filenameTruncated: Boolean,
    val intentionallyEmpty: Boolean,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val scale: Double,
    val shiftX: Double,
    val shiftY: Double,
    val tint: List<Double>,
    val next: Int,
    val extra: Int,
)

internal data class WidgetElement(
    val present: Boolean,
    val stackCount: Long?,
    val item: WidgetItem?,
)

internal data class WidgetItem(
    val nativeType: String,
    val typeTruncated: Boolean,
    val health: Double,
    val durabilityLeft: Double? = null,
    val magazineLeft: Double? = null,
)

internal data class WidgetProgress(val value: Double, val direction: String, val hasText: Boolean)

internal data class WidgetNumber(
    val drawRequested: Boolean,
    val value: Double?,
    val showZero: Boolean?,
    val unknown: Boolean?,
    val infinite: Boolean?,
)

internal data class WidgetPrototype(
    val name: String?,
    val nativeType: String?,
    val nameTruncated: Boolean = false,
    val typeTruncated: Boolean = false,
)

internal data class WidgetOption(val text: String, val truncated: Boolean = false)

internal data class WidgetOptions(val values: List<WidgetOption>, val total: Int)

internal data class SliderProperties(
    val value: Double,
    val minimum: Double,
    val maximum: Double,
    val step: Double,
)

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
    val spriteUnavailableReason: String? = null,
    val sprites: List<WidgetSprite> = emptyList(),
    val qualityConditionUnavailableReason: String? = null,
    val switchUnavailableReason: String? = null,
    val chat: ChatSnapshot? = null,
    val inputTransfer: InputTransferSnapshot? = null,
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
