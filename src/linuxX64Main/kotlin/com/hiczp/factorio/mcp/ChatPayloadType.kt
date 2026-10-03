package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*

/** The constructor's indexed RTTI selection, derived from the original action argument. */
internal data class ChatPayloadType(val slot: Long, val typeInfo: Long, val typeName: Long, val access: Long) {
    companion object {
        fun resolve(image: ElfImage, kind: Int): ChatPayloadType {
            val entry =
                image.symbol("_ZN11InputActionC2E15InputActionTypeRKNSt7__cxx1112basic_stringIcSt11char_traitsIcESaIcEEE")
            val encoded = "NSt7__cxx1112basic_stringIcSt11char_traitsIcESaIcEEE"
            val type = image.symbol("_ZTI$encoded")
            val name = image.symbol("_ZTS$encoded")
            require(
                type.type == 1 && type.size >= 16 && name.type == 1 && name.size in 2..4096 &&
                        image.virtualBytes(name.address, name.size).string(0, name.size.toInt()) == encoded
            )
            EhFrames(image).function(entry)
            val pointers = image.pointers
            return analyze(image.functionBytes(entry, 4096), entry.address, kind, type.address, name.address) {
                pointers.words(it, 1).single().pointer()
            }
        }

        fun analyze(
            bytes: BinaryView, address: Long, kind: Int, typeInfo: Long, typeName: Long,
            pointer: (Long) -> Long
        ): ChatPayloadType {
            require(
                address >= 0 && address <= Long.MAX_VALUE - bytes.size && kind in 0..65535 &&
                        typeInfo in 1..Long.MAX_VALUE - 16 && typeName > 0
            )
            val decoded = X64Instructions(bytes).all(1024)
            val prefix = decoded.takeWhile { it.operation != Operation.CALL }
            require(prefix.none {
                it.operation in listOf(Operation.JMP, Operation.JCC, Operation.RET) ||
                        it.destination is Register && it.destination.number == 6
            }) {
                "Constructor changes or branches before capturing its scalar argument"
            }
            val captures = prefix.filter {
                it.operation == Operation.MOV && it.source == Register(6, 4) &&
                        (it.destination as? Register)?.width == 4
            }.map { it.offset }.toSet()
            require(captures.isNotEmpty())
            val flow = X64ControlFlow(decoded.map {
                if (it.offset in captures) it.copy(source = Immediate(kind.toLong())) else it
            })
            val scalars = ScalarExpression(flow)
            fun relative(instruction: X64Instructions.Instruction, memory: Memory): Long {
                val next = address + instruction.offset + instruction.size
                require(
                    memory.relative && memory.base == null && memory.index == null &&
                            memory.displacement >= -next && memory.displacement <= Long.MAX_VALUE - next
                )
                return next + memory.displacement
            }

            val candidates = mutableListOf<Pair<Long, Long>>()
            for ((position, instruction) in decoded.withIndex()) {
                if (instruction.offset !in flow.reachable || instruction.operation != Operation.MOV) continue
                val source = instruction.source as? Memory ?: continue
                val target = instruction.destination as? Register ?: continue
                if (source.relative || source.base == null || source.index == null || source.scale != 8 ||
                    source.width != 8 || target.width != 8
                ) continue
                val base = scalars.definition(instruction.offset, source.base)
                val location = base.source as? Memory ?: continue
                if (base.operation != Operation.LEA || !location.relative) continue
                require(location.base == null && location.index == null && source.displacement == 0L)
                val indexValue = scalars.before(instruction.offset, Register(source.index, 4))
                require(ScalarExpression.inputs(indexValue).isEmpty())
                val index = ScalarExpression.evaluate(indexValue) { error("External payload type index") }
                require(index in 0..65535)
                val table = relative(base, location)
                require(table > 0 && table % 8 == 0L && table <= Long.MAX_VALUE - index * 8 - 8)
                val nameRead = decoded.getOrNull(position + 1) ?: error("Missing RTTI name load")
                val nameRegister = nameRead.destination as? Register ?: error("Invalid RTTI name destination")
                val member = nameRead.source as? Memory ?: error("Missing RTTI name member")
                require(
                    nameRead.operation == Operation.MOV && nameRegister.width == 8 && member.width == 8 &&
                            member.base == target.number && !member.relative && member.index == null && member.displacement == 8L
                )
                val expected = decoded.getOrNull(position + 2) ?: error("Missing expected RTTI name")
                val expectedRegister = expected.destination as? Register ?: error("Invalid expected RTTI register")
                val expectedAddress = expected.source as? Memory ?: error("Missing expected RTTI address")
                require(
                    expected.operation == Operation.LEA && expectedRegister.width == 8 &&
                            expectedRegister != nameRegister && expectedAddress.relative &&
                            relative(expected, expectedAddress) == typeName
                )
                val comparison = decoded.getOrNull(position + 3) ?: error("Missing RTTI comparison")
                require(
                    comparison.operation == Operation.CMP &&
                            setOf(comparison.destination, comparison.source) == setOf(nameRegister, expectedRegister)
                )
                val branch = decoded.getOrNull(position + 4) ?: error("Missing RTTI equality branch")
                require(
                    branch.operation == Operation.JCC && branch.condition == 4 &&
                            branch.destination != Immediate(branch.offset + branch.size) &&
                            (branch.destination as? Immediate)?.value in flow.body
                )
                candidates += (table + index * 8) to instruction.offset
            }
            val (slot, access) = candidates.singleOrNull()
                ?: error("Constructor payload RTTI lookup is absent or ambiguous")
            require(pointer(slot) == typeInfo && pointer(typeInfo + 8) == typeName) {
                "Selected chat action payload is not the native string RTTI"
            }
            return ChatPayloadType(slot, typeInfo, typeName, access)
        }
    }
}
