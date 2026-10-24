package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/**
 * Connects every write to selected GLAD storage to the exact name passed to SDL's verified lookup
 * API.
 */
internal object GlLoaderBinding {
    fun resolve(image: ElfImage, names: List<String>, lookup: SdlDynamicApi): Map<String, Long> {
        require(names.isNotEmpty() && names.size <= 32 && names.distinct().size == names.size)
        val loader = image.symbol("gladLoadGLLoader")
        require(loader.size in 1..8192 * 15L) { "GL loader exceeds complete-function bounds" }
        EhFrames(image).function(loader)
        val flow = X64ControlFlow(X64Instructions(image.functionBytes(loader, 8192 * 15)).all(8192))
        return names.associateWith { name ->
            require(name.startsWith("gl") && name.length in 3..64)
            val slot = image.symbol("glad_$name")
            require(slot.type == 1 && slot.size == 8L && slot.address % 8 == 0L)
            analyze(flow, loader.address, lookup.slot, slot.address, name) {
                require(
                    image.sections.any { section ->
                        section.flags and 3L == 2L &&
                            it >= section.address &&
                            name.length + 1L <= section.size &&
                            it - section.address <= section.size - name.length - 1
                    }
                ) {
                    "GL lookup name is not immutable ELF data"
                }
                image.virtualBytes(it, name.length + 1L).string(0, name.length + 1)
            }
            slot.address
        }
    }

    fun analyze(
        flow: X64ControlFlow,
        address: Long,
        lookup: Long,
        storage: Long,
        name: String,
        readName: (Long) -> String,
    ) {
        require(address > 0 && storage > 0 && storage <= Long.MAX_VALUE - 8)
        val calls = SdlDynamicApi.calls(flow, address, lookup).map { it.offset }.toSet()
        val returned = ReturnedPointerOrigins(flow)
        val definitions = ScalarExpression(flow)
        fun nameAt(site: Long, number: Int, depth: Int = 0): Long {
            require(depth < 16)
            val definition = definitions.definition(site, number)
            require(definition.destination == Register(number, 8))
            return when (definition.operation) {
                Operation.MOV -> {
                    val source =
                        definition.source as? Register
                            ?: error("Lookup name is not an immutable address")
                    require(source.width == 8)
                    nameAt(definition.offset, source.number, depth + 1)
                }
                Operation.LEA ->
                    SdlDynamicApi.global(definition, definition.source as Memory, address)
                else -> error("Lookup name has an unsupported definition")
            }
        }
        val stores =
            flow.instructions.filter { instruction ->
                val memory = instruction.destination as? Memory ?: return@filter false
                if (
                    !memory.relative ||
                        instruction.operation in
                            listOf(Operation.CMP, Operation.TEST, Operation.CALL, Operation.JMP)
                )
                    return@filter false
                val target = SdlDynamicApi.global(instruction, memory, address)
                target < storage + 8 && target + memory.width > storage
            }
        require(stores.size in 1..16) { "Missing or excessive GL loader assignments" }
        for (store in stores) {
            val target = store.destination as Memory
            val source =
                store.source as? Register ?: error("GL storage does not receive a lookup result")
            require(
                store.offset in flow.reachable &&
                    store.operation == Operation.MOV &&
                    source.width == 8 &&
                    target.width == 8 &&
                    SdlDynamicApi.global(store, target, address) == storage
            )
            val result =
                returned.register(store.offset, source.number)
                    ?: error("GL storage has ambiguous lookup provenance")
            require(result.call in calls && result.members.isEmpty() && result.adjustment == 0L)
            require(readName(nameAt(result.call, 7)) == name) {
                "GL storage uses a different SDK name"
            }
        }
    }
}
