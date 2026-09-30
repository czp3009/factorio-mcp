@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.linuxbridge.FmLinuxLuaStateLayout

/** Matching state allocation, embedded base frame and accessor evidence; live identity is checked in the resident. */
internal data class LuaStateLayout(
    val allocation: LuaAllocation,
    val stack: LuaStackLayout,
    val stackBase: Long,
    val stackEnd: Long,
    val baseFrame: Long,
    val call: LuaProtectedCall,
    val handler: Long,
) {
    init {
        require(
            allocation.size in 16..(64 * 1024 * 1024) && allocation.globalOffset in 8..allocation.size - 8 &&
                    allocation.globalOffset % 8 == 0L && allocation.mainState in 0..allocation.size - allocation.globalOffset - 8 &&
                    allocation.mainState % 8 == 0L && baseFrame in 0 until allocation.globalOffset &&
                    stack.function in 0..allocation.globalOffset - baseFrame - 8 &&
                    call.frameTop in 0..allocation.globalOffset - baseFrame - 8 && call.status in 0 until allocation.globalOffset
        )
        val pointers = listOf(
            allocation.global, stack.top, stackBase, stackEnd, stack.callInfo,
            baseFrame + stack.function, baseFrame + call.frameTop, handler
        ).sorted()
        require(pointers.all { it in 0..allocation.globalOffset - 8 && it % 8 == 0L && call.status !in it until it + 8 } &&
                pointers.zipWithNext().all { (left, right) -> left + 8 <= right })
    }

    fun writeTo(output: FmLinuxLuaStateLayout) {
        output.allocationSize = allocation.size.toUInt()
        output.globalOffset = allocation.globalOffset.toUInt()
        output.global = allocation.global.toUInt()
        output.mainState = allocation.mainState.toUInt()
        output.top = stack.top.toUInt()
        output.stackBase = stackBase.toUInt()
        output.stackEnd = stackEnd.toUInt()
        output.callInfo = stack.callInfo.toUInt()
        output.baseFrame = baseFrame.toUInt()
        output.function = stack.function.toUInt()
        output.frameTop = call.frameTop.toUInt()
        output.status = call.status.toUInt()
        output.handler = handler.toUInt()
        output.valueSize = stack.valueSize.toUInt()
    }

    companion object {
        fun resolve(image: ElfImage): LuaStateLayout {
            val allocation = SysVLuaAllocation.resolve(image)
            val stack = SysVLuaStack.resolve(image)
            val loader = SysVLuaLoadFrame.resolve(image, stack, allocation.globalOffset)
            val call = SysVLuaProtectedCall.resolve(image, stack, loader, allocation.globalOffset)
            return LuaStateLayout(
                allocation, stack, loader.stackBase,
                SysVLuaCapacity.resolve(image, stack, allocation.globalOffset),
                SysVLuaBaseFrame.resolve(image, allocation, stack, call), call,
                SysVLuaProtection.resolve(image, allocation.globalOffset).handler
            )
        }
    }
}
