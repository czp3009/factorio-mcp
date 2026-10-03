package com.hiczp.factorio.mcp

/** Typed GlobalContext -> Scenario -> Game ownership, without requiring Lua or player adapters. */
internal data class WorldReferences(
    val global: Long,
    val globalSize: Long,
    val scenarioSize: Long,
    val gameSize: Long,
    val scenario: Long,
    val game: Long,
) {
    init {
        require(global > 0 && global % 8 == 0L && global <= Long.MAX_VALUE - 8)
        require(listOf(globalSize, scenarioSize, gameSize).all { it in 8..(64 * 1024 * 1024) })
        require(scenario in 0..globalSize - 8 && game in 0..scenarioSize - 8)
    }

    companion object {
        fun resolve(image: ElfImage): WorldReferences {
            val global = SysVGlobalAllocation.resolve(
                image, "_ZN9MainTasks6createERK13ParsedOptions",
                "_ZN13GlobalContextC2ERKN10Filesystem4PathES3_", "global"
            )
            fun owned(type: String) = SysVOwnedObjectSize.resolve(
                image, "_ZNSt10unique_ptrI${type}St14default_deleteIS0_EED2Ev", "_ZN${type}D2Ev"
            ).size
            val scenarioSize = owned("8Scenario")
            val gameSize = owned("4Game")
            val scenario = SysVGlobalMember.resolve(image, "_ZN8ScenarioD2Ev", "global", global.size, scenarioSize)
            val noReturn = SysVNoReturn(image, setOf("abort", "__cxa_throw", "_ZSt20__throw_system_errori"))
                .resolve("_Z19ReleaseAssertFailedPKcjS0_")
            val game = SysVMemberCalls.direct(image, "_ZN8ScenarioD2Ev", "_ZN4GameD2Ev", scenarioSize, setOf(noReturn))
            return WorldReferences(image.symbol("global").address, global.size, scenarioSize, gameSize, scenario, game)
        }
    }
}
