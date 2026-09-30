@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test

/** Explicit file-only proof. Does not submit chat or install hooks. */
class ChatActionAcceptanceTest {
    @Test
    fun resolvesNativeConsoleActionConstructionAndSubmission() {
        val path = checkNotNull(getenv("FACTORIO_MCP_TEST_ELF")).toKString()
        MappedBinary(path).use { file ->
            val image = ElfImage(file.view)
            val action = ChatActionCall.resolve(image)
            println("Verified console callback action: $action")
            val rtti = ChatPayloadType.resolve(image, action.type)
            println("Verified constructor payload RTTI: $rtti")
            val type = ChatActionHeader.resolve(image, action.size)
            println("Verified action discriminator field: $type")
            println("Verified native chat submission gate: ${ChatSubmissionGate.resolve(image)}")
            val string = ChatStringEntries.resolve(image)
            println("Verified owned chat string entries: $string")
            val payload = ChatActionPayload.resolve(image, action.size, type, string.layout)
            println("Verified action source and inline payload: $payload")
            val indexed = ChatStringCopy.verify(image, action.size, payload, string.layout)
            println("Verified bounded chat string copy paths")
            ChatConstructorFrame.verify(image, action.size, indexed + rtti.access)
            println("Verified normal chat constructor frame restoration")
            ChatActionDestruction.verify(image, action, type, payload, string.layout)
            println("Verified chat action inline and heap destruction")
        }
    }
}
