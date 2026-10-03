package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.Operation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class HeapStringArgumentTest {
    private val string = NativeStringLayout(0, 8, 16, 32, ElfImage.Symbol("fixture", 1, 1, 2, 1))

    private fun analyze(middle: String = "", allocationSize: Int = 19, terminator: Int = 0, allocation: Long = 0x1000): String {
        val bytes = mutableListOf<Byte>()
        fun emit(text: String) {
            bytes += text.split(' ').filter(String::isNotEmpty).map { it.toInt(16).toByte() }
        }

        fun word(value: Long, width: Int = 4) {
            repeat(width) { bytes += (value ushr (it * 8)).toByte() }
        }

        fun call(target: Long) {
            emit("e8")
            word(target - bytes.size - 4)
        }
        emit("48 83 ec 28 bf")
        word(allocationSize.toLong())
        call(allocation)
        emit("48 89 04 24 48 c7 44 24 08 12 00 00 00 48 b9")
        bytes += "intentio".encodeToByteArray().toList()
        emit("48 89 08 48 ba")
        bytes += "nallyEmp".encodeToByteArray().toList()
        emit("48 89 50 08 66 c7 40 10 74 79 c6 40 12")
        bytes += terminator.toByte()
        emit(middle)
        emit("48 89 e6 ba 01 00 00 00 b9 01 00 00 00")
        call(0x2000)
        emit("48 83 c4 28 c3")
        val flow = X64ControlFlow(X64Instructions(BinaryView(bytes.toByteArray())).all())
        val consume = flow.instructions.last { it.operation == Operation.CALL }.offset
        return HeapStringArgument.text(flow, consume, 6, 0x100000, string, 0x1000) { _, _ ->
            error("Fixture has no immutable external data")
        }
    }

    @Test
    fun readsLongNamesThroughOwnedAllocationAndAgreeingConditionalStores() {
        assertEquals("intentionallyEmpty", analyze())
        assertEquals("intentionallyEmpty", analyze("90 85 ff 74 03 c6 00 69"))
        // An unrelated private scalar slot cannot invalidate the separately bounded name object.
        assertEquals("intentionallyEmpty", analyze("48 83 44 24 20 01"))
    }

    @Test
    fun rejectsUnknownCallsAliasingConflictingBytesAndInvalidAllocationBounds() {
        assertFails { analyze("e8 00 10 00 00") }
        assertFails { analyze("48 89 03") }
        assertFails { analyze("48 8b 07 c6 00 00") }
        assertFails { analyze("85 ff 74 03 c6 00 78") }
        assertFails { analyze("85 ff 74 02 31 c0") }
        assertFails { analyze(allocationSize = 18) }
        assertFails { analyze(terminator = 1) }
        assertFails { analyze(allocation = 0x1800) }
    }
}
