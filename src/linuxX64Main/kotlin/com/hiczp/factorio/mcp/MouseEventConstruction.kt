package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.Immediate
import com.hiczp.factorio.mcp.X64Instructions.Operation

/** Native construction defaults. Dispatch ABI, input state and widget lifetime are checked separately. */
internal data class MouseEventConstruction(
    val down: Template,
    val up: Template,
    val click: Template,
    val leave: Template,
    val literals: List<LocalAggregate.LiteralRange>,
) {
    data class Template(val type: Long, val zeroBytes: List<Long>, val previous: Long? = null)

    companion object {
        fun resolve(image: ElfImage, fields: MouseEventFields): MouseEventConstruction {
            val function = image.symbol("_ZN4agui3Gui5logicEb")
            val flow = X64ControlFlow.resolve(image, function)
            val constructors = DwarfInlines(image).find(function, "logic", setOf("MouseEvent"))
            val aggregate = LocalAggregate.resolve(image, function)
            val literals = mutableListOf<LocalAggregate.LiteralRange>()
            fun template(entry: String): Template {
                val target = image.symbol("_ZN4agui6Widget${entry}ERKNS_10MouseEventE")
                val calls = flow.instructions.filter {
                    it.operation == Operation.CALL &&
                            (it.destination as? Immediate)?.value?.plus(function.address) == target.address
                }
                require(calls.isNotEmpty())
                return calls.map { call ->
                    // The nearest constructor must finish before this call. Never borrow an earlier template
                    // when the actual construction has an unsupported shape.
                    val constructor = constructors.filter { inline ->
                        inline.ranges.maxOf { it.end } <= function.address + call.offset
                    }.maxByOrNull { it.ranges.maxOf { range -> range.end } }
                        ?: error("Mouse dispatch has no preceding inline constructor")
                    val proof = aggregate.constructedAt(call.offset, fields.extent, constructor.ranges.map {
                        DwarfRanges.Range(it.start - function.address, it.end - function.address)
                    })
                    literals += proof.literals
                    crossCheck(fields, proof)
                }.distinct().singleOrNull() ?: error("Mouse event construction sites disagree")
            }

            val down = template("17dispatchMouseDown")
            val up = template("15dispatchMouseUp")
            val click = template("13dispatchClick")
            val leave = template("18dispatchMouseLeave")
            require(down.zeroBytes == up.zeroBytes && up.zeroBytes == click.zeroBytes && click.zeroBytes == leave.zeroBytes) {
                "Mouse event construction defaults disagree"
            }
            return MouseEventConstruction(down, up, click, leave, literals.distinct())
        }

        fun crossCheck(fields: MouseEventFields, proof: LocalAggregate.Proof, previous: Long? = null): Template {
            val members = listOf(
                fields.x to 4, fields.y to 4, fields.wheel to 4, fields.button to 2,
                fields.type to 4, fields.time to 8, fields.alt to 1, fields.control to 1, fields.shift to 1,
                fields.source to 8
            )
            val identified = members.flatMap { (offset, width) -> (offset until offset + width).toList() }.toSet()
            val previousBytes = previous?.let {
                require(it >= 0 && it <= fields.extent - 8 && (it until it + 8).none(identified::contains)) {
                    "Previous-widget field overlaps an identified event field"
                }
                (it until it + 8).toSet()
            }.orEmpty()
            val known = identified + previousBytes
            fun bound(offset: Long, width: Int) {
                require(width in listOf(1, 2, 4, 8, 16) && offset >= 0 && offset <= fields.extent - width)
            }

            val written = mutableSetOf<Long>()
            for (field in proof.written) {
                bound(field.offset, field.width)
                for (byte in field.offset until field.offset + field.width) require(written.add(byte))
            }
            require(written.containsAll(known)) { "Mouse construction omits an identified field" }
            require(proof.receiverFields == listOf(fields.source)) { "Mouse event source differs from its receiver" }
            val constants = mutableMapOf<Long, Int>()
            for (constant in proof.constants) {
                bound(constant.offset, constant.width)
                require(constant.width <= 8)
                for (byte in 0 until constant.width) {
                    val offset = constant.offset + byte
                    require(
                        offset in written && constants.put(
                            offset,
                            (constant.value ushr (byte * 8) and 255).toInt()
                        ) == null
                    )
                }
            }
            val type = (0..3).fold(0L) { value, byte ->
                value or (constants.getValue(fields.type + byte).toLong() shl (byte * 8))
            }
            val extra = written - known
            require(extra.all { constants[it] == 0 }) { "Mouse construction has an unknown dynamic or nonzero field" }
            return Template(type, extra.sorted(), previous)
        }
    }
}
