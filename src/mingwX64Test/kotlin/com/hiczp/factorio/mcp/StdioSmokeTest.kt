@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlinx.cinterop.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import platform.posix.getenv
import platform.posix.memset
import platform.windows.*
import kotlin.test.Test
import kotlin.test.assertEquals

/** Launches only a disposable MCP child; never controls the game process. */
class StdioSmokeTest {
    @Test
    fun invalidStartupReturnsFailure() = runBlocking {
        val executable =
            getenv("FACTORIO_MCP_TEST_EXECUTABLE")?.toKString()?.takeIf { it.isNotBlank() }
                ?: return@runBlocking
        memScoped {
            val startup = alloc<STARTUPINFOW>()
            memset(startup.ptr, 0, sizeOf<STARTUPINFOW>().toULong())
            startup.cb = sizeOf<STARTUPINFOW>().toUInt()
            val child = alloc<PROCESS_INFORMATION>()
            check(
                CreateProcessW(
                    executable,
                    "\"$executable\" --no-http --no-stdio".wcstr.ptr,
                    null,
                    null,
                    0,
                    CREATE_NO_WINDOW.toUInt(),
                    null,
                    null,
                    startup.ptr,
                    child.ptr,
                ) != 0
            )
            CloseHandle(child.hThread)
            try {
                withTimeout(10_000) {
                    while (WaitForSingleObject(child.hProcess, 0u) == WAIT_TIMEOUT.toUInt()) delay(
                        10
                    )
                }
                val code = alloc<UIntVar>()
                check(GetExitCodeProcess(child.hProcess, code.ptr) != 0)
                assertEquals(1u, code.value)
            } finally {
                if (WaitForSingleObject(child.hProcess, 0u) == WAIT_TIMEOUT.toUInt())
                    TerminateProcess(child.hProcess, 1u)
                CloseHandle(child.hProcess)
            }
        }
    }

    @Test
    fun protocolOutputAndStdinEof() = runBlocking {
        val executable =
            getenv("FACTORIO_MCP_TEST_EXECUTABLE")?.toKString()?.takeIf { it.isNotBlank() }
                ?: run {
                    println("Stdio smoke skipped: set FACTORIO_MCP_TEST_EXECUTABLE")
                    return@runBlocking
                }
        println("Running stdio initialization and EOF smoke")
        memScoped {
            val security = alloc<SECURITY_ATTRIBUTES>()
            memset(security.ptr, 0, sizeOf<SECURITY_ATTRIBUTES>().toULong())
            security.nLength = sizeOf<SECURITY_ATTRIBUTES>().toUInt()
            security.bInheritHandle = 1
            val inputRead = alloc<HANDLEVar>()
            val inputWrite = alloc<HANDLEVar>()
            val outputRead = alloc<HANDLEVar>()
            val outputWrite = alloc<HANDLEVar>()
            check(CreatePipe(inputRead.ptr, inputWrite.ptr, security.ptr, 0u) != 0)
            try {
                check(CreatePipe(outputRead.ptr, outputWrite.ptr, security.ptr, 0u) != 0)
                try {
                    check(
                        SetHandleInformation(inputWrite.value, HANDLE_FLAG_INHERIT.toUInt(), 0u) !=
                                0
                    )
                    check(
                        SetHandleInformation(outputRead.value, HANDLE_FLAG_INHERIT.toUInt(), 0u) !=
                                0
                    )
                    val startup = alloc<STARTUPINFOW>()
                    memset(startup.ptr, 0, sizeOf<STARTUPINFOW>().toULong())
                    startup.cb = sizeOf<STARTUPINFOW>().toUInt()
                    startup.dwFlags = STARTF_USESTDHANDLES.toUInt()
                    startup.hStdInput = inputRead.value
                    startup.hStdOutput = outputWrite.value
                    startup.hStdError = GetStdHandle(STD_ERROR_HANDLE)
                    val child = alloc<PROCESS_INFORMATION>()
                    check(
                        CreateProcessW(
                            null,
                            "\"$executable\" --no-http".wcstr.ptr,
                            null,
                            null,
                            1,
                            CREATE_NO_WINDOW.toUInt(),
                            null,
                            null,
                            startup.ptr,
                            child.ptr,
                        ) != 0
                    )
                    CloseHandle(child.hThread)
                    CloseHandle(inputRead.value)
                    inputRead.value = null
                    CloseHandle(outputWrite.value)
                    outputWrite.value = null
                    try {
                        val request = buildJsonObject {
                            put("jsonrpc", "2.0")
                            put("id", 1)
                            put("method", "initialize")
                            putJsonObject("params") {
                                put("protocolVersion", "2025-11-25")
                                putJsonObject("capabilities") {}
                                putJsonObject("clientInfo") {
                                    put("name", "stdio-smoke")
                                    put("version", "1")
                                }
                            }
                        }
                        val bytes = "$request\n".encodeToByteArray()
                        val count = alloc<UIntVar>()
                        bytes.usePinned {
                            check(
                                WriteFile(
                                    inputWrite.value,
                                    it.addressOf(0),
                                    bytes.size.toUInt(),
                                    count.ptr,
                                    null,
                                ) != 0
                            )
                        }
                        assertEquals(bytes.size.toUInt(), count.value)
                        val response = StringBuilder()
                        withTimeout(30_000) {
                            val byte = alloc<ByteVar>()
                            while (true) {
                                check(
                                    PeekNamedPipe(
                                        outputRead.value,
                                        null,
                                        0u,
                                        null,
                                        count.ptr,
                                        null,
                                    ) != 0
                                )
                                if (count.value == 0u) {
                                    delay(10)
                                    continue
                                }
                                check(
                                    ReadFile(outputRead.value, byte.ptr, 1u, count.ptr, null) != 0
                                )
                                if (byte.value.toInt() == 10) break
                                response.append(byte.value.toInt().toChar())
                            }
                        }
                        val result = Json.parseToJsonElement(response.toString()).jsonObject
                        assertEquals(1, result.getValue("id").jsonPrimitive.int)
                        assertEquals(
                            "factorio-mcp",
                            result
                                .getValue("result")
                                .jsonObject
                                .getValue("serverInfo")
                                .jsonObject
                                .getValue("name")
                                .jsonPrimitive
                                .content,
                        )
                        CloseHandle(inputWrite.value)
                        inputWrite.value = null
                        withTimeout(10_000) {
                            while (
                                WaitForSingleObject(child.hProcess, 0u) == WAIT_TIMEOUT.toUInt()
                            ) delay(10)
                        }
                        val exitCode = alloc<UIntVar>()
                        check(GetExitCodeProcess(child.hProcess, exitCode.ptr) != 0)
                        assertEquals(0u, exitCode.value)
                    } finally {
                        if (WaitForSingleObject(child.hProcess, 0u) == WAIT_TIMEOUT.toUInt())
                            TerminateProcess(child.hProcess, 1u)
                        CloseHandle(child.hProcess)
                    }
                } finally {
                    outputRead.value?.let { CloseHandle(it) }
                    outputWrite.value?.let { CloseHandle(it) }
                }
            } finally {
                inputRead.value?.let { CloseHandle(it) }
                inputWrite.value?.let { CloseHandle(it) }
            }
        }
    }
}
