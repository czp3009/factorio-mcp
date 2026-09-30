package com.hiczp.factorio.mcp

import kotlin.test.Test
import kotlin.test.assertFails

class LuaProtectedDispatchTest {
    @Test
    fun rejectsChangedArgumentsAndUnprovenPrefixControlFlow() {
        val code =
            "55 48 89 e5 41 57 53 48 83 ec 10 48 89 fb 48 8b 47 08 48 89 45 e0 4c 89 47 08 49 89 cf e8 de 00 00 00 4c 03 7b 10 48 83 c4 10 5b 41 5f 5d c3"

        fun verify(value: String) = SysVLuaProtectedDispatch.analyze(machineCode(value), 0x1000, 0x1100, 64, 16, 8)
        verify(code)
        for (changed in listOf(
            code.replace("48 89 45 e0", "48 89 d6 90"),
            code.replace("48 89 45 e0", "48 89 f2 90"),
            code.replace("48 89 45 e0", "48 89 d7 90"),
            code.replace("48 89 45 e0", "89 ff 90 90"),
            code.replace("e8 de", "e8 df"),
            code.replace("48 89 45 e0", "eb 02 90 90"),
            code.replace("4c 89 47 08", "4c 89 4f 08"),
            code.replace("49 89 cf", "49 89 d7"),
            code.replace("4c 03 7b 10", "4c 03 7e 10"),
        )) assertFails { verify(changed) }
    }
}
