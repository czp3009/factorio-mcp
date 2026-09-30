package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.SysVReceiverFlow.Pointer
import com.hiczp.factorio.mcp.SysVReceiverFlow.Receiver
import com.hiczp.factorio.mcp.X64Instructions.*

/** Original nonnull child dispatched through its primary table before the owner clears that member.
 * The complete caller CFG must prevent any later path from reentering the analyzed prefix. Calls within the
 * game retain their native behavior; this proves the lifetime notification boundary, not their transitive ABIs.
 */
internal object VirtualMemberRetirement {
    data class Proof(val member: Long, val call: Long, val boundary: Long)

    fun resolve(image: ElfImage, caller: String, size: Long, member: Long, method: ItaniumVtable.Method): Proof {
        val function = image.symbol(caller)
        EhFrames(image).function(function)
        return analyze(image.functionBytes(function, 8192), function.address, size, member, method.slot)
    }

    fun analyze(bytes: BinaryView, address: Long, size: Long, member: Long, slot: Int): Proof {
        require(
            bytes.size in 1..8192 && size in 8..(64 * 1024 * 1024) && member in 0..size - 8 &&
                    member % 8 == 0L && slot in 0..4095
        )
        // XADD is admitted only for CFG discovery outside the proven prefix. No argument analysis interprets it.
        val instructions = X64Instructions(bytes, allowAtomicExchangeAdd = true).all(2048)
        val complete = X64ControlFlow(instructions)
        val pairs = instructions.zipWithNext().filter { (call, clear) ->
            val target = call.destination as? Memory
            call.offset in complete.reachable && call.operation == Operation.CALL && target != null &&
                    target.width == 8 && !target.relative && target.index == null && target.displacement == slot * 8L &&
                    clear.operation == Operation.MOV && clear.destination is Memory && clear.source == Immediate(0)
        }
        val candidates = pairs.mapNotNull { (call, clear) ->
            val boundary = clear.offset + clear.size
            val guards = instructions.zipWithNext().filter { (test, branch) ->
                val register = test.destination as? Register
                test.offset < call.offset && test.operation == Operation.TEST && register?.width == 8 &&
                        test.source == register && branch.operation == Operation.JCC && branch.condition == 4 &&
                        branch.destination == Immediate(boundary)
            }
            if (guards.isEmpty()) return@mapNotNull null
            // Edges into/out of this prefix are exhaustively discovered, even when the later destructor uses
            // atomics unrelated to the member. No unsupported later branch can hide an entry into the prefix.
            val prefix = complete.reachable.filter { it < boundary }.toSet()
            require(complete.successors.all { (from, targets) ->
                from !in complete.reachable ||
                        targets.all { target -> from < boundary || target >= boundary }
            }) { "Retirement prefix has an incoming edge from its suffix" }
            require(prefix.all { site ->
                val instruction = complete.body.getValue(site)
                val successors = complete.successors.getValue(site)
                instruction.operation != Operation.RET && successors.isNotEmpty() &&
                        successors.all { it < boundary || it == boundary }
            }) { "Retirement prefix has another normal exit" }
            require(complete.predecessors[clear.offset] == setOf(call.offset)) {
                "Owner can clear its child without dispatching retirement"
            }
            val flow = SysVReceiverFlow(bytes.slice(0, boundary), address, size)
            val registers = flow.call(call.offset)
            val child = registers[7] as? Pointer ?: error("Retirement does not use a pointer member")
            require(child.base == Receiver() && child.offset == member)
            val target = call.destination as Memory
            val table = target.base?.let { registers[it] } as? Pointer
            require(table?.base == child && table.offset == 0L) { "Retirement uses another object's vtable" }
            val store = clear.destination as Memory
            val owner = store.base?.let { flow.before(clear.offset)[it] } as? Receiver
            require(
                store.width == 8 && !store.relative && store.index == null && owner != null &&
                        owner.adjustment + store.displacement == member
            ) { "Retirement does not clear its owner member" }
            val matching = guards.filter { (test, branch) ->
                val register = test.destination as Register
                flow.before(test.offset)[register.number] == child &&
                        flow.requiresEdge(call.offset, branch.offset, branch.offset + branch.size)
            }
            val guard = matching.singleOrNull()?.second ?: error("Retirement lacks a unique original-child null guard")
            require(
                complete.predecessors[boundary].orEmpty().filter { it < boundary }.toSet() ==
                        setOf(guard.offset, clear.offset)
            ) { "A nonnull child can leave the prefix without retirement" }
            Proof(member, call.offset, boundary)
        }
        return candidates.singleOrNull() ?: error("Owner has no unique guarded member retirement")
    }
}
