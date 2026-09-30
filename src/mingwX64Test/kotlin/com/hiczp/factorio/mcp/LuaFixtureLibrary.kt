@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.reinterpret
import platform.windows.FreeLibrary
import platform.windows.GetProcAddress
import platform.windows.LoadLibraryW

internal actual class LuaFixtureLibrary actual constructor(path: String) : AutoCloseable {
    private var library = checkNotNull(LoadLibraryW(path)) { "Cannot load Lua fixture library: $path" }

    actual fun symbol(name: String): COpaquePointer =
        checkNotNull(GetProcAddress(library, name)) { "Missing Lua fixture export: $name" }.reinterpret()

    actual override fun close() {
        check(FreeLibrary(library) != 0) { "Cannot release Lua fixture library" }
    }
}
