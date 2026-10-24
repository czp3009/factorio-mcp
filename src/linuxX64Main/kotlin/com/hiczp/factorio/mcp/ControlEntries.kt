package com.hiczp.factorio.mcp

/**
 * Typed semantic boundaries; each consumer still proves the selected body's optimized ABI and data
 * flow.
 */
internal object ControlEntries {
    const val ACTIVE =
        "_ZNK12ControlInput8isActiveE9NamedBoolI11GuiCheckTagES0_I22CheckIsUsedByActionTagES0_I17CheckModifiersTagES0_I17IgnoreBlockingTagE"
    const val VALUE_ACTIVE =
        "_ZNK17ControlInputValue8isActiveE9NamedBoolI19ContinuousActionTagES0_I11GuiCheckTagES0_I22CheckIsUsedByActionTagES0_I17CheckModifiersTagES0_I17IgnoreBlockingTagE"
    const val TRIGGERED =
        "_ZNK12ControlInput28triggeredByWithoutSideEffectERK5EventPK14ControlContextj"
    const val VALUE_TRIGGERED = "_ZNK17ControlInputValue11triggeredByERK5EventPK14ControlContextjb"
    const val MODIFIERS =
        "_ZNK17ControlInputValue14checkModifiersE9NamedBoolI19ContinuousActionTagE"
    const val REQUIRED_MODIFIERS = "_ZNK17ControlInputValue23checkModifiersAreActiveEv"
}
