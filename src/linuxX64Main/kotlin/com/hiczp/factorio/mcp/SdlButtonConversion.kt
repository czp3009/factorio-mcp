package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.Immediate
import com.hiczp.factorio.mcp.X64Instructions.Operation

/** File-only native mouse payload evidence. InputState ABI and GUI delivery remain separate checks. */
internal data class SdlButtonConversion(
    val header: EventHeader,
    val admission: SdlButtonAdmission.Proof,
    val payload: IndexedTablePayload.Proof,
    val kinds: Map<SdlButtonAdmission.Transition, Long>,
) {
    companion object {
        fun resolve(image: ElfImage): SdlButtonConversion {
            val function = image.symbol("_ZN5Event15convertSDLEventERKNS_5StateERK9SDL_EventR12CompactDequeIS_Ef")
            val flow = X64ControlFlow.resolve(image, function)
            val table = GuardedByteTable.resolve(image, function).single()
            val admission = SdlButtonAdmission.analyze(flow, table)
            val grow = image.symbol("_ZN12CompactDequeI5EventE4growEj")
            val calls = flow.instructions.filter {
                it.operation == Operation.CALL &&
                        (it.destination as? Immediate)?.value?.plus(function.address) == grow.address
            }.map { it.offset }.toSet()
            val header = EventHeader.resolve(image)
            val payload = IndexedTablePayload.analyze(
                flow, table, header,
                SdlButtonAdmission.extents(table), calls
            )
            val kinds = SdlButtonAdmission.kinds(table, payload.kind)
            return SdlButtonConversion(header, admission, payload, kinds)
        }
    }
}
