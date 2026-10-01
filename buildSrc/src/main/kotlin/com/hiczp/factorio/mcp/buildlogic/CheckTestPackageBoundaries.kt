package com.hiczp.factorio.mcp.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Rejects external-game environment markers in test sources outside the packages excluded from the
 * default Gradle test tasks, so such tests cannot silently run or vacuously pass there.
 */
@DisableCachingByDefault(because = "Validates test source package boundaries without producing an artifact.")
abstract class CheckTestPackageBoundaries : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Input
    abstract val excludedPackages: SetProperty<String>

    @get:Input
    abstract val requiredMarkers: SetProperty<String>

    @TaskAction
    fun check() {
        val excluded = excludedPackages.get()
        val markers = requiredMarkers.get()
        val violations = mutableListOf<String>()
        for (file in sources.files.sortedBy { it.path }) {
            if (!file.isFile || file.extension != "kt") continue
            val text = file.readText()
            val packageName = text
                .lineSequence()
                .firstOrNull { it.startsWith("package ") }
                ?.removePrefix("package ")
                ?.trim()
                ?.substringBefore(';')
                .orEmpty()
            if (packageName in excluded) continue
            for (marker in markers) {
                if (marker in text) {
                    violations += "$file: $marker outside ${excluded.joinToString(" or ")}"
                }
            }
        }
        require(violations.isEmpty()) {
            "External-game test markers must stay in the excluded test packages:\n" +
                    violations.joinToString("\n")
        }
    }
}
