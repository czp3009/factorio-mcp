package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** Connects the original Lua direction byte to the typed Frame constructor's consumed input. */
internal object GuiDirectionArgument {
    private const val CONSTRUCTOR =
        "_ZN4agui5FrameC2ENS_12GuiDirectionEPKNS_10FrameStyleERKNSt7__cxx1112basic_stringIcSt11char_traitsIcESaIcEEE"

    fun verify(image: ElfImage, sourceSize: Long, sourceMember: Long): DwarfSourceLines.Source {
        val create = image.symbol("_ZN11CustomFrame12createWidgetEv")
        val constructor = image.symbol(CONSTRUCTOR)
        val allocator = image.symbol("_Znwm")
        val flow = X64ControlFlow.resolve(image, create)
        val call =
            flow.instructions.single {
                it.offset in flow.reachable &&
                    it.operation == Operation.CALL &&
                    it.destination == Immediate(constructor.address - create.address)
            }
        forwards(flow, call.offset, sourceSize, sourceMember)

        // This is the game's unchanged constructor call, not an adapter invocation. Prove the
        // actual receiver and consumed scalar without recursively analyzing its other callees.
        val window = SysVDeletingTailSize.resolve(image, image.symbol("_ZN4agui6WindowD0Ev"))
        val prefix = flow.reaching(call.offset)
        val constants = LocalStringArgument(prefix, NativeStringLayout.resolve(image))
        val allocation =
            prefix.instructions.single {
                it.offset in prefix.reachable &&
                    it.operation == Operation.CALL &&
                    it.destination == Immediate(allocator.address - create.address) &&
                    runCatching { constants.constant(it.offset, 7) == window }.getOrDefault(false)
            }
        NativeAllocationResult.verify(prefix, allocation, call, 7)

        val body = X64ControlFlow.resolve(image, constructor)
        val instances = image.inlines.find(constructor, "Frame", setOf("operator=="))
        val test =
            body.instructions.single {
                it.offset in body.reachable &&
                    it.operation == Operation.TEST &&
                    (it.destination as? Register)?.width == 1 &&
                    it.source == it.destination &&
                    instances.any { instance ->
                        instance.ranges.any { range ->
                            constructor.address + it.offset >= range.start &&
                                constructor.address + it.offset + it.size <= range.end
                        }
                    }
            }
        consumes(body, test.offset)
        val instance =
            instances.single { inline ->
                inline.ranges.any { range ->
                    constructor.address + test.offset in range.start until range.end
                }
            }
        return image.inlines.source(instance, constructor.address + test.offset)
    }

    fun forwards(flow: X64ControlFlow, call: Long, sourceSize: Long, sourceMember: Long) {
        require(flow.body[call]?.operation == Operation.CALL && call in flow.reachable)
        require(sourceSize in 8..4096 && sourceMember in 8 until sourceSize)
        val expression = ScalarExpression(flow, mapOf(7 to sourceSize))
        var value = expression.before(call, Register(6, 1))
        while (value is ScalarExpression.Narrow) value = value.value
        require(
            value is ScalarExpression.Input &&
                value.width in listOf(1, 2, 4) &&
                value.field ==
                    SysVArgumentFlow.Read(SysVArgumentFlow.Reference(7, sourceMember), value.width)
        ) {
            "Typed direction call changes or replaces the original member's low byte"
        }
    }

    fun consumes(flow: X64ControlFlow, test: Long) {
        val instruction = flow.body.getValue(test)
        val register = instruction.destination as? Register
        require(
            instruction.operation == Operation.TEST &&
                register?.width == 1 &&
                instruction.source == register &&
                test in flow.reachable
        )
        require(
            PrivateValueCopies(flow.reaching(test), emptyMap(), scalarArguments = mapOf(6 to 1))
                .argument(test, register) == 6
        ) {
            "Typed direction comparison does not consume the original scalar input byte"
        }
    }
}
