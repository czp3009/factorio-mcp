import com.hiczp.factorio.mcp.buildlogic.GenerateBuildVersion
import com.hiczp.factorio.mcp.buildlogic.PrintVersion
import com.hiczp.factorio.mcp.buildlogic.ProjectInfo
import com.hiczp.factorio.mcp.buildlogic.WindowsNativeBuild
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest
import org.jetbrains.kotlin.gradle.utils.NativeCompilerDownloader
import org.jetbrains.kotlin.konan.target.HostManager
import java.nio.file.Files

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinNativeNpmPublishing)
}

group = "com.hiczp"

version = ProjectInfo.VERSION

tasks.register<PrintVersion>("printVersion") {
    group = "help"
    description = "Prints the project version; use --quiet for machine-readable output."
    releaseVersion.set(project.version.toString())
}

val generateBuildVersion = tasks.register<GenerateBuildVersion>("generateBuildVersion") {
    group = "build"
    description = "Generates the runtime version type with KotlinPoet."
    releaseVersion.set(project.version.toString())
    outputDirectory.set(layout.buildDirectory.dir("generated/build-version/kotlin"))
}

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
        commonMain { kotlin.srcDir(generateBuildVersion.flatMap { it.outputDirectory }) }
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

kotlinNativeNpmPublishing {
    packageName.set("@czp3009/factorio-mcp")
    description.set("MCP server for observing and interacting with a running Factorio client.")
    repository.set("https://github.com/czp3009/factorio-mcp")
    homepage.set("https://github.com/czp3009/factorio-mcp")
    keywords.addAll("factorio", "mcp", "kotlin-native")
    access.set("public")
    registry.set(providers.gradleProperty("npmRegistry"))
    otp.set(providers.gradleProperty("npmOtp"))
    stage {
        main {
            readme()
            copy(layout.projectDirectory.file("README-zh-cn.md"))
            copy(layout.projectDirectory.file("tools.md"))
            copy(layout.projectDirectory.dir("images"))
        }
        platforms {
            mingwX64 {
                val release =
                    kotlin.targets.getByName<KotlinNativeTarget>("mingwX64")
                        .binaries.getExecutable(NativeBuildType.RELEASE)
                copy(
                    release.linkTaskProvider
                        .flatMap { it.outputFile }
                        .map { it.parentFile.resolve("factorio_bridge.dll") },
                    "bin/factorio_bridge.dll",
                )
            }
        }
    }
}
