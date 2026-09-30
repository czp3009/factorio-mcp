@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.linuxbridge.FmLinuxChatConfig

/** One selected executable's admission and owned-string action entries, with loaded evidence. */
internal class ChatMetadata private constructor(
    val admission: ChatAdmissionMetadata,
    val action: ChatActionCall,
    val string: ChatStringEntries,
    private val construct: ElfImage.Symbol,
    private val destroy: ElfImage.Symbol,
    private val submit: ElfImage.Symbol,
    val evidence: ElfEvidence,
) {
    fun verifyLoaded(image: ElfImage, process: ProcessHandle, bias: Long) {
        admission.verifyLoaded(image, process, bias)
        evidence.verify(image, bias, process::readMemory)
    }

    fun writeTo(output: FmLinuxChatConfig, bias: Long) {
        fun relocated(entry: ElfImage.Symbol): ULong {
            require(
                bias >= 0 && bias % 8 == 0L && entry.address > 0 && entry.size > 0 &&
                        entry.address <= Long.MAX_VALUE - entry.size - bias
            )
            return (entry.address + bias).toULong()
        }
        require(string.layout.size in 1..256 && action.size in 1..4096 && action.type in 0..65535)
        admission.layout.writeTo(output.admission, bias)
        output.constructString = relocated(string.construct)
        output.assignString = relocated(string.assign)
        output.destroyString = relocated(string.layout.destructor)
        output.constructAction = relocated(construct)
        output.destroyAction = relocated(destroy)
        output.submit = relocated(submit)
        output.stringSize = string.layout.size.toUInt()
        output.actionSize = action.size.toUInt()
        output.actionType = action.type.toUInt()
    }

    companion object {
        fun resolve(image: ElfImage): ChatMetadata {
            val admission = ChatAdmissionMetadata.resolve(image)
            val pointers = mutableMapOf<Long, Long>()
            val (resolved, readonly) = image.withReadonlyEvidence {
                image.withFunctionEvidence {
                    val action = ChatActionCall.resolve(image)
                    val type = ChatActionHeader.resolve(image, action.size)
                    val string = ChatStringEntries.resolve(image)
                    val rtti = ChatPayloadType.resolve(image, action.type)
                    val payload = ChatActionPayload.resolve(image, action.size, type, string.layout)
                    val indexed = ChatStringCopy.verify(image, action.size, payload, string.layout)
                    ChatConstructorFrame.verify(image, action.size, indexed + rtti.access)
                    ChatActionDestruction.verify(image, action, type, payload, string.layout)
                    ChatSubmitArguments.verify(
                        image,
                        admission.layout.player,
                        action.size,
                        type,
                        payload,
                        string.layout.size
                    )
                    pointers[rtti.slot] = rtti.typeInfo
                    pointers[rtti.typeInfo + 8] = rtti.typeName
                    ChatMetadata(
                        admission, action, string,
                        image.symbol("_ZN11InputActionC2E15InputActionTypeRKNSt7__cxx1112basic_stringIcSt11char_traitsIcESaIcEEE"),
                        image.symbol("_ZN11InputAction12destroyValueEv"),
                        image.symbol("_ZNK6Player15sendToListenersEO11InputAction"),
                        ElfEvidence(emptyList(), emptyList(), emptyMap(), emptyMap())
                    )
                }
            }
            val metadata = resolved.first
            return ChatMetadata(
                admission, metadata.action, metadata.string, metadata.construct, metadata.destroy,
                metadata.submit, ElfEvidence(resolved.second, readonly, pointers, emptyMap())
            )
        }
    }
}
