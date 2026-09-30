package com.hiczp.factorio.mcp

/** Public SDL2 ABI constants, not Factorio layouts. No SDL function is called or linked.
 * https://github.com/libsdl-org/SDL/blob/SDL2/include/SDL_events.h
 * https://github.com/libsdl-org/SDL/blob/SDL2/include/SDL_mouse.h
 */
internal object SdlButtonAdmission {
    // SDL_MouseButtonEvent: four Uint32 fields, four Uint8 fields, then two Sint32 fields.
    private const val EXTENT = 4L * 4 + 4 + 2 * 4
    private const val TYPE = 0L
    private const val BUTTON = 4L * 4
    private const val DOWN = 0x401L
    private const val UP = 0x402L

    enum class Button(val sdkValue: Int) { LEFT(1), MIDDLE(2), RIGHT(3) }
    enum class Transition(val sdkValue: Long) { PRESS(DOWN), RELEASE(UP) }
    data class Case(val button: Button, val transition: Transition, val path: List<Long>)
    data class Proof(val table: GuardedByteTable.Proof, val cases: List<Case>)

    fun extents(table: GuardedByteTable.Proof): Map<Int, Long> = mapOf(table.input.reference.argument to EXTENT)

    fun kinds(table: GuardedByteTable.Proof, expression: ScalarExpression.Value): Map<Transition, Long> {
        val type = SysVArgumentFlow.Read(SysVArgumentFlow.Reference(table.input.reference.argument, TYPE), 4)
        require(expression.width == 4 && ScalarExpression.inputs(expression).map { it.field }.toSet() == setOf(type)) {
            "Native mouse kind is not derived solely from the SDK event type"
        }
        return Transition.entries.associateWith { transition ->
            ScalarExpression.evaluate(expression) { transition.sdkValue }
        }
            .also { require(it.values.toSet().size == Transition.entries.size) { "Native mouse transitions are indistinguishable" } }
    }

    fun analyze(flow: X64ControlFlow, table: GuardedByteTable.Proof): Proof {
        val paths = buttonPaths(flow, table, Button.entries.map { it.sdkValue }.toSet())
        return Proof(table, Button.entries.flatMap { button ->
            Transition.entries.map { transition ->
                Case(button, transition, paths.getValue(button.sdkValue to transition))
            }
        })
    }

    fun buttonPaths(
        flow: X64ControlFlow, table: GuardedByteTable.Proof,
        buttons: Set<Int>
    ): Map<Pair<Int, Transition>, List<Long>> {
        require(buttons.isNotEmpty() && buttons.all { it in 1..255 })
        require(table.input.width == 1 && table.input.reference.offset == BUTTON) {
            "Lookup input is not the SDK mouse-button field"
        }
        val argument = table.input.reference.argument
        require(SysVArgumentFlow(flow).source(table.inputLoad) == table.input)
        val type = SysVArgumentFlow.Read(SysVArgumentFlow.Reference(argument, TYPE), 4)
        val analysis = ScalarBranchPath(flow, extents(table))
        return buttons.flatMap { button ->
            require(table.value(button) != null) { "Native table rejects an SDK mouse button" }
            Transition.entries.map { transition ->
                val path = analysis.to(table.load) {
                    when (it.field) {
                        type -> transition.sdkValue
                        table.input -> button.toLong()
                        else -> error("SDK button admission depends on an unverified input")
                    }
                }
                require(table.inputLoad in path)
                (button to transition) to path
            }
        }.toMap()
    }
}
