package com.hiczp.factorio.mcp

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * Reads the documentation shipped beside the selected installation, not an online/latest schema.
 */
internal fun readRuntimeApi(executable: String): RuntimeApi {
    val bin = Path(executable).parent?.parent ?: error("Cannot locate Factorio installation")
    val root = bin.parent ?: error("Cannot locate Factorio installation")
    val path = Path(root, "doc-html", "runtime-api.json")
    val size =
        SystemFileSystem.metadataOrNull(path)?.size
            ?: error("Object inspection requires the installation's doc-html/runtime-api.json")
    require(size in 1..(32L * 1024 * 1024)) { "Runtime API file exceeds bound" }
    val document =
        SystemFileSystem.source(path).buffered().use {
            val text = it.readString(size)
            require(it.exhausted()) { "Runtime API file changed while reading" }
            text
        }
    return RuntimeApi(Json.parseToJsonElement(document).jsonObject)
}
