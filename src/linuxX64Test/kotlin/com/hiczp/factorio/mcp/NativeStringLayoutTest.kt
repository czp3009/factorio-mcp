@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import com.hiczp.factorio.mcp.X64Instructions.*
import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotEquals

class NativeStringLayoutTest {
    @Test
    fun derivesTypedStorageAndChecksBothBufferLifetimes() {
        val directory = checkNotNull(getenv("FACTORIO_MCP_TEST_NATIVE")).toKString()
        val layouts = listOf(1, 23).map { padding ->
            MappedBinary("$directory/string_storage_fixture_$padding").use { file ->
                val image = ElfImage(file.view)
                fun constant(name: String) = image.symbol(name).let { image.virtualBytes(it.address, 8).unsigned(0, 8) }
                val data = constant("fixture_string_data")
                val local = constant("fixture_string_local")
                val owner = image.symbol("_ZN5Owner7disposeEv")
                val destroy = image.symbol("fixture_string_destroy")
                val sized = image.symbol("fixture_delete_sized").address
                val plain = image.symbol("fixture_delete").address
                val bytes = image.functionBytes(owner, 256)
                val size = StringStorageProof.owner(bytes, owner.address, sized, data, local)
                assertEquals(constant("fixture_string_size"), size)
                StringStorageProof.destructor(
                    image.functionBytes(destroy, 256),
                    destroy.address,
                    plain,
                    data,
                    local,
                    size
                )
                assertFails {
                    StringStorageProof.owner(
                        image.functionBytes(owner, 256),
                        owner.address,
                        plain,
                        data,
                        local
                    )
                }
                assertFails {
                    StringStorageProof.owner(
                        image.functionBytes(owner, 256),
                        owner.address,
                        sized,
                        data + 8,
                        local
                    )
                }
                assertFails {
                    StringStorageProof.owner(
                        image.functionBytes(owner, 256),
                        owner.address,
                        sized,
                        data,
                        local + 1
                    )
                }
                assertFails {
                    StringStorageProof.destructor(
                        image.functionBytes(destroy, 256),
                        destroy.address,
                        sized,
                        data,
                        local,
                        size
                    )
                }
                assertFails {
                    StringStorageProof.destructor(
                        image.functionBytes(destroy, 256),
                        destroy.address,
                        plain,
                        data,
                        local,
                        local
                    )
                }
                for (instruction in X64Instructions(bytes).all()
                    .filter { it.operation in listOf(Operation.JCC, Operation.INC) }) {
                    val changed = bytes.bytes(0, bytes.size.toInt())
                    val offset = instruction.offset.toInt()
                    if (instruction.operation == Operation.JCC) {
                        val opcode = offset + if (instruction.size == 6) 1 else 0
                        changed[opcode] = (changed[opcode].toInt() xor 1).toByte()
                    } else {
                        repeat(instruction.size) { changed[offset + it] = 0x90.toByte() }
                    }
                    assertFails { StringStorageProof.owner(BinaryView(changed), owner.address, sized, data, local) }
                }
                val deletionSize = X64Instructions(bytes).all().single {
                    it.operation == Operation.MOV &&
                            it.destination == Register(6, 4) && it.source == Immediate(size)
                }
                val undersized = bytes.bytes(0, bytes.size.toInt())
                val immediate = (deletionSize.offset + deletionSize.size - 4).toInt()
                repeat(4) { undersized[immediate + it] = if (it == 0) 1 else 0 }
                assertFails { StringStorageProof.owner(BinaryView(undersized), owner.address, sized, data, local) }
                Triple(size, data, local)
            }
        }
        assertNotEquals(layouts[0].first, layouts[1].first)
        assertNotEquals(layouts[0].second, layouts[1].second)
        assertNotEquals(layouts[0].third, layouts[1].third)
    }

    @Test
    fun rejectsReloadingAnOwnedPointerDuringCleanup() {
        val code = "48 8b 47 10 48 8b 47 10 c3"
        assertFails { StringStorageProof.owner(machineCode(code), 0x10000, 0x11000, 8, 40) }
    }

    @Test
    fun rejectsWrongDestructionBranchPointerWidthAndFrame() {
        // Synthetic string: pointer at 8, inline storage at 40; allocator at 0x11000.
        val code = "48 8b 47 08 48 83 c7 28 48 39 f8 74 08 48 89 c7 e9 eb 0f 00 00 c3"
        fun verify(text: String) = StringStorageProof.destructor(machineCode(text), 0x10000, 0x11000, 8, 40, 64)
        verify(code)
        for (changed in listOf(
            code.replace("74 08", "75 08"),
            code.replace("74 08", "eb 08"),
            code.replace("74 08", "74 05"),
            code.replace("48 8b 47 08", "48 8b 47 10"),
            code.replace("48 8b 47 08", "48 8b 46 08"),
            code.replace("48 8b 47 08", "90 8b 47 08"),
            code.replace("48 83 c7 28", "48 83 c7 20"),
            code.replace("48 89 c7", "48 89 cf"),
            code.replace("48 89 c7", "48 89 c3"),
            code.replace("48 39 f8", "48 85 c0"),
            code.replace("e9 eb", "e9 ea"),
        )) assertFails { verify(changed) }
    }
}
