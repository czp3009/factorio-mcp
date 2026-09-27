package com.hiczp.factorio.mcp.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Prints the project version without producing an artifact.")
abstract class PrintVersion : DefaultTask() {
    @get:Input
    abstract val releaseVersion: Property<String>

    @TaskAction
    fun printVersion() {
        logger.quiet(releaseVersion.get())
    }
}
