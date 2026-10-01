import com.hiczp.factorio.mcp.buildlogic.*
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
    linuxX64 {
        val mainDirectory = "src/${compilations.getByName("main").defaultSourceSet.name}"
        val testDirectory = "src/${compilations.getByName("test").defaultSourceSet.name}"
        val interop = compilations.getByName("main").cinterops.create("linux") {
            definitionFile.set(layout.projectDirectory.file("$mainDirectory/cinterop/linux.def"))
            includeDirs("$mainDirectory/native")
        }
        compilations.getByName("test").cinterops.create("fixture") {
            definitionFile.set(layout.projectDirectory.file("$testDirectory/cinterop/fixture.def"))
            includeDirs("$testDirectory/native")
        }
        binaries.executable { entryPoint = "com.hiczp.factorio.mcp.main" }
        if (HostManager.host == konanTarget) {
            val taskSuffix = name.replaceFirstChar { it.uppercaseChar() }
            val outputScope = "${HostManager.host.name}/$name"
            fun LinuxNativeBuild.configureToolchain() {
                inputs.dir("src/nativeMain/native")
                dependsOn(interop.interopProcessingTaskName)
                nativeHome.set(kotlinNativeHome)
                konanDataDirectory.set(konanData)
            }

            val nativeBuild = tasks.register<LinuxNativeBuild>("build${taskSuffix}Native") {
                configureToolchain()
                sourceDirectory.set(layout.projectDirectory.dir("$mainDirectory/native"))
                outputDirectory.set(layout.buildDirectory.dir("native/$outputScope"))
            }
            val nativeFixtures = tasks.register<LinuxNativeBuild>("build${taskSuffix}NativeFixtures") {
                configureToolchain()
                sourceDirectory.set(layout.projectDirectory.dir("$testDirectory/native"))
                inputs.dir("$mainDirectory/native")
                inputs.dir("src/nativeTest/lua")
                inputs.dir("src/nativeTest/native")
                outputDirectory.set(layout.buildDirectory.dir("native-tests/$outputScope"))
            }
            tasks.named<KotlinNativeTest>("${name}Test") {
                dependsOn(nativeFixtures)
                inputs.files(nativeFixtures.flatMap { it.outputDirectory }.map { directory ->
                    directory.asFileTree.matching {
                        include("*fixture*", "resident/*.so")
                    }
                }).withPropertyName("nativeFixtures").withPathSensitivity(PathSensitivity.RELATIVE)
                environment(
                    "FACTORIO_MCP_TEST_NATIVE",
                    nativeFixtures.get().outputDirectory.get().asFile.absolutePath,
                    true
                )
                val luaFixture = nativeFixtures.flatMap { it.outputDirectory.file("lua/libquery_lua_fixture.so") }
                inputs.file(luaFixture)
                environment("FACTORIO_MCP_TEST_LUA_LIBRARY", luaFixture.get().asFile.absolutePath, true)
                filter.excludeTestsMatching("com.hiczp.factorio.mcp.acceptance.*")
            }
            binaries.configureEach {
                linkTaskProvider.configure {
                    dependsOn(nativeBuild)
                    val sourceLibrary = nativeBuild.flatMap { it.outputDirectory.file("libfactorio_mcp_resident.so") }
                    val runtime = outputFile.map { it.parentFile.resolve("libfactorio_mcp_resident.so") }
                    inputs.file(sourceLibrary)
                    outputs.file(runtime)
                    doLast {
                        val source = sourceLibrary.get().asFile
                        val destination = runtime.get()
                        if (!destination.exists() || Files.mismatch(source.toPath(), destination.toPath()) != -1L)
                            source.copyTo(destination, overwrite = true)
                    }
                }
            }
            val nativeTest = tasks.register<Exec>("test${taskSuffix}Native") {
                dependsOn(nativeFixtures)
                commandLine(
                    "ctest", "--test-dir", nativeFixtures.get().outputDirectory.get().asFile,
                    "--output-on-failure", "--no-tests=error"
                )
            }
            tasks.named("check") { dependsOn(nativeTest) }
        }
    }
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
                inputs.dir("src/nativeMain/native")
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
                    inputs.dir("src/nativeTest/lua")
                    inputs.dir("src/nativeTest/native")
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
                environment("FACTORIO_MCP_TEST_LUA_LIBRARY", luaFixture.get().asFile.absolutePath, true)
                filter.excludeTestsMatching("com.hiczp.factorio.mcp.acceptance.*")
                filter.excludeTestsMatching("com.hiczp.factorio.mcp.offline.*")
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
            implementation(libs.ktor.client.cio)
        }
    }
}

val checkTestPackageBoundaries =
    tasks.register<CheckTestPackageBoundaries>("checkTestPackageBoundaries") {
        group = "verification"
        description = "Rejects external-game test markers outside the excluded test packages."
        sources.from(
            kotlin.sourceSets
                .filter { it.name.endsWith("Test") }
                .map { it.kotlin },
        )
        excludedPackages.addAll(
            "com.hiczp.factorio.mcp.acceptance",
            "com.hiczp.factorio.mcp.offline",
        )
        requiredMarkers.addAll(
            "FACTORIO_MCP_ACCEPTANCE_URL",
            "FACTORIO_MCP_TEST_PID",
            "FACTORIO_MCP_UI_SERVER_LOG",
            "FACTORIO_MCP_UI_CLIENT_LOG",
        )
    }
tasks.named("check") { dependsOn(checkTestPackageBoundaries) }

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
            linuxX64 {
                val release = kotlin.targets.getByName<KotlinNativeTarget>("linuxX64")
                    .binaries.getExecutable(NativeBuildType.RELEASE)
                copy(
                    release.linkTaskProvider.flatMap { it.outputFile }
                    .map { it.parentFile.resolve("libfactorio_mcp_resident.so") },
                    "bin/libfactorio_mcp_resident.so"
                )
            }
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
