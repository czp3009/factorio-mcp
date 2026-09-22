import org.jetbrains.kotlin.gradle.tasks.KotlinNativeLink

plugins {
    distribution
    alias(libs.plugins.kotlinMultiplatform)
}

group = "com.hiczp"
version = "0.0.1"

val nativeSources = layout.projectDirectory.dir("src/linuxX64Main/native")
val nativeOutput = layout.buildDirectory.dir("native")
val configureNativeBridge = tasks.register<Exec>("configureNativeBridge") {
    inputs.files(fileTree(nativeSources) { include("CMakeLists.txt", "cmake/**") })
    outputs.file(nativeOutput.map { it.file("CMakeCache.txt") })
    commandLine(
        "cmake", "-S", nativeSources.asFile, "-B", nativeOutput.get().asFile,
        "-DCMAKE_BUILD_TYPE=RelWithDebInfo"
    )
}
val buildNativeBridge = tasks.register<Exec>("buildNativeBridge") {
    dependsOn(configureNativeBridge)
    inputs.dir(nativeSources)
    inputs.dir("src/linuxX64Test/native")
    outputs.files(
        nativeOutput.map { it.file("libfactorio_bridge.a") },
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
                extraOpts("-libraryPath", nativeOutput.get().asFile.absolutePath)
            }
        }
        binaries.executable {
            entryPoint = "com.hiczp.factorio.mcp.main"
        }
    }
}

tasks.matching { it.name == "cinteropBridgeLinuxX64" }.configureEach {
    dependsOn(buildNativeBridge)
}

val testNativeBridge = tasks.register<Exec>("testNativeBridge") {
    dependsOn(buildNativeBridge)
    commandLine("ctest", "--test-dir", nativeOutput.get().asFile, "--output-on-failure")
}
tasks.named("check") { dependsOn(testNativeBridge) }

val executableName = project.name
val releaseExecutable = tasks.named<KotlinNativeLink>("linkReleaseExecutableLinuxX64")
distributions {
    main {
        contents {
            from(files(releaseExecutable.map { it.outputFile.get() }).builtBy(releaseExecutable)) {
                into("bin")
                rename(".*", executableName)
                filePermissions { unix("755") }
            }
        }
    }
}
