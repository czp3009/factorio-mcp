package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.Immediate
import com.hiczp.factorio.mcp.X64Instructions.Operation

/** Establishes normal non-return through decoded control flow and matching DWARF no-return declarations. */
internal class SysVNoReturn(private val image: ElfImage, declarationNames: Set<String>) {
    private val declared = DwarfInfo(image).noReturnFunctions(declarationNames)
    private val results = mutableMapOf<Long, Boolean>()
    private val active = mutableSetOf<Long>()
    private val unsupported = linkedMapOf<Long, String>()
    private var examined = 0

    fun resolve(name: String): Long {
        val symbol = image.symbol(name)
        require(prove(symbol.address)) {
            "Function has no complete no-return proof: $name; declarations=$declared; unsupported=" +
                    unsupported.entries.toList().takeLast(8)
                        .joinToString { (address, reason) -> "0x${address.toString(16)}: $reason" }
        }
        return symbol.address
    }

    private fun prove(address: Long): Boolean {
        results[address]?.let { return it }
        if (active.size >= 8 || examined >= 128 || !active.add(address)) return false
        examined++
        val result = try {
            val imported = image.importedFunction(address)
            if (imported != null) imported in declared else {
                val symbols =
                    image.symbols().filter { it.address == address && it.type == 2 && it.size > 0 }.distinct().toList()
                if (symbols.isEmpty() || symbols.map { it.size }.distinct().size != 1) false else {
                    val symbol = symbols.first()
                    val proven = symbols.any { it.name in declared } || (symbol.size in 1..8192 &&
                            image.functionBytes(symbol, 8192).let { code ->
                                X64Instructions(code).all(2048).none { it.operation == Operation.RET } &&
                                        analyze(code, address, ::prove)
                            })
                    if (proven) EhFrames(image).function(symbol)
                    proven
                }
            }
        } catch (error: IllegalArgumentException) {
            unsupported[address] = error.message.orEmpty().take(160)
            false // Unsupported metadata means the callee may return; it never removes an edge.
        } catch (error: IllegalStateException) {
            unsupported[address] = error.message.orEmpty().take(160)
            false
        } finally {
            active.remove(address)
        }
        results[address] = result
        return result
    }

    companion object {
        fun analyze(bytes: BinaryView, address: Long, calleeDoesNotReturn: (Long) -> Boolean): Boolean {
            require(bytes.size in 1..8192 && address >= 0 && address <= Long.MAX_VALUE - bytes.size)
            val instructions = X64Instructions(bytes).all(2048).associateBy { it.offset }
            val pending = ArrayDeque<Long>()
            val visited = mutableSetOf<Long>()
            pending.add(0)
            while (pending.isNotEmpty()) {
                val offset = pending.removeFirst()
                if (!visited.add(offset)) continue
                val instruction = instructions[offset] ?: return false
                val relative = (instruction.destination as? Immediate)?.value
                fun exits(): Boolean =
                    relative != null && relative >= -address && relative <= Long.MAX_VALUE - address &&
                            calleeDoesNotReturn(address + relative)

                val next = instruction.offset + instruction.size
                when (instruction.operation) {
                    Operation.RET -> return false
                    Operation.CALL -> if (!exits()) pending.add(next)
                    Operation.JMP, Operation.JCC -> {
                        if (relative == null) return false
                        if (relative in 0 until bytes.size) pending.add(relative) else if (!exits()) return false
                        if (instruction.operation == Operation.JCC) pending.add(next)
                    }

                    else -> pending.add(next)
                }
            }
            return true
        }
    }
}
