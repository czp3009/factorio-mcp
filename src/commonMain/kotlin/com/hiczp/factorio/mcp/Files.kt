package com.hiczp.factorio.mcp

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

/** Read the current file size, without following a concurrently growing file indefinitely. */
internal fun Path.readBounded(maxBytes: Int): ByteArray {
    require(maxBytes >= 0)
    val metadata = SystemFileSystem.metadataOrNull(this) ?: error("Cannot stat file: $this")
    require(metadata.isRegularFile && metadata.size in 0..maxBytes.toLong()) {
        "Expected a regular file of at most $maxBytes bytes: $this"
    }
    return SystemFileSystem.source(this).buffered().use { source ->
        source.readByteArray(metadata.size.toInt())
    }
}
