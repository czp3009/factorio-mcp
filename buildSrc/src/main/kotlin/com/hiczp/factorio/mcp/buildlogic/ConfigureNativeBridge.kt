package com.hiczp.factorio.mcp.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.jetbrains.kotlin.konan.target.Distribution
import org.jetbrains.kotlin.konan.target.GccConfigurables
import org.jetbrains.kotlin.konan.target.HostManager
import org.jetbrains.kotlin.konan.target.KonanTarget
import org.jetbrains.kotlin.konan.target.PlatformManager
import javax.inject.Inject

abstract class ConfigureNativeBridge @Inject constructor(private val execOperations: ExecOperations) : DefaultTask() {
    @get:Input
    abstract val nativeHome: Property<String>

    @get:Input
    abstract val konanDataDirectory: Property<String>

    @get:Input
    abstract val generator: Property<String>

    @get:Internal
    abstract val cmakeSources: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val cmakeFiles: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val konanProperties: RegularFileProperty

    @get:Internal
    abstract val outputDirectory: DirectoryProperty

    @get:OutputFile
    val cacheFile: Provider<RegularFile> get() = outputDirectory.file("CMakeCache.txt")

    @get:OutputFile
    val buildFile: Provider<RegularFile>
        get() = outputDirectory.file(generator.map { if (it == "Ninja") "build.ninja" else "Makefile" })

    @TaskAction
    fun configure() {
        val platforms = PlatformManager(Distribution(nativeHome.get(), konanDataDir = konanDataDirectory.get()))
        val linux = platforms.loader(KonanTarget.LINUX_X64) as GccConfigurables
        val binutils = if (HostManager.hostIsMingw) {
            platforms.hostPlatform.absoluteTargetToolchain
        } else {
            linux.absoluteTargetToolchain
        }
        execOperations.exec {
            commandLine(
                "cmake", "--fresh", "-S", cmakeSources.get().asFile, "-B", outputDirectory.get().asFile,
                "-G", generator.get(), "-DCMAKE_BUILD_TYPE=RelWithDebInfo",
                "-DCMAKE_TOOLCHAIN_FILE=${cmakeSources.file("cmake/KonanLinuxX64.cmake").get().asFile.invariantSeparatorsPath}",
                "-DKONAN_LLVM_HOME=${linux.absoluteLlvmHome}",
                "-DKONAN_GCC_TOOLCHAIN=${linux.absoluteGccToolchain}",
                "-DKONAN_SYSROOT=${linux.absoluteTargetSysRoot}",
                "-DKONAN_BINUTILS=$binutils",
                "-DKONAN_LINKER=${linux.absoluteLinker}",
                "-DKONAN_TARGET_TRIPLE=${linux.targetTriple}"
            )
        }
    }
}
