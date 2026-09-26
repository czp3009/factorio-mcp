import com.hiczp.factorio.mcp.buildlogic.WindowsNativeBuild
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest
import org.jetbrains.kotlin.gradle.utils.NativeCompilerDownloader
import org.jetbrains.kotlin.konan.target.HostManager
import org.jetbrains.kotlin.konan.target.KonanTarget
import java.nio.file.Files

plugins { alias(libs.plugins.kotlinMultiplatform) }

group = "com.hiczp"

version = "0.1.0"

val kotlinNativeHome =
    providers
        .gradleProperty("kotlin.native.home")
        .getOrElse(NativeCompilerDownloader(project).compilerDirectory.absolutePath)
val konanData =
    providers
        .gradleProperty("konan.data.dir")
        .orElse(providers.environmentVariable("KONAN_DATA_DIR"))
        .orElse(providers.systemProperty("user.home").map { "$it/.konan" })

val nativeBuild =
    tasks.register<WindowsNativeBuild>("buildWindowsNative") {
        onlyIf("This adapter requires a Windows x64 build host") {
            HostManager.host == KonanTarget.MINGW_X64
        }
        dependsOn("cinteropBridgeMingwX64")
        nativeHome.set(kotlinNativeHome)
        konanDataDirectory.set(konanData)
        sourceDirectory.set(layout.projectDirectory.dir("src/mingwX64Main/native"))
        testDirectory.set(layout.projectDirectory.dir("src/mingwX64Test/native"))
        outputDirectory.set(layout.buildDirectory.dir("native/${HostManager.host.name}/mingwX64"))
        localConfiguration.set(
            layout.projectDirectory.file("local.properties").takeIf { it.asFile.exists() }
        )
    }

kotlin {
    mingwX64 {
        compilations.getByName("main").cinterops.create("bridge") {
            includeDirs("src/mingwX64Main/native")
        }
        binaries.executable { entryPoint = "com.hiczp.factorio.mcp.main" }
        binaries.configureEach {
            linkTaskProvider.configure {
                dependsOn(nativeBuild)
                val runtime = outputFile.map { it.parentFile.resolve("factorio_bridge.dll") }
                val sourceDll =
                    nativeBuild.get().outputDirectory.file("factorio_bridge.dll").get().asFile
                outputs.file(runtime)
                inputs.file(sourceDll)
                doLast {
                    val destination = runtime.get()
                    if (
                        !destination.exists() ||
                        Files.mismatch(sourceDll.toPath(), destination.toPath()) != -1L
                    )
                        sourceDll.copyTo(destination, overwrite = true)
                }
            }
        }
    }
    sourceSets {
        commonMain.dependencies {
            implementation(libs.mcp.server)
            implementation(libs.ktor.server.cio)
            implementation(libs.serialization.json)
            implementation(libs.io.core)
            implementation(libs.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.ktor.client.core)
        }
        mingwX64Test.dependencies { implementation(libs.ktor.client.winhttp) }
    }
}

val testWindowsNative =
    tasks.register<Exec>("testWindowsNative") {
        dependsOn(nativeBuild)
        onlyIf("Windows native fixtures require a Windows x64 host") {
            HostManager.host == KonanTarget.MINGW_X64
        }
        commandLine(
            "ctest",
            "--test-dir",
            nativeBuild.get().outputDirectory.get().asFile,
            "--output-on-failure",
        )
    }

if (HostManager.host == KonanTarget.MINGW_X64) tasks.named("check") { dependsOn(testWindowsNative) }

tasks.named<KotlinNativeTest>("mingwX64Test") {
    if (!providers.environmentVariable("FACTORIO_MCP_TEST_EXECUTABLE").orNull.isNullOrBlank()) {
        dependsOn("linkDebugExecutableMingwX64")
    }
    listOf(
        "FACTORIO_MCP_ACCEPTANCE_URL",
        "FACTORIO_MCP_TEST_PID",
        "FACTORIO_MCP_TEST_PDB",
        "FACTORIO_MCP_TEST_EXECUTABLE",
        "FACTORIO_MCP_UI_SERVER_LOG",
        "FACTORIO_MCP_UI_CLIENT_LOG",
    )
        .forEach { name ->
            environment(name, providers.environmentVariable(name).orElse("").get(), true)
        }
}
