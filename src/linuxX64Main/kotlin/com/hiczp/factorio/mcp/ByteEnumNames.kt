package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/**
 * Exhaustive static byte projections into an original native length-delimited string submission.
 */
internal object ByteEnumNames {
    fun analyze(
        flow: X64ControlFlow,
        source: Long,
        push: Long,
        string: NativeStringLayout,
    ): Map<Int, String> {
        val start = flow.body.getValue(source).let { it.offset + it.size }
        require(start in flow.reachable && flow.body[push]?.operation == Operation.CALL)
        fun entryWithout(omit: Long, check: (Instruction) -> Boolean) {
            val pending = ArrayDeque<Long>()
            pending.add(0)
            val seen = mutableSetOf<Long>()
            while (pending.isNotEmpty()) {
                val site = pending.removeFirst()
                if (site == omit || !seen.add(site)) continue
                require(seen.size <= 8192 && check(flow.body.getValue(site))) {
                    "Enum reader can bypass its original byte observation or native string submission"
                }
                pending.addAll(flow.successors.getValue(site))
            }
        }
        entryWithout(source) { it.offset != push }
        entryWithout(push) { it.operation != Operation.RET }
        val failures = mutableSetOf<Long>()
        fun verifyFailure(start: Long) {
            if (!failures.add(start)) return
            val pending = ArrayDeque<Long>()
            pending.add(start)
            val seen = mutableSetOf<Long>()
            while (pending.isNotEmpty()) {
                val site = pending.removeFirst()
                if (!seen.add(site)) continue
                require(
                    seen.size <= 8192 &&
                        site != push &&
                        flow.body.getValue(site).operation != Operation.RET
                ) {
                    "An unsupported enum branch can submit a string or return normally"
                }
                pending.addAll(flow.successors.getValue(site))
            }
        }
        val names = mutableMapOf<Int, String>()
        for (value in 0..255) {
            val specialized = flow.withByteValue(source, value)
            val scalars = ScalarExpression(specialized)
            val choices = mutableMapOf<Long, Long>()
            val visited = mutableSetOf<Long>()
            var site = start
            var submitted = true
            while (site != push) {
                require(visited.size < 1024 && visited.add(site)) {
                    "Enum projection loops or exceeds its bounded path"
                }
                val instruction = flow.body.getValue(site)
                if (instruction.operation == Operation.CALL) {
                    verifyFailure(site)
                    submitted = false
                    break
                }
                require(instruction.operation != Operation.RET) {
                    "Enum projection returns without its string"
                }
                site =
                    when (instruction.operation) {
                        Operation.JCC -> {
                            val taken =
                                ScalarExpression.evaluate(scalars.branch(site)) {
                                    error("Enum projection depends on another input observation")
                                } != 0L
                            val target =
                                if (taken)
                                    (instruction.destination as? Immediate)?.value
                                        ?: error("Indirect enum branch")
                                else site + instruction.size
                            choices[site] = target
                            target
                        }
                        Operation.JMP ->
                            (instruction.destination as? Immediate)?.value
                                ?: error("Indirect enum projection")
                        else -> site + instruction.size
                    }
                require(site in flow.successors.getValue(instruction.offset)) {
                    "Enum projection leaves its original control flow"
                }
            }
            if (!submitted) continue
            val selected = flow.following(choices)
            val literal = LocalStringArgument(selected, string)
            val length = literal.constant(push, 2)
            require(length in 1..128)
            val data = SysVLocalArgument(selected).argument(push, 6, length.toInt() + 1)
            val text = literal.textAt(push, data - string.local)
            require(text.encodeToByteArray().size.toLong() == length) {
                "Native string submission changes its complete literal length"
            }
            names[value] = text
        }
        require(names.isNotEmpty() && names.size <= 64) {
            "Native enum has no bounded literal name projection"
        }
        return names
    }
}
