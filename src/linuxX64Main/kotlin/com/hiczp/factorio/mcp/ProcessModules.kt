@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import platform.posix._SC_PAGESIZE
import platform.posix.sysconf

internal data class ModuleSymbol(val address: Long, val size: Long, val path: String)

/** Resolves loaded ELF definitions and confirms their mapped inode and unmodified entry bytes. */
internal class ProcessModules(private val process: ProcessHandle) {
    fun functions(
        names: Set<String>,
        path: String? = null,
        sharedObjects: Set<String>? = null
    ): Map<String, ModuleSymbol> =
        exports(names, path, 2, sharedObjects)

    fun objects(names: Set<String>, path: String): Map<String, ModuleSymbol> = exports(names, path, 1, null)

    private fun exports(
        names: Set<String>,
        path: String?,
        type: Int,
        sharedObjects: Set<String>?
    ): Map<String, ModuleSymbol> {
        require(names.isNotEmpty() && names.size <= 256)
        val found = mutableMapOf<String, ModuleSymbol>()
        val groups = process.mappings().filter { it.path?.startsWith('/') == true && it.inode != 0L }
            .groupBy { Triple(it.deviceMajor, it.deviceMinor, it.inode) }
        for (mappings in groups.values) {
            if (mappings.none { it.executable } || (path != null && mappings.none { it.path == path })) continue
            val header = mappings.firstOrNull { it.offset == 0L && it.readable && it.end - it.start >= 4 }
            if (header != null && BinaryView(process.readMemory(header.start, 4)).unsigned(0, 4) != 0x464c457fL) {
                // Drivers may map executable memfd code buffers which are not ELF modules.
                continue
            }
            val first = mappings.first()
            process.withMappedFile(first) { image ->
                if (sharedObjects != null && image.sharedObjectName() !in sharedObjects) return@withMappedFile
                if (image.sections.none { it.type == 11L }) return@withMappedFile
                val symbols = image.symbols(dynamic = true).filter { it.name in names }.toList().groupBy { it.name }
                if (symbols.isEmpty()) return@withMappedFile
                val bias = image.loadBias(mappings, sysconf(_SC_PAGESIZE))
                for ((name, matches) in symbols) {
                    val symbol = matches.distinct().singleOrNull() ?: error("Ambiguous module export: $name")
                    require(symbol.type == type && symbol.size > 0) { "Unexpected loaded export kind: $name" }
                    require(symbol.address <= Long.MAX_VALUE - bias) { "Loaded symbol address overflows" }
                    val address = bias + symbol.address
                    if (type == 2) {
                        val bytes = image.functionBytes(symbol, 32)
                        require(
                            process.readMemory(address, bytes.size.toInt())
                                .contentEquals(bytes.bytes(0, bytes.size.toInt()))
                        ) {
                            "Loaded module entry differs from its selected ELF: $name"
                        }
                    } else {
                        require(image.segments.any {
                            it.type == 1L && symbol.address >= it.address && symbol.address - it.address <= it.memorySize &&
                                    symbol.size <= it.memorySize - (symbol.address - it.address)
                        }) { "ELF data export exceeds a loaded segment" }
                    }
                    val function = ModuleSymbol(address, symbol.size, checkNotNull(first.path))
                    val prior = found.put(name, function)
                    require(prior == null || prior == function) {
                        "Export is ambiguous across loaded modules: $name (${prior?.path}, ${function.path})"
                    }
                }
            }
        }
        require(found.keys == names) { "Missing loaded ELF exports: ${(names - found.keys).joinToString()}" }
        return found
    }
}
