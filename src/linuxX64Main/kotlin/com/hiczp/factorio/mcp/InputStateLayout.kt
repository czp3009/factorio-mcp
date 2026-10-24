package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/**
 * The global object and typed modifier-call receiver. Update/lookup entry ABIs remain independent
 * obligations.
 */
internal data class InputStateLayout(
    val global: Long,
    val globalSize: Long,
    val member: Long,
    val size: Long,
) {
    init {
        require(
            global > 0 &&
                global <= Long.MAX_VALUE - 8 &&
                globalSize >= 8 &&
                member in 0..globalSize - 8
        )
        require(size in 8..(16 * 1024 * 1024))
    }

    companion object {
        fun resolve(image: ElfImage): InputStateLayout {
            val global =
                SysVGlobalAllocation.resolve(
                    image,
                    "_ZN9MainTasks6createERK13ParsedOptions",
                    "_ZN13GlobalContextC2ERKN10Filesystem4PathES3_",
                    "global",
                )
            val size =
                SysVOwnedObjectSize.resolveInlined(
                        image,
                        "_ZN13UniquePointerI10InputState13SimpleDeleterIS0_ELb1EED2Ev",
                    )
                    .size
            val function = image.symbol("_ZN16InputHandlerAgui12processEventERK5Event")
            val flow = X64ControlFlow.resolve(image, function)
            val root = image.symbol("global").address
            val pointers = GlobalPointerLoad(flow, function.address, root, global.size)
            val members =
                listOf("_ZNK10InputState10isCtrlDownEv", "_ZNK10InputState11isShiftDownEv").map {
                    name ->
                    val getter = image.symbol(name)
                    EhFrames(image).function(getter)
                    val call =
                        flow.instructions.single {
                            it.operation == Operation.CALL &&
                                it.destination == Immediate(getter.address - function.address)
                        }
                    pointers.at(call.offset, 7)
                }
            return InputStateLayout(root, global.size, members.distinct().single(), size)
        }
    }
}
