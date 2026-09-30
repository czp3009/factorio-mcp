package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.ConstructorValues.Argument
import com.hiczp.factorio.mcp.ConstructorValues.Constant
import com.hiczp.factorio.mcp.X64Instructions.*

/** Output placement from a complete native empty-string constructor, including a privately spilled pointer. */
internal object EmptyStringOutput {
    data class Proof(val argument: Int, val constructors: List<Long>, val callers: List<Long>)

    fun resolve(
        image: ElfImage, function: String, name: String, callerName: String, string: NativeStringLayout,
        debug: DwarfInlines = DwarfInlines(image), constructor: String = "basic_string"
    ): Proof {
        val method = image.symbol(function)
        val caller = image.symbol(callerName)
        val flow = X64ControlFlow.resolve(image, method)
        val ranges =
            debug.find(method, name, setOf(constructor)).filter { it.ranges.size == 1 }.map { it.ranges.single() }
                .map { DwarfRanges.Range(it.start - method.address, it.end - method.address) }
        val proof = analyze(flow, string, ranges)
        val calls = StringResultStorage.caller(
            X64ControlFlow.resolve(image, caller), method.address - caller.address,
            proof.argument, string.size
        )
        return proof.copy(callers = calls)
    }

    fun analyze(flow: X64ControlFlow, string: NativeStringLayout, constructors: List<DwarfRanges.Range>): Proof {
        require(
            string.size in 1..4096 && string.data in 0..string.size - 8 && string.length in 0..string.size - 8 &&
                    string.local in 0 until string.size && string.data + 8 <= string.local && string.length + 8 <= string.local &&
                    (string.data + 8 <= string.length || string.length + 8 <= string.data)
        )
        require(constructors.size in 1..64)
        val errors = mutableListOf<String>()
        val proofs = constructors.mapNotNull { range ->
            try {
                require(range.end - range.start in 1..256)
                val code = flow.instructions.filter { it.offset >= range.start && it.offset < range.end }
                require(
                    code.size in 3..32 && code.first().offset == range.start &&
                        code.last()
                            .let { it.offset + it.size } == range.end && code.all { it.offset in flow.reachable })
                require(code.all { it.operation in listOf(Operation.MOV, Operation.LEA, Operation.NOP) }) {
                    "Empty string constructor has additional operations"
                }
                for ((before, after) in code.zipWithNext()) require(flow.predecessors[after.offset] == setOf(before.offset)) {
                    "Empty string constructor has an additional entry"
                }
                // Other branches may have unrelated borrowed locals. Preserve every path to this constructor,
                // including backedges, while applying the existing strict private-frame model unchanged.
                val arguments = SysVArgumentFlow(flow)
                val values by lazy { ConstructorValues(flow.reaching(code.last().offset), emptyMap()) }
                fun register(site: Long, number: Int) = arguments.register(site, number)?.let {
                    Argument(it.argument, it.offset)
                } ?: values.register(site, number)

                fun address(site: Long, memory: Memory): Argument {
                    require(!memory.relative && memory.index == null && memory.base != null)
                    val base = register(site, memory.base) as? Argument
                        ?: error("Empty string output is not an original pointer")
                    require(memory.displacement in -4096..4096)
                    return base.copy(adjustment = base.adjustment + memory.displacement)
                }

                val stores = code.filter { it.destination is Memory }
                require(stores.size == 3 && stores.all { it.operation == Operation.MOV }) {
                    "Empty string constructor does not have exactly three initialization stores"
                }
                var argument: Int? = null
                val fields = mutableSetOf<Long>()
                for (store in stores) {
                    val memory = store.destination as Memory
                    val target = address(store.offset, memory)
                    if (argument == null) argument = target.register
                    require(target.register == argument && fields.add(target.adjustment)) {
                        "Empty string initialization changes output or repeats a field"
                    }
                    val source = when (val operand = store.source) {
                        is Register -> {
                            require(operand.width == memory.width)
                            register(store.offset, operand.number)
                        }

                        is Immediate -> Constant(operand.value)
                        else -> error("Empty string initialization copies unrelated memory")
                    }
                    when (target.adjustment) {
                        string.data -> require(memory.width == 8 && source == Argument(target.register, string.local))
                        string.length -> require(memory.width == 8 && source == Constant(0))
                        string.local -> require(memory.width == 1 && source == Constant(0))
                        else -> error("Empty string initialization writes outside its native fields")
                    }
                }
                require(fields == setOf(string.data, string.length, string.local))
                checkNotNull(argument) to range.start
            } catch (error: IllegalArgumentException) {
                errors += error.message.orEmpty()
                null
            } catch (error: IllegalStateException) {
                errors += error.message.orEmpty()
                null
            }
        }
        val argument = proofs.map { it.first }.distinct().singleOrNull()
            ?: error("No unique native empty-string output: ${errors.joinToString()}")
        return Proof(argument, proofs.map { it.second }.distinct().sorted(), emptyList())
    }
}
