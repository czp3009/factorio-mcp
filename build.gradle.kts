import com.hiczp.factorio.mcp.buildlogic.ConfigureNativeBridge
import org.jetbrains.kotlin.gradle.utils.NativeCompilerDownloader
import org.jetbrains.kotlin.konan.target.HostManager
import org.jetbrains.kotlin.konan.target.KonanTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

group = "com.hiczp"
version = "0.0.1"

val nativeSources = layout.projectDirectory.dir("src/linuxX64Main/native")
// CMake caches contain host paths and cannot be shared between Windows and WSL.
val nativeOutput = layout.buildDirectory.dir("native/${HostManager.host.name}/linuxX64")
val nativeBridgeArchive = nativeOutput.map { it.file("libfactorio_bridge.a") }
val kotlinNativeDataDirectory = providers.gradleProperty("konan.data.dir")
    .orElse(providers.environmentVariable("KONAN_DATA_DIR"))
    .orElse(providers.systemProperty("user.home").map { "$it/.konan" })
val kotlinNativeHome = providers.gradleProperty("kotlin.native.home")
    .getOrElse(NativeCompilerDownloader(project).compilerDirectory.absolutePath)
val configureNativeBridge = tasks.register<ConfigureNativeBridge>("configureNativeBridge") {
    // Binding generation provisions Kotlin/Native's matching compiler and Linux sysroot.
    dependsOn("cinteropBridgeLinuxX64")
    nativeHome.set(kotlinNativeHome)
    konanDataDirectory.set(kotlinNativeDataDirectory)
    generator.set(if (HostManager.hostIsMingw) "Ninja" else "Unix Makefiles")
    cmakeSources.set(nativeSources)
    cmakeFiles.from(fileTree(nativeSources) { include("CMakeLists.txt", "cmake/**") })
    konanProperties.set(file("$kotlinNativeHome/konan/konan.properties"))
    outputDirectory.set(nativeOutput)
}
val buildNativeBridge = tasks.register<Exec>("buildNativeBridge") {
    dependsOn(configureNativeBridge)
    inputs.files(configureNativeBridge).withPropertyName("cmakeConfiguration")
    inputs.dir(nativeSources)
    inputs.dir("src/linuxX64Test/native")
    outputs.files(
        nativeBridgeArchive,
        nativeOutput.map { it.file("libfactorio_resident.so") },
        nativeOutput.map { it.file("resident_hooks_test") },
        nativeOutput.map { it.file("resident_delivery_test") },
        nativeOutput.map { it.file("debug_image_test") }, nativeOutput.map { it.file("trace_lifecycle_test") })
    commandLine("cmake", "--build", nativeOutput.get().asFile, "--parallel")
}

kotlin {
    sourceSets.commonMain.dependencies {
        implementation(libs.mcp.server)
        implementation(libs.ktor.server.cio)
        implementation(libs.serialization.json)
        implementation(libs.io.core)
        implementation(libs.coroutines.core)
    }
    linuxX64 {
        compilations.getByName("main") {
            cinterops.create("bridge") {
                includeDirs(nativeSources.asFile)
            }
        }
        binaries.executable {
            entryPoint = "com.hiczp.factorio.mcp.main"
        }
        // IDE import generates bindings from headers; only binary linking needs CMake's archive.
        binaries.configureEach {
            linkerOpts(nativeBridgeArchive.get().asFile.absolutePath)
            linkTaskProvider.configure {
                dependsOn(buildNativeBridge)
                inputs.file(nativeBridgeArchive)
                    .withPropertyName("nativeBridgeArchive")
                    .withPathSensitivity(PathSensitivity.NONE)
            }
        }
    }
}

val testNativeBridge = tasks.register<Exec>("testNativeBridge") {
    dependsOn(buildNativeBridge)
    onlyIf("Native fixtures must run on Linux x86-64") { HostManager.host == KonanTarget.LINUX_X64 }
    commandLine("ctest", "--test-dir", nativeOutput.get().asFile, "--output-on-failure")
}
tasks.named("check") { dependsOn(testNativeBridge) }
