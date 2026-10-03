package com.hiczp.factorio.mcp.buildlogic

import java.util.Properties
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.process.ExecOperations
import org.jetbrains.kotlin.konan.target.Distribution
import org.jetbrains.kotlin.konan.target.KonanTarget
import org.jetbrains.kotlin.konan.target.PlatformManager

abstract class WindowsNativeBuild @Inject constructor(private val processes: ExecOperations) :
    DefaultTask() {
    init {
        // Ninja owns compiler-discovered SDK/header dependencies outside the Gradle source tree.
        // Always let it check them; unchanged outputs do not relink Kotlin binaries.
        outputs.upToDateWhen { false }
    }

    @get:Input
    abstract val nativeHome: Property<String>

    @get:Input
    abstract val konanDataDirectory: Property<String>

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDirectory: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val toolchainFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val localConfiguration: RegularFileProperty

    @TaskAction
    fun build() {
        val config = Properties()
        localConfiguration.orNull?.asFile?.inputStream()?.use(config::load)
        fun configured(property: String, variable: String) =
            config.getProperty("windows.native.$property") ?: System.getenv(variable)

        // These standard names belong only to the compiler subprocess interface. Project configuration
        // never falls back to an ambient INCLUDE or LIB from an unrelated shell or application.
        val environment = mapOf(
            "INCLUDE" to configured("includeDirectories", "FACTORIO_MCP_WINDOWS_NATIVE_INCLUDE_DIRECTORIES").orEmpty(),
            "LIB" to configured("libraryDirectories", "FACTORIO_MCP_WINDOWS_NATIVE_LIBRARY_DIRECTORIES").orEmpty(),
            "PATH" to listOfNotNull(
                configured("toolDirectories", "FACTORIO_MCP_WINDOWS_NATIVE_TOOL_DIRECTORIES"), System.getenv("PATH")
            ).joinToString(";"),
        )
        val directory = outputDirectory.get().asFile
        val platform =
            PlatformManager(Distribution(nativeHome.get(), konanDataDir = konanDataDirectory.get()))
                .loader(KonanTarget.MINGW_X64)
        processes.exec {
            environment(environment)
            commandLine(
                "cmake",
                "-S",
                sourceDirectory.get().asFile,
                "-B",
                directory,
                "-G",
                "Ninja",
                "-DCMAKE_BUILD_TYPE=Release",
                "-DCMAKE_TOOLCHAIN_FILE=${toolchainFile.get().asFile.invariantSeparatorsPath}",
                "-DKONAN_LLVM_HOME=${platform.absoluteLlvmHome}",
                "-DKONAN_DRIVER_DIR=${directory.resolve("drivers").invariantSeparatorsPath}",
            )
        }
        processes.exec {
            environment(environment)
            commandLine("cmake", "--build", directory, "--parallel")
        }
    }
}
