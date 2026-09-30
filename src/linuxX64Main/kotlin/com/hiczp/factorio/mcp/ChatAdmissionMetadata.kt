package com.hiczp.factorio.mcp

/** Admission layout with matching loaded code, lookup data and primary RTTI evidence. */
internal class ChatAdmissionMetadata private constructor(val layout: ChatAdmissionLayout, val evidence: ElfEvidence) {
    fun verifyLoaded(image: ElfImage, process: ProcessHandle, bias: Long) =
        evidence.verify(image, bias, process::readMemory)

    companion object {
        fun resolve(image: ElfImage): ChatAdmissionMetadata {
            val pointers = mutableMapOf<Long, Long>()
            val scalars = mutableMapOf<Long, Long>()
            val (resolved, readonly) = image.withReadonlyEvidence {
                image.withFunctionEvidence {
                    val layout = ChatAdmissionLayout.resolve(image)
                    val reader = ElfPointers(image)
                    for (type in listOf("10LuaContext", "6Player", "8GameView", "17GameActionHandler")) {
                        val identity = ItaniumType.resolve(image, type)
                        scalars[identity.addressPoint - 16] = 0
                        pointers[identity.addressPoint - 8] = identity.typeInfo
                        pointers[identity.typeInfo + 8] = reader.words(identity.typeInfo + 8, 1).single().pointer()
                    }
                    // World layout uses this slot to identify Scenario's context-owned members.
                    val destructor = ItaniumVtable.resolve(image, "_ZTV10LuaContext")
                        .method(image, "_ZN10LuaContextD0Ev")
                    pointers[destructor.entryAddress] = destructor.function.address
                    layout
                }
            }
            return ChatAdmissionMetadata(resolved.first, ElfEvidence(resolved.second, readonly, pointers, scalars))
        }
    }
}
