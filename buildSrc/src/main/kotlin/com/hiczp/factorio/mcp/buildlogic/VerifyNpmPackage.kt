package com.hiczp.factorio.mcp.buildlogic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** Verifies the generated packages through an offline installation without publishing them. */
@DisableCachingByDefault(because = "Runs the installed host executable and the local npm toolchain.")
abstract class VerifyNpmPackage : DefaultTask() {
    @get:Inject
    abstract val fileSystemOperations: FileSystemOperations

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val mainPackageDirectory: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val platformPackageDirectory: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val releasePlatforms: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val executableFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val residentFile: RegularFileProperty

    @get:Input
    abstract val packageName: Property<String>

    @get:Input
    abstract val releaseVersion: Property<String>

    @get:Input
    abstract val npmPlatform: Property<String>

    @get:Input
    abstract val npmExecutable: Property<String>

    @get:OutputDirectory
    abstract val reportDirectory: DirectoryProperty

    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun verify() {
        val report = reportDirectory.get().asFile
        fileSystemOperations.delete { delete(report) }
        report.mkdirs()
        val workspace = Files.createTempDirectory(temporaryDir.toPath(), "consumer-").toFile()
        try {
            val config = workspace.resolve("empty.npmrc").apply { writeText("") }
            val environment = mapOf(
                "NPM_CONFIG_USERCONFIG" to config.path,
                "NPM_CONFIG_GLOBALCONFIG" to workspace.resolve("global.npmrc").apply { writeText("") }.path,
                "NPM_CONFIG_CACHE" to workspace.resolve("cache").path,
                "NPM_CONFIG_OFFLINE" to "true",
            )
            val main = mainPackageDirectory.get().asFile
            val platform = platformPackageDirectory.get().asFile
            val mainMetadata = metadata(main)
            val platformMetadata = metadata(platform)
            validateMetadata(mainMetadata, platformMetadata)
            val mainArchive = pack(main, report.resolve("main"), environment)
            val platformArchive = pack(platform, report.resolve("platform"), environment)
            val files = packedFiles(platformArchive.second)
            check(files == setOf("package.json", "bin/${executableFile.get().asFile.name}", "bin/${residentFile.get().asFile.name}")) {
                "Unexpected native package contents: $files"
            }
            val mainFiles = packedFiles(mainArchive.second)
            check(mainFiles.containsAll(listOf("package.json", "bin/factorio-mcp.js", "README.md", "README-zh-cn.md", "tools.md"))) {
                "Launcher package is missing required documentation or its command: $mainFiles"
            }
            check(mainFiles.all { it in setOf("package.json", "bin/factorio-mcp.js", "README.md", "README-zh-cn.md", "tools.md") || it.startsWith("images/") }) {
                "Unexpected launcher package contents: $mainFiles"
            }

            val consumer = workspace.resolve("consumer").apply { mkdirs() }
            consumer.resolve("package.json").writeText(buildJsonObject { put("private", true) }.toString())
            command(
                consumer,
                environment,
                report.resolve("install.log"),
                npmExecutable.get(), "install", "--offline", "--ignore-scripts", "--no-audit", "--no-fund",
                "--package-lock=false", mainArchive.first.path, platformArchive.first.path,
            )
            val installedMain = consumer.resolve("node_modules/${packageName.get()}")
            val installedPlatform = consumer.resolve("node_modules/${platformMetadata.string("name")}")
            check(metadata(installedMain) == mainMetadata && metadata(installedPlatform) == platformMetadata) {
                "The installed manifests differ from the unmodified package manifests"
            }
            for (source in listOf(executableFile.get().asFile, residentFile.get().asFile)) {
                val installed = installedPlatform.resolve("bin/${source.name}")
                check(Files.mismatch(source.toPath(), installed.toPath()) == -1L) {
                    "Installed ${source.name} differs from the release build"
                }
            }
            val installedBinary = installedPlatform.resolve(platformMetadata.getValue("kotlinNativeNpmPublishing").jsonObject.string("binary"))
            if (!npmPlatform.get().startsWith("win32-")) {
                check(Files.isExecutable(installedBinary.toPath())) { "Installed native binary lost execute permission" }
                val mode = platformArchive.second.getValue("files").jsonArray.single {
                    it.jsonObject.string("path") == "bin/${executableFile.get().asFile.name}"
                }.jsonObject.getValue("mode").jsonPrimitive.int
                check(mode and 0b001001001 != 0) { "Tarball native binary has no executable permission" }
            }
            val commandShim = consumer.resolve("node_modules/.bin/factorio-mcp" + if (npmPlatform.get().startsWith("win32-")) ".cmd" else "")
            check(commandShim.isFile) { "npm did not install the factorio-mcp command" }
            verifyStdio(installedMain.resolve("bin/factorio-mcp.js"), consumer, report)
            report.resolve("result.txt").writeText(
                "Verified ${npmPlatform.get()} ${releaseVersion.get()}: archive contents, offline tarball installation, " +
                    "release binary identity, installed command, detached MCP initialize/status and clean EOF.\n",
            )
            logger.lifecycle("Verified npm package for ${npmPlatform.get()}; report: $report")
        } finally {
            fileSystemOperations.delete { delete(workspace) }
        }
    }

    private fun validateMetadata(main: JsonObject, platform: JsonObject) {
        check(main.string("name") == packageName.get() && main.string("version") == releaseVersion.get())
        check(main.getValue("bin").jsonObject == buildJsonObject { put("factorio-mcp", "bin/factorio-mcp.js") })
        val declared = main.getValue("kotlinNativeNpmPublishing").jsonObject.getValue("platformPackages").jsonObject
        val enabled = Json.parseToJsonElement(releasePlatforms.get().asFile.readText()).jsonArray
            .map { it.jsonObject }.filter { it.getValue("enabled").jsonPrimitive.boolean }
            .map { it.string("id").replaceFirst("windows-", "win32-").replaceFirst("macos-", "darwin-") }.toSet()
        check(declared.keys == enabled) { "Launcher platforms ${declared.keys} differ from release platforms $enabled" }
        check(main.getValue("optionalDependencies").jsonObject == buildJsonObject {
            declared.values.forEach { put(it.jsonPrimitive.content, releaseVersion.get()) }
        }) { "Launcher dependencies must pin every platform package to the release version" }
        check(platform.string("name") == declared.getValue(npmPlatform.get()).jsonPrimitive.content)
        check(platform.string("version") == releaseVersion.get())
        check(platform.getValue("os").jsonArray.single().jsonPrimitive.content == npmPlatform.get().substringBeforeLast('-'))
        check(platform.getValue("cpu").jsonArray.single().jsonPrimitive.content == npmPlatform.get().substringAfterLast('-'))
        check(platform.getValue("kotlinNativeNpmPublishing").jsonObject.string("binary") == "bin/${executableFile.get().asFile.name}")
    }

    private fun pack(directory: File, destination: File, environment: Map<String, String>): Pair<File, JsonObject> {
        destination.mkdirs()
        val output = destination.resolve("pack.json")
        command(directory, environment, output, npmExecutable.get(), "pack", "--offline", "--ignore-scripts", "--json", "--pack-destination", destination.path)
        val result = Json.parseToJsonElement(output.readText())
        val entries = when (result) {
            is JsonArray -> result.toList()
            is JsonObject -> result.map { (name, entry) ->
                check(entry.jsonObject.string("name") == name) { "npm pack returned a mismatched package key" }
                entry
            }
            else -> error("Unsupported npm pack response: $result")
        }
        val packed = entries.single().jsonObject
        check(packed.string("name") == metadata(directory).string("name") && packed.string("version") == releaseVersion.get())
        val archive = destination.resolve(packed.string("filename"))
        check(archive.isFile && archive.length() > 0)
        return archive to packed
    }

    private fun packedFiles(packed: JsonObject) =
        packed.getValue("files").jsonArray.map { it.jsonObject.string("path") }.toSet()

    private fun command(directory: File, environment: Map<String, String>, output: File, vararg args: String) {
        val process = ProcessBuilder(args.toList()).directory(directory).apply {
            environment().putAll(environment)
            redirectOutput(output)
            redirectError(File(output.path + ".stderr"))
        }.start()
        try {
            check(process.waitFor(120, TimeUnit.SECONDS)) { "Package verification command exceeded its watchdog: ${args.first()}" }
            check(process.exitValue() == 0) { "Package verification command failed; see $output and ${output.path}.stderr" }
        } finally {
            stopOwnedProcess(process)
        }
    }

    private fun verifyStdio(launcher: File, directory: File, report: File) {
        val process = ProcessBuilder("node", launcher.path, "--no-http").directory(directory)
            .redirectError(report.resolve("stdio.stderr")).start()
        val readerExecutor = Executors.newSingleThreadExecutor()
        try {
            val input = process.outputStream.bufferedWriter()
            val output = process.inputStream.bufferedReader()
            fun send(value: JsonObject) {
                input.write(value.toString())
                input.newLine()
                input.flush()
            }
            fun response(id: Int): JsonObject {
                val line = readerExecutor.submit(Callable { output.readLine() }).get(30, TimeUnit.SECONDS)
                check(line != null) { "Installed launcher closed stdout before response $id" }
                report.resolve("stdio.jsonl").appendText("$line\n")
                val message = Json.parseToJsonElement(line).jsonObject
                check(message.string("jsonrpc") == "2.0" && message.getValue("id").jsonPrimitive.int == id)
                return message.getValue("result").jsonObject
            }
            send(buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", 1)
                put("method", "initialize")
                putJsonObject("params") {
                    put("protocolVersion", "2025-11-25")
                    putJsonObject("capabilities") {}
                    putJsonObject("clientInfo") {
                        put("name", "npm-package-verification")
                        put("version", "1")
                    }
                }
            })
            val initialized = response(1)
            val info = initialized.getValue("serverInfo").jsonObject
            check(info.string("name") == "factorio-mcp" && info.string("version") == releaseVersion.get())
            send(buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", "notifications/initialized")
            })
            send(buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", 2)
                put("method", "tools/call")
                putJsonObject("params") {
                    put("name", "status")
                    putJsonObject("arguments") {}
                }
            })
            val statusResult = response(2)
            check(statusResult["isError"]?.jsonPrimitive?.boolean != true)
            val status = Json.parseToJsonElement(statusResult.getValue("content").jsonArray.single().jsonObject.string("text")).jsonObject
            check(!status.getValue("attached").jsonPrimitive.boolean && status.string("state") == "detached")
            input.close()
            check(process.waitFor(30, TimeUnit.SECONDS)) { "Installed launcher did not exit after EOF" }
            check(process.exitValue() == 0 && output.readText().isEmpty()) { "Installed launcher failed or wrote unexpected stdout" }
        } finally {
            stopOwnedProcess(process)
            readerExecutor.shutdownNow()
        }
    }

    private fun stopOwnedProcess(process: Process) {
        if (!process.isAlive) return
        process.descendants().use { children -> children.forEach { it.destroyForcibly() } }
        process.destroyForcibly()
        process.waitFor(10, TimeUnit.SECONDS)
    }

    private fun metadata(directory: File) = Json.parseToJsonElement(directory.resolve("package.json").readText()).jsonObject

    private fun JsonObject.string(name: String) = getValue(name).jsonPrimitive.content
}
