import com.hiczp.factorio.mcp.buildlogic.WindowsNativeBuild
import java.nio.file.Files
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest
import org.jetbrains.kotlin.gradle.utils.NativeCompilerDownloader
import org.jetbrains.kotlin.konan.target.HostManager

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

kotlin {
    mingwX64 {
        val mainDirectory = "src/${compilations.getByName("main").defaultSourceSet.name}"
        val testDirectory = "src/${compilations.getByName("test").defaultSourceSet.name}"
        compilations.getByName("main").cinterops.create("bridge") {
            definitionFile.set(layout.projectDirectory.file("$mainDirectory/cinterop/bridge.def"))
            includeDirs("$mainDirectory/native")
        }
        binaries.executable { entryPoint = "com.hiczp.factorio.mcp.main" }

        // These adapters execute host tools and require the Windows SDK.
        if (HostManager.host == konanTarget) {
            val taskSuffix = name.replaceFirstChar { it.uppercaseChar() }
            val outputScope = "${HostManager.host.name}/$name"
            val interop = compilations.getByName("main").cinterops.getByName("bridge")

            fun WindowsNativeBuild.configureToolchain() {
                dependsOn(interop.interopProcessingTaskName)
                nativeHome.set(kotlinNativeHome)
                konanDataDirectory.set(konanData)
                toolchainFile.set(
                    layout.projectDirectory.file("$mainDirectory/native/cmake/KonanWindows.cmake")
                )
                localConfiguration.set(
                    layout.projectDirectory.file("local.properties").takeIf { it.asFile.exists() }
                )
            }

            val nativeBuild =
                tasks.register<WindowsNativeBuild>("build${taskSuffix}Native") {
                    configureToolchain()
                    sourceDirectory.set(layout.projectDirectory.dir("$mainDirectory/native"))
                    outputDirectory.set(layout.buildDirectory.dir("native/$outputScope"))
                }
            val nativeFixtures =
                tasks.register<WindowsNativeBuild>("build${taskSuffix}NativeFixtures") {
                    configureToolchain()
                    sourceDirectory.set(layout.projectDirectory.dir("$testDirectory/native"))
                    inputs.dir("$testDirectory/lua")
                    inputs.dir("$mainDirectory/native")
                    outputDirectory.set(layout.buildDirectory.dir("native-tests/$outputScope"))
                }

            val executable = binaries.getExecutable(NativeBuildType.DEBUG)
            tasks.named<KotlinNativeTest>("${name}Test") {
                dependsOn(executable.linkTaskProvider, nativeFixtures)
                inputs.file(executable.linkTaskProvider.flatMap { it.outputFile })
                environment(
                    "FACTORIO_MCP_TEST_EXECUTABLE",
                    executable.outputFile.absolutePath,
                    true,
                )
                val luaFixture =
                    nativeFixtures.flatMap { it.outputDirectory.file("lua/query_lua_fixture.dll") }
                inputs.file(luaFixture)
                environment("FACTORIO_MCP_TEST_LUA_DLL", luaFixture.get().asFile.absolutePath, true)
                filter.excludeTestsMatching("*AcceptanceTest")
                filter.excludeTestsMatching("*OfflineQueryMetadataTest")
            }
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

            val nativeTest =
                tasks.register<Exec>("test${taskSuffix}Native") {
                    dependsOn(nativeFixtures)
                    commandLine(
                        "ctest",
                        "--test-dir",
                        nativeFixtures.get().outputDirectory.get().asFile,
                        "--output-on-failure",
                        "--no-tests=error",
                    )
                }
            tasks.named("check") { dependsOn(nativeTest) }
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
