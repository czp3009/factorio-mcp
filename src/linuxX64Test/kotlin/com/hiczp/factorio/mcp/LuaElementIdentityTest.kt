package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertFails

class LuaElementIdentityTest {
    @Test
    fun requiresTheSameReceiverPrimaryEntryAndUnchangedReturnOnEveryPath() {
        fun check(code: String) =
            LuaElementIdentity.analyze(X64ControlFlow(X64Instructions(machineCode(code)).all()), 3)
        check("48 8b 07 ff 50 18 c3")
        check("48 8b 07 48 8b 48 18 ff d1 c3")
        check("48 8b 07 ff 50 18 48 85 c0 74 01 c3 90 c3")
        for (code in
            listOf(
                "48 8b 06 ff 50 18 c3", // Another object's table.
                "48 8b 47 08 ff 50 18 c3", // A secondary table.
                "48 8b 07 ff 50 20 c3", // Another virtual entry.
                "48 8b 07 48 89 f7 ff 50 18 c3", // Changed receiver.
                "48 8b 07 ff 50 18 48 83 c0 08 c3", // Adjusted return.
                "48 8b 07 ff 50 18 48 85 c0 74 01 c3 31 c0 c3", // Another normal result.
                "48 8b 07 ff 50 18 eb 20 c3", // Unverified tail return.
            )) assertFails { check(code) }
    }
}
