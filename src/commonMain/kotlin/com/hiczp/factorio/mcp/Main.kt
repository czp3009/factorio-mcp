package com.hiczp.factorio.mcp

import io.github.oshai.kotlinlogging.FormattingAppender
import io.github.oshai.kotlinlogging.KLoggingEvent
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import io.github.oshai.kotlinlogging.Level
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    KotlinLoggingConfiguration.logStartupMessage = false
    KotlinLoggingConfiguration.direct.logLevel = Level.WARN
    KotlinLoggingConfiguration.direct.appender =
        object : FormattingAppender() {
            override fun logFormattedMessage(loggingEvent: KLoggingEvent, formattedMessage: Any?) =
                Platform.writeError(formattedMessage.toString())
        }
    try {
        val options = McpOptions.parse(args)
        Platform.initializeCancellation()
        runBlocking {
            val game = GameSession()
            try {
                serveTransports(createServer(game), options)
            } finally {
                withContext(NonCancellable) { game.close() }
            }
        }
    } catch (failure: Exception) {
        Platform.writeError("factorio-mcp: ${failure.message}")
        exitProcess(1)
    }
}
