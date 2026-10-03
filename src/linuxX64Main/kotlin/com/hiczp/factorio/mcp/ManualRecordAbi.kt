package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** The recorder remains an original game call; this verifies its actual name/member/explorer parameter uses. */
internal object ManualRecordAbi {
    private const val ASSIGN = "_ZNSt7__cxx1112basic_stringIcSt11char_traitsIcESaIcEE9_M_assignERKS4_"

    fun resolve(image: ElfImage, string: NativeStringLayout) {
        val consumer = image.symbol(NamedMemberRecord.CONSUMER)
        val assignment = image.symbol(ASSIGN)
        EhFrames(image).function(consumer)
        EhFrames(image).function(assignment)
        val flow = X64ControlFlow.resolve(image, consumer)
        val copies = flow.instructions.filter { it.operation == Operation.CALL }.mapNotNull {
            (it.destination as? Immediate)?.value?.takeIf { target ->
                image.importedFunction(consumer.address + target) == "memcpy"
            }
        }.distinct()
        require(copies.size == 1) { "Manual recorder has no unique imported forward copy" }
        analyze(flow, assignment.address - consumer.address, copies.single(), string.size)
        val assignFlow = X64ControlFlow.resolve(image, assignment)
        val arguments = SysVArgumentFlow(assignFlow)
        val reads = assignFlow.instructions.filter { it.offset in assignFlow.reachable }.mapNotNull { instruction ->
            if (instruction.operation != Operation.MOV) null else arguments.source(instruction.offset)
        }
        require(SysVArgumentFlow.Read(SysVArgumentFlow.Reference(6, string.length), 8) in reads &&
                SysVArgumentFlow.Read(SysVArgumentFlow.Reference(7, string.data), 8) in reads &&
                SysVArgumentFlow.Read(SysVArgumentFlow.Reference(7, string.local), 8) in reads) {
            "String assignment does not use the verified object-reference ABI"
        }
    }

    fun analyze(flow: X64ControlFlow, assign: Long, memcpy: Long, stringSize: Long) {
        require(stringSize in 8..256)
        val origins = PrivateArgumentOrigins(flow, memcpy, assign)
        val copy = flow.instructions.single { it.offset in flow.reachable &&
                it.operation == Operation.CALL && it.destination == Immediate(assign) }
        require(origins.register(copy.offset, 6) == SysVArgumentFlow.Reference(6)) {
            "Recorder name is not copied from its original string argument"
        }
        val name = origins.register(copy.offset, 7)
            ?: error("Recorder name destination loses its explorer receiver")
        require(name.argument == 8 && name.offset in 0..65536 - stringSize)
        val stores = flow.instructions.filter { it.offset in flow.reachable && it.operation == Operation.MOV &&
                it.destination is Memory && it.destination.width == 8 && it.source is Register && it.source.width == 8 }
            .mapNotNull { instruction ->
                val source = runCatching { origins.register(instruction.offset, (instruction.source as Register).number) }.getOrNull()
                if (source?.offset != 0L || source.argument !in listOf(7, 2, 1)) return@mapNotNull null
                val destination = origins.address(instruction.offset, instruction.destination as Memory)
                if (destination?.argument != 8) return@mapNotNull null
                origins.validateAliases(instruction.offset)
                source.argument to destination.offset
            }.groupBy({ it.first }, { it.second })
        val fields = listOf(7, 2, 1).map { argument ->
            stores[argument]?.distinct()?.singleOrNull() ?: error("Recorder has no unique descriptor for argument $argument")
        }
        require(fields.all { it in 0..65528 && (it + 8 <= name.offset || name.offset + stringSize <= it) } &&
                fields.sorted().zipWithNext().all { (first, second) -> first + 8 <= second }) {
            "Recorder descriptors overlap each other or their native name"
        }
    }
}
