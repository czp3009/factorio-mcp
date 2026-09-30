@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlinx.cinterop.COpaquePointer
import platform.posix.*

internal actual class LuaFixtureLibrary actual constructor(path: String) : AutoCloseable {
    private val library =
        checkNotNull(dlopen(path, RTLD_NOW or RTLD_LOCAL)) { "Cannot load Lua fixture library: $path" }

    actual fun symbol(name: String): COpaquePointer =
        checkNotNull(dlsym(library, name)) { "Missing Lua fixture export: $name" }

    actual override fun close() {
        check(dlclose(library) == 0) { "Cannot release Lua fixture library" }
    }
}
