package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.SysVReceiverFlow.Pointer
import com.hiczp.factorio.mcp.SysVReceiverFlow.Receiver
import com.hiczp.factorio.mcp.X64Instructions.Immediate
import com.hiczp.factorio.mcp.X64Instructions.Operation

/** Typed LuaPlayer -> Player -> OutputConsole pointer association; no printing or live dereference. */
internal object ChatConsoleMember {
    fun resolve(image: ElfImage, playerSize: Long): Long {
        val wrapperSize = SysVObjectSize.resolve(image, "9LuaPlayer")
        val player = SysVArgumentMember.resolve(image, "_ZN9LuaPlayerC2EP6PlayerP9lua_State", wrapperSize)
        val caller = image.symbol("_ZN9LuaPlayer8luaPrintEP9lua_State")
        val add =
            image.symbol("_ZN13OutputConsole3addERK15LocalisedStringPK6PlayerRK13PrintSettingsOSt6vectorI25SavedSpecialItemReferenceSaISA_EE")
        EhFrames(image).function(caller)
        EhFrames(image).function(add)
        return analyze(image.functionBytes(caller, 8192), caller.address, add.address, wrapperSize, player, playerSize)
    }

    fun analyze(bytes: BinaryView, address: Long, add: Long, wrapperSize: Long, player: Long, playerSize: Long): Long {
        require(
            wrapperSize in 8..(64 * 1024 * 1024) && playerSize in 8..(64 * 1024 * 1024) &&
                    player in 0..wrapperSize - 8 && player % 8 == 0L && add > 0
        )
        val flow = SysVReceiverFlow(bytes, address, wrapperSize)
        val call = flow.instructions.filter {
            it.offset in flow.reachable && it.operation == Operation.CALL &&
                    it.destination == Immediate(add - address)
        }.singleOrNull() ?: error("Console dispatch is absent or ambiguous")
        // Only pointer registers are evidence here. Borrowed PrintSettings/text locals are never accessed.
        val console = flow.borrowedCallValues(call.offset)[7] as? Pointer
            ?: error("Console dispatch receiver is not a pointer member")
        val owner = console.base as? Pointer ?: error("Console dispatch lacks a typed Player owner")
        require(
            owner.base == Receiver() && owner.offset == player && console.offset in 8..playerSize - 8 &&
                    console.offset % 8 == 0L
        ) { "Console dispatch does not use the original LuaPlayer's bounded Player member" }
        return console.offset
    }
}
