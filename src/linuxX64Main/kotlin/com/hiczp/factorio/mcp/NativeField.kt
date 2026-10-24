package com.hiczp.factorio.mcp

/** A byte range whose object bounds and meaning are established by its owning metadata proof. */
internal data class NativeField(val offset: Long, val width: Int)
