@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

/** Matches the Windows admission rule: an active main-loop step, including optimized inline code. */
internal class FrontendReadiness private constructor(
    private val process: ProcessHandle,
    private val ranges: List<LongRange>,
) {
    fun requireRunning(trace: ThreadTrace) {
        check(ProcessStack(process).contains(trace.registers().general, ranges)) {
            "Factorio frontend is not initialized; retry after initial loading"
        }
    }

    companion object {
        private const val STEP = "_ZN8MainLoop12mainLoopStepER12WorkerThreadNS_9HeavyModeE"
        private const val RUN = "_ZN8MainLoop3runERKN10Filesystem4PathES3_bbSt8functionIFvvEES3_NS_9HeavyModeE"

        internal fun resolveRanges(image: ElfImage): List<DwarfRanges.Range> {
            val ranges = mutableListOf<DwarfRanges.Range>()
            image.findSymbol(STEP)?.let { function ->
                EhFrames(image).function(function)
                require(function.size in 1..131072)
                image.functionBytes(function, function.size.toInt())
                ranges += DwarfRanges.Range(function.address, function.address + function.size)
            }
            val owner = image.symbol(RUN)
            require(owner.size in 1..131072)
            image.functionBytes(owner, owner.size.toInt())
            val info = DwarfInfo(image)
            val inlines = DwarfInlines(image).all(owner, "run").filter { it.name == "mainLoopStep" }
            require(inlines.map { it.origin }.distinct().size <= 1) { "Ambiguous main-loop inline origin" }
            for (inline in inlines) {
                val origin = info.entry(inline.origin)
                val linkage = info.attribute(origin, 0x6e) ?: info.attribute(origin, 0x2007)
                // Line-table debug builds retain the inline origin/name without an external linker
                // identity. Its unique origin and enclosing exact run function still identify it;
                // no optimized argument ABI is inferred or invoked here.
                require(linkage == null || (linkage as? DwarfInfo.Value.Text)?.value == STEP) {
                    "Main-loop inline origin has a different linker identity"
                }
                ranges += inline.ranges
            }
            require(ranges.isNotEmpty() && ranges.size <= 4096) { "Main-loop step has no bounded debug ranges" }
            return ranges.distinct().sortedBy { it.start }
        }

        fun resolve(process: ProcessHandle, bias: Long): FrontendReadiness =
            process.withExecutable { image ->
                val (ranges, evidence) = image.withFunctionEvidence { resolveRanges(image) }
                ElfEvidence(evidence, emptyList(), emptyMap(), emptyMap())
                    .verify(image, bias, process::readMemory)
                FrontendReadiness(process, ranges.map {
                    require(bias >= 0 && it.start > 0 && it.end > it.start && it.end <= Long.MAX_VALUE - bias)
                    (it.start + bias) until (it.end + bias)
                })
            }
    }
}
