package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Existing Lua name reads associate a bounded native string with its typed prototype wrapper. */
internal object LuaStringMember {
    fun resolve(
        image: ElfImage,
        function: String,
        wrapper: String,
        extent: Long,
        string: NativeStringLayout,
    ): Long {
        val entry = image.symbol(function)
        val push = image.symbol("lua_pushlstring")
        EhFrames(image).function(push)
        return analyze(
            X64ControlFlow.resolve(image, entry),
            push.address - entry.address,
            SysVObjectSize.resolve(image, wrapper),
            extent,
            string,
        )
    }

    fun analyze(
        flow: X64ControlFlow,
        push: Long,
        wrapperExtent: Long,
        objectExtent: Long,
        string: NativeStringLayout,
    ): Long {
        require(wrapperExtent in 8..65536 && objectExtent in string.size..16 * 1024 * 1024)
        val origins = OriginalPointerOrigins(flow)
        val calls =
            flow.instructions.filter {
                it.offset in flow.reachable &&
                    it.operation == Operation.CALL &&
                    it.destination == Immediate(push)
            }
        require(calls.isNotEmpty())
        val fields =
            calls.map { call ->
                SysVLocalArgument(flow.reaching(call.offset))
                require(origins.register(call.offset, 7) == OriginalPointerOrigins.Argument(6)) {
                    "String reader does not preserve its original Lua state"
                }
                val data =
                    origins.register(call.offset, 6) as? OriginalPointerOrigins.Load
                        ?: error("String reader does not pass a native data member")
                val length =
                    origins.register(call.offset, 2) as? OriginalPointerOrigins.Load
                        ?: error("String reader does not pass a native length member")
                val objectPointer =
                    data.base as? OriginalPointerOrigins.Load
                        ?: error("String reader has no wrapper-owned object")
                require(
                    length.base == objectPointer &&
                        objectPointer.base == OriginalPointerOrigins.Argument(7)
                )
                require(
                    objectPointer.member in 0..wrapperExtent - 8 && objectPointer.member % 8 == 0L
                )
                val member = data.member - string.data
                require(
                    member in 0..objectExtent - string.size &&
                        member % 8 == 0L &&
                        length.member - string.length == member
                ) {
                    "Native string parts disagree or exceed the object"
                }
                member
            }
        return fields.distinct().singleOrNull()
            ?: error("Lua name reads disagree on their string member")
    }
}
