@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlinx.cinterop.COpaquePointer

/** Only OS library loading differs; Lua fixture execution and query assertions are shared. */
internal expect class LuaFixtureLibrary(path: String) : AutoCloseable {
    fun symbol(name: String): COpaquePointer

    override fun close()
}
