package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.SysVArgumentFlow.Reference
import com.hiczp.factorio.mcp.X64Instructions.*

/** Hidden string output evidenced by native construction and the selected caller's reserved frame. */
internal object StringResultStorage {
    data class Proof(val argument: Int, val initialize: Long, val length: Long, val callers: List<Long>)

    fun resolve(
        image: ElfImage, function: String, name: String, callerName: String, storage: NativeStringLayout,
        debug: DwarfInlines = image.inlines, initializerName: String = "_Alloc_hider",
        lengthName: String = "_M_length"
    ): Proof {
        val method = image.symbol(function)
        val callerMethod = image.symbol(callerName)
        require(method.size in 1..32768 && callerMethod.size in 1..32768)
        EhFrames(image).function(method)
        EhFrames(image).function(callerMethod)
        val inlines = debug.find(method, name, setOf(initializerName, lengthName))
        fun ranges(inlineName: String) = inlines.filter { it.name == inlineName }.flatMap { it.ranges }
            .map { DwarfRanges.Range(it.start - method.address, it.end - method.address) }

        val result = analyze(
            X64ControlFlow.resolve(image, method), storage.data, storage.length, storage.local,
            storage.size, ranges(initializerName), ranges(lengthName)
        )
        val calls = caller(
            X64ControlFlow.resolve(image, callerMethod), method.address - callerMethod.address,
            result.argument, storage.size
        )
        return result.copy(callers = calls)
    }

    fun analyze(
        flow: X64ControlFlow, data: Long, length: Long, local: Long, size: Long,
        initializers: List<DwarfRanges.Range>, lengths: List<DwarfRanges.Range>
    ): Proof {
        require(
            size in 1..4096 && data in 0..size - 8 && length in 0..size - 8 && local in 0 until size &&
                    data + 8 <= local && length + 8 <= local && (data + 8 <= length || length + 8 <= data)
        )
        fun within(offset: Long, ranges: List<DwarfRanges.Range>): Boolean {
            val instruction = flow.body.getValue(offset)
            return ranges.any { offset >= it.start && offset + instruction.size <= it.end }
        }

        val arguments = SysVArgumentFlow(flow)
        val initializations = flow.instructions.filter {
            it.offset in flow.reachable && it.operation == Operation.MOV &&
                    within(it.offset, initializers)
        }.mapNotNull { instruction ->
            val target = instruction.destination as? Memory ?: return@mapNotNull null
            val source = instruction.source as? Register ?: return@mapNotNull null
            if (target.width != 8 || source.width != 8) return@mapNotNull null
            val to = arguments.memory(instruction.offset, target)?.reference ?: return@mapNotNull null
            val from = arguments.register(instruction.offset, source.number) ?: return@mapNotNull null
            if (to.offset == data && from == Reference(to.argument, local)) to.argument to instruction.offset else null
        }
        val (argument, initialize) = initializations.singleOrNull()
            ?: error("String result lacks one original output pointer initialized to its own inline buffer")
        val stores = flow.instructions.filter { instruction ->
            instruction.offset in flow.reachable && instruction.operation == Operation.MOV &&
                    within(instruction.offset, lengths) && (instruction.destination as? Memory)?.let {
                it.width == 8 && arguments.memory(instruction.offset, it)?.reference == Reference(argument, length)
            } == true
        }
        val store = stores.singleOrNull() ?: error("String result has no unique native length store into that output")
        // The selected native constructor cannot set the output length without first establishing its storage.
        val pending = ArrayDeque<Long>()
        val visited = mutableSetOf<Long>()
        pending.add(0)
        while (pending.isNotEmpty()) {
            val site = pending.removeFirst()
            if (site == initialize || !visited.add(site)) continue
            require(site != store.offset) { "String length store bypasses its storage initialization" }
            pending.addAll(flow.successors.getValue(site))
        }
        for (instruction in flow.instructions.filter { it.offset in flow.reachable }) {
            for (operand in listOf(instruction.destination, instruction.source).filterIsInstance<Memory>()) {
                val reference = arguments.memory(instruction.offset, operand)?.reference ?: continue
                if (reference.argument == argument) require(reference.offset in 0..size - operand.width) {
                    "String result directly accesses outside its independently established storage"
                }
            }
        }
        // This proves output placement, not construction semantics, other arguments, or an RAX return value.
        return Proof(argument, initialize, store.offset, emptyList())
    }

    fun caller(flow: X64ControlFlow, target: Long, argument: Int, size: Long): List<Long> {
        require(argument in listOf(7, 6, 2, 1, 8, 9) && size in 1..4096)
        val calls = flow.instructions.filter {
            it.offset in flow.reachable && it.operation == Operation.CALL &&
                    it.destination == Immediate(target)
        }
        require(calls.isNotEmpty()) { "Selected caller does not invoke the string-producing entry" }
        val locals = SysVLocalArgument(flow)
        return calls.map { call ->
            val output = locals.argument(call.offset, argument, size.toInt())
            val ancestors = flow.reaching(call.offset).reachable
            for (instruction in flow.instructions.filter { it.offset in ancestors && it.operation == Operation.PUSH }) {
                val pushed = instruction.destination as? Register ?: continue
                if (pushed.number in listOf(3, 5, 12, 13, 14, 15)) {
                    val saved = checkNotNull(locals.registers(instruction.offset)[4]) - 8
                    require(output + size <= saved || saved + 8 <= output) {
                        "String output overlaps a preserved register in its caller's frame"
                    }
                }
            }
            call.offset
        }
    }
}
