package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class TypedMemberStoresTest {
    private val construction = "48 89 fb 49 89 f4 48 8d 05 f3 0f 00 00 49 89 04 24"
    private val publication = "4c 89 63 18 c3"

    private fun resolve(code: String = "$construction $publication", size: Long = 64, table: Long = 0x2000) =
        TypedMemberStores.analyze(X64ControlFlow(X64Instructions(machineCode(code)).all()), 0x1000, size, table)

    @Test
    fun requiresTypedInitializationAndOriginalOwner() {
        assertEquals(listOf(24L), resolve())
        assertFails { resolve(table = 0x2008) }
        assertFails { resolve(size = 31) }
        assertFails { resolve("$construction ${publication.replace("63 18", "63 19")}") }
        assertFails { resolve("${construction.replace("48 89 fb", "48 89 f3")} $publication") }
        assertFails { resolve("${construction.replace("49 89 f4", "49 89 fc")} $publication") }
        assertFails { resolve("${construction.replace("49 89 04 24", "49 89 44 24 08")} $publication") }
        assertFails { resolve("$construction ${publication.replace("4c 89", "44 89")}") }
    }

    @Test
    fun invalidatesChangedRegistersAndOverwrittenPrimaryTables() {
        for (change in listOf(
            "49 83 c4 08", // Adjusted child pointer.
            "45 31 e4", // Partial-register write.
            "49 8b 04 24 49 89 c4", // A load cannot restore construction provenance.
            "49 c7 04 24 00 00 00 00", // Primary table replacement.
            "4d 89 e5 49 c7 45 00 00 00 00 00", // Replacement through a register alias.
            "48 89 f3", // Owner is no longer the original argument.
        )) assertFails { resolve("$construction $change $publication") }
        // Calls preserve the child in R12 and owner in RBX, but not in a volatile register.
        assertEquals(listOf(24L), resolve("$construction e8 00 01 00 00 $publication"))
        assertFails { resolve("$construction 4c 89 e0 e8 00 01 00 00 48 89 43 18 c3") }
    }

    @Test
    fun rejectsPublicationWithoutEvidenceOnEveryIncomingPath() {
        // One branch bypasses initialization; the RIP displacement is adjusted for the inserted branch.
        val conditional = "48 89 fb 49 89 f4 85 d2 74 0b 48 8d 05 ef 0f 00 00 49 89 04 24 $publication"
        assertFails { resolve(conditional) }
        // A loop can create a new object at the same construction site; discard identities on backedges.
        assertFails { resolve("$construction 85 d2 75 fc $publication") }
    }
}
