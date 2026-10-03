package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ByteEnumNamesTest {
    private val string = NativeStringLayout(0, 8, 16, 32, ElfImage.Symbol("fixture", 1, 1, 2, 1))

    private fun analyze(
        first: Int = 2,
        second: Int = 5,
        compared: String = "83 f8",
        length: Int = 4,
        terminator: Int = 0,
        pointer: String = "48 8d 75 f0",
        failureReturns: Boolean = false,
        bypassSource: Boolean = false,
    ): Map<Int, String> {
        val bytes = mutableListOf<Byte>()
        val labels = mutableMapOf<String, Int>()
        val fixups = mutableListOf<Pair<Int, String>>()
        fun emit(code: String) {
            bytes += code.split(' ').filter(String::isNotEmpty).map { it.toInt(16).toByte() }
        }
        fun integer(value: Int) {
            repeat(4) { bytes += (value ushr (it * 8)).toByte() }
        }
        fun branch(opcode: String, label: String) {
            emit(opcode)
            fixups += bytes.size to label
            integer(0)
        }
        fun call(address: Int) {
            emit("e8")
            integer(address - bytes.size - 4)
        }
        emit("55 48 89 e5 48 83 ec 20")
        if (bypassSource) {
            emit("85 c9")
            branch("0f 84", "push")
        }
        val source = bytes.size.toLong()
        emit("0f b6 07 83 f8")
        bytes += first.toByte()
        branch("0f 84", "first")
        emit(compared)
        bytes += second.toByte()
        branch("0f 84", "second")
        call(0x3000)
        if (failureReturns) emit("c3")
        else {
            emit("e9")
            integer(0x4000 - bytes.size - 4)
        }
        for ((name, text) in listOf("first" to "east", "second" to "west")) {
            labels[name] = bytes.size
            emit("90 ba 04 00 00 00 $pointer 48 89 75 e0 48 89 55 e8 c7 45 f0")
            bytes += text.encodeToByteArray().toList()
            emit("c6 45 f4")
            bytes += terminator.toByte()
            emit("ba")
            integer(length)
            branch("e9", "push")
        }
        labels["push"] = bytes.size
        val push = bytes.size.toLong()
        call(0x2000)
        emit("48 83 c4 20 5d c3")
        for ((at, label) in fixups) {
            val delta = labels.getValue(label) - at - 4
            repeat(4) { bytes[at + it] = (delta ushr (it * 8)).toByte() }
        }
        val flow = X64ControlFlow(X64Instructions(BinaryView(bytes.toByteArray())).all())
        return ByteEnumNames.analyze(flow, source, push, string)
    }

    @Test
    fun enumeratesNativeValuesWithoutDependingOnCaseOrderOrAdjacentInstructions() {
        assertEquals(mapOf(2 to "east", 5 to "west"), analyze())
        assertEquals(mapOf(5 to "east", 2 to "west"), analyze(first = 5, second = 2))
        assertEquals(mapOf(17 to "east", 23 to "west"), analyze(first = 17, second = 23))
    }

    @Test
    fun rejectsOtherInputsIncompleteStringsAndNormalReturnsWithoutSubmission() {
        assertFailsWith<IllegalArgumentException> { analyze(compared = "83 f9") }
        assertFailsWith<IllegalArgumentException> { analyze(length = 5) }
        assertFailsWith<IllegalArgumentException> { analyze(terminator = 1) }
        assertFailsWith<IllegalArgumentException> { analyze(pointer = "48 8d 75 e0") }
        assertFailsWith<IllegalArgumentException> { analyze(failureReturns = true) }
        assertFailsWith<IllegalArgumentException> { analyze(bypassSource = true) }
    }
}
