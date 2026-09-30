package com.hiczp.factorio.mcp.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.process.ExecOperations
import org.jetbrains.kotlin.konan.target.Distribution
import org.jetbrains.kotlin.konan.target.KonanTarget
import org.jetbrains.kotlin.konan.target.PlatformManager
import javax.inject.Inject

abstract class LinuxNativeBuild @Inject constructor(private val processes: ExecOperations) : DefaultTask() {
    init {
        // Let Ninja check compiler-discovered system headers without relinking unchanged artifacts.
        outputs.upToDateWhen { false }
    }

    @get:Input
    abstract val nativeHome: Property<String>

    @get:Input
    abstract val konanDataDirectory: Property<String>

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun build() {
        val platform = PlatformManager(Distribution(nativeHome.get(), konanDataDir = konanDataDirectory.get()))
            .loader(KonanTarget.LINUX_X64)
        val compilerDirectory = "${platform.absoluteLlvmHome}/bin"
        processes.exec {
            commandLine(
                "cmake", "-S", sourceDirectory.get().asFile, "-B", outputDirectory.get().asFile,
                "-G", "Ninja", "-DCMAKE_BUILD_TYPE=Release", "-DCMAKE_C_COMPILER=$compilerDirectory/clang",
                "-DCMAKE_CXX_COMPILER=$compilerDirectory/clang++",
                "-DCMAKE_ASM_COMPILER=$compilerDirectory/clang"
            )
        }
        processes.exec { commandLine("cmake", "--build", outputDirectory.get().asFile, "--parallel", "2") }
    }
}
