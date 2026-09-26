package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails

class PdbTypeStreamTest {
    private class Bytes {
        val values = mutableListOf<Byte>()

        fun number(value: Int, width: Int = 4) {
            repeat(width) { values.add((value ushr (it * 8)).toByte()) }
        }

        fun text(value: String) {
            values.addAll(value.encodeToByteArray().toList())
            values.add(0)
        }

        fun bytes() = values.toByteArray()
    }

    private fun bytes(block: Bytes.() -> Unit) = Bytes().apply(block).bytes()

    private fun ByteArray.put(offset: Int, value: Int) {
        repeat(4) { this[offset + it] = (value ushr (it * 8)).toByte() }
    }

    private fun types(
        slot: Int = 24,
        returnType: Int = 0x41,
        fieldLeaf: Int = 0x1409,
        parameterCount: Int = 0,
        pointerClass: String? = null,
        pointerFlags: Int = 0x1000c,
        qualifiers: Int = 1,
        aggregate: Boolean = false,
        functionFlags: Int = if (aggregate) 0x100 else 0,
        base: Boolean = false,
        baseOffset: Int = 0,
    ): ByteArray {
        val name = "NumberInterface"
        val records =
            listOf(
                bytes {
                    number(0x1504, 2)
                    number(0, 2)
                    number(0x80, 2)
                    repeat(3) { number(0) }
                    number(0, 2)
                    text(name)
                },
                bytes {
                    number(0x1001, 2)
                    number(0x1000)
                    number(1, 2)
                },
                bytes {
                    number(0x1002, 2)
                    number(0x1001)
                    number(0x1000c)
                },
                bytes {
                    number(0x1201, 2)
                    number(0)
                },
                bytes {
                    number(0x1009, 2)
                    number(
                        if (pointerClass == null) returnType else if (aggregate) 0x1008 else 0x100a
                    )
                    number(0x1000)
                    number(0x1002)
                    number(functionFlags, 2)
                    number(parameterCount, 2)
                    number(0x1003)
                    number(0)
                },
                bytes {
                    number(0x000a, 2)
                    number(8, 2)
                    number(0x55555555)
                },
                bytes {
                    number(0x1203, 2)
                    number(if (base) 0x1400 else fieldLeaf, 2)
                    number(0, 2)
                    number(if (base) 0x1000 else 0x1002)
                    if (base) number(baseOffset, 2)
                    number(0x1511, 2)
                    number(0x1b, 2)
                    number(0x1004)
                    number(slot)
                    text("getCount")
                    number(0x150d, 2)
                    number(3, 2)
                    number(0x74)
                    number(0x8004, 2)
                    number(80000)
                    text("unrelatedField")
                },
                bytes {
                    number(0x1504, 2)
                    number(3, 2)
                    number(0, 2)
                    number(0x1006)
                    number(0)
                    number(0x1005)
                    number(64, 2)
                    text(name)
                },
            ) +
                    if (pointerClass == null) emptyList()
                    else
                        listOf(
                            bytes {
                                number(0x1504, 2)
                                number(0, 2)
                                number(0x80, 2)
                                repeat(3) { number(0) }
                                number(0, 2)
                                text(pointerClass)
                            },
                            bytes {
                                number(0x1001, 2)
                                number(0x1008)
                                number(qualifiers, 2)
                            },
                            bytes {
                                number(0x1002, 2)
                                number(0x1009)
                                number(pointerFlags)
                            },
                        )
        val body = bytes {
            records.forEach { record ->
                number(record.size, 2)
                values.addAll(record.toList())
            }
        }
        return ByteArray(56 + body.size).apply {
            put(0, 20040203)
            put(4, 56)
            put(8, 0x1000)
            put(12, 0x1000 + records.size)
            put(16, body.size)
            body.copyInto(this, 56)
        }
    }

    @Test
    fun discoversChangingVirtualPositionsFromTypeRecords() {
        for (offset in listOf(0, 8, 24, 56)) {
            val methods =
                PdbTypeStream(types(offset)).methods("NumberInterface", mapOf("getCount" to 0x41u))
            assertEquals(
                PdbTypeStream.VirtualMethod(offset.toUInt(), 0x41u),
                methods.getValue("getCount"),
            )
        }
    }

    @Test
    fun pointerReturnsRequireTheNamedConstClassAndPlain64BitPointer() {
        fun resolve(input: ByteArray) =
            PdbTypeStream(input)
                .methods("NumberInterface", emptyMap(), mapOf("getCount" to "QualityPrototype"))
        for (offset in listOf(8, 32, 56)) {
            assertEquals(
                offset.toUInt(),
                resolve(types(slot = offset, pointerClass = "QualityPrototype"))
                    .getValue("getCount")
                    .offset,
            )
        }
        for (input in
        listOf(
            types(),
            types(pointerClass = "OtherPrototype"),
            types(pointerClass = "QualityPrototype", qualifiers = 0),
            types(pointerClass = "QualityPrototype", qualifiers = 3),
            types(pointerClass = "QualityPrototype", pointerFlags = 0x800a),
            types(pointerClass = "QualityPrototype", pointerFlags = 0x1002c),
            types(pointerClass = "QualityPrototype", parameterCount = 1),
        )) assertFails { resolve(input) }
    }

    @Test
    fun rejectsUnknownInheritanceAndIncompatibleSignatures() {
        for (input in
        listOf(
            types(slot = 3),
            types(slot = 64),
            types(returnType = 0x74),
            types(fieldLeaf = 0x1400),
            types(parameterCount = 1),
        )) {
            assertFails {
                PdbTypeStream(input).methods("NumberInterface", mapOf("getCount" to 0x41u))
            }
        }
        assertFails { PdbTypeStream(types()).methods("OtherInterface", mapOf("getCount" to 0x41u)) }
        assertFails { PdbTypeStream(types()).methods("NumberInterface", mapOf("absent" to 0x41u)) }
        val input = types()
        for (length in listOf(0, 4, 15, 55, input.size - 1)) assertFails {
            PdbTypeStream(input.copyOf(length))
        }
        assertFails { PdbTypeStream(input.copyOf().apply { put(16, Int.MAX_VALUE) }) }
        assertFails { PdbTypeStream(input.copyOf().apply { put(12, Int.MAX_VALUE) }) }
    }

    @Test
    fun aggregateReturnsAndPrimaryInterfacesRequireExplicitMatchingMetadata() {
        fun resolve(input: ByteArray) =
            PdbTypeStream(input)
                .methods(
                    "NumberInterface",
                    emptyMap(),
                    aggregateSignatures = mapOf("getCount" to "FixtureResult"),
                    primaryBase = "NumberInterface",
                )
        for (slot in listOf(8, 32, 56)) {
            assertEquals(
                slot.toUInt(),
                resolve(
                    types(
                        slot = slot,
                        pointerClass = "FixtureResult",
                        aggregate = true,
                        base = true,
                    )
                )
                    .getValue("getCount")
                    .offset,
            )
        }
        for (input in
        listOf(
            types(pointerClass = "FixtureResult", aggregate = true),
            types(
                pointerClass = "FixtureResult",
                aggregate = true,
                base = true,
                baseOffset = 8,
            ),
            types(
                pointerClass = "FixtureResult",
                aggregate = true,
                base = true,
                functionFlags = 0,
            ),
            types(
                pointerClass = "FixtureResult",
                aggregate = true,
                base = true,
                parameterCount = 1,
            ),
            types(pointerClass = "OtherResult", aggregate = true, base = true),
            types(pointerClass = "FixtureResult", base = true),
        )) assertFails { resolve(input) }
    }

    private fun container(): ByteArray =
        ByteArray(512 * 6).apply {
            "Microsoft C/C++ MSF 7.00\r\n\u001aDS\u0000\u0000\u0000"
                .encodeToByteArray()
                .copyInto(this)
            put(32, 512)
            put(40, 6)
            put(44, 28)
            put(52, 1)
            put(512, 2)
            put(1024, 3)
            put(1028, -1)
            put(1032, 28)
            put(1036, 600)
            put(1040, 3)
            put(1044, 5)
            put(1048, 4)
            put(1536 + 8, 7)
            ByteArray(16) { it.toByte() }.copyInto(this, 1536 + 12)
            repeat(600) { index ->
                this[if (index < 512) 2560 + index else 2048 + index - 512] = index.toByte()
            }
        }

    private fun streams(bytes: ByteArray) =
        PdbStreams(bytes.size.toLong()) { offset, size ->
            bytes.copyOfRange(offset.toInt(), offset.toInt() + size)
        }

    @Test
    fun readsFragmentedStreamsAndChecksTheirIdentityAndBounds() {
        val input = container()
        val pdb = streams(input)
        assertContentEquals(ByteArray(600) { it.toByte() }, pdb.stream(2, 600))
        pdb.verifyIdentity(ByteArray(16) { it.toByte() }, 7)
        assertFails { pdb.verifyIdentity(ByteArray(16), 7) }
        assertFails { pdb.verifyIdentity(ByteArray(16) { it.toByte() }, 8) }
        assertFails { pdb.stream(0, 100) }
        assertFails { pdb.stream(2, 2) }
        assertFails { streams(input.copyOf().apply { put(1044, Int.MAX_VALUE) }).stream(2, 600) }
        assertFails { streams(input.copyOf().apply { put(32, 513) }) }
        assertFails { streams(input.copyOf().apply { put(44, Int.MAX_VALUE) }) }
        assertFails { streams(input.copyOf(100)) }
    }
}
