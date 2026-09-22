package com.hiczp.factorio.mcp

import kotlinx.io.Sink
import kotlinx.io.Source

internal class UnrecognizedTarget(message: String) : Exception(message)

/** OS services used by the common CLI and session lifecycle. */
internal expect object Platform {
    fun standardInput(): Source
    fun standardOutput(): Sink
    fun findProcesses(name: String): List<Int>
    fun initializeCancellation()
    val cancelled: Boolean
    fun writeError(message: String)
    fun exitProcess(code: Int): Nothing
}

/** Owns exclusive access to a target; evaluate runs at a safe game update boundary. */
internal expect class GameProcess(pid: Int) {
    suspend fun connectResident(timeoutMillis: Int)
    suspend fun submitTask(description: String, timeoutMillis: Int): String
    fun outputPath(filename: String): String
    suspend fun status(timeoutMillis: Int): String
    suspend fun diagnosticStatus(timeoutMillis: Int): String
    suspend fun evaluate(source: String, timeoutMillis: Int): String
    suspend fun close()
}
