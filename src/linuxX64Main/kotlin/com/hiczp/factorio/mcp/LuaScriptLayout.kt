package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/**
 * State-pointer association and load-handler flags; selection and Lua invocation are verified
 * separately.
 */
internal data class LuaScriptLayout(
    val size: Long,
    val state: Long,
    val vtable: Long,
    val typeInfo: Long,
    val readiness: ScriptReadiness,
) {
    companion object {
        fun resolve(image: ElfImage): LuaScriptLayout {
            val size = SysVObjectSize.resolve(image, "13LuaGameScript")
            val table = ItaniumVtable.resolve(image, "_ZTV13LuaGameScript")
            table.method(image, "_ZN13LuaGameScriptD0Ev")
            val loadState =
                stateMember(
                    image,
                    "_ZN13LuaGameScript14checkRunOnLoadERK9SetupData",
                    "lua_rawgetglobal",
                    size,
                )
            val eventState =
                stateMember(
                    image,
                    "_ZN13LuaGameScript15runEventHandlerE12LuaEventTypei",
                    "lua_pushcclosure",
                    size,
                )
            require(loadState == eventState) {
                "Script lifecycle methods disagree on their Lua state member"
            }
            return LuaScriptLayout(
                size,
                loadState,
                table.addressPoint,
                image.symbol("_ZTI13LuaGameScript").address,
                SysVScopedFlags.resolve(image, size),
            )
        }

        private fun stateMember(
            image: ElfImage,
            caller: String,
            callee: String,
            extent: Long,
        ): Long {
            val from = image.symbol(caller)
            val to = image.symbol(callee)
            EhFrames(image).function(to)
            return stateMember(
                X64ControlFlow.resolve(image, from),
                to.address - from.address,
                extent,
            )
        }

        /**
         * Observes existing game calls; unrelated native borrows are not adapter ABI boundaries.
         */
        fun stateMember(flow: X64ControlFlow, callee: Long, extent: Long): Long {
            require(extent in 8..16 * 1024 * 1024)
            val origins = OriginalPointerOrigins(flow, attributeMembers = true)
            val calls =
                flow.instructions.filter {
                    it.offset in flow.reachable &&
                        it.operation == Operation.CALL &&
                        it.destination == Immediate(callee)
                }
            require(calls.isNotEmpty())
            val members =
                calls
                    .map { call ->
                        SysVLocalArgument(flow.reaching(call.offset))
                        val receiver =
                            origins.register(call.offset, 7) as? OriginalPointerOrigins.MemberSource
                                ?: error("Lua call state is not an original pointer member")
                        require(receiver.argument == 7)
                        val member = receiver.member
                        require(member in 0..extent - 8 && member % 8 == 0L)
                        member
                    }
                    .distinct()
            return members.singleOrNull() ?: error("Lua calls disagree on their state member")
        }
    }
}
