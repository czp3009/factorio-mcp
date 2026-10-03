@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import kotlinx.cinterop.*
import platform.posix.*

/** An open proc directory pins process identity so PID reuse cannot retarget later reads. */
internal class ProcessHandle(val pid: Int) : AutoCloseable {
    private var directory: Int
    private var executableMapping: MappedBinary? = null
    private var executableImage: ElfImage? = null

    init {
        require(pid > 0) { "Expected a positive process ID" }
        directory = open("/proc/$pid", O_RDONLY or O_DIRECTORY or O_CLOEXEC)
        check(directory >= 0) { "Cannot open process $pid (errno $errno)" }
    }

    private fun descriptor(): Int = directory.also { check(it >= 0) { "Process handle is closed" } }

    private fun procPath(name: String): String = "/proc/self/fd/${descriptor()}/$name"

    fun fileDescriptorPath(number: Int): String {
        require(number >= 0)
        return procPath("fd/$number")
    }

    fun executablePath(): String = memScoped {
        val buffer = allocArray<ByteVar>(65536)
        val length = readlink(procPath("exe"), buffer, 65536u)
        check(length in 1..65535) { "Cannot resolve process executable (errno $errno)" }
        buffer.readBytes(length.toInt()).decodeToString(throwOnInvalidSequence = true)
    }

    fun <T> withExecutable(action: (ElfImage) -> T): T {
        // Opening via this proc descriptor reads the loaded inode even after path replacement.
        val path = procPath("exe")
        val mapping = executableMapping ?: MappedBinary(path).also { executableMapping = it }
        check(BinaryFileIdentity.read(path) == mapping.identity) {
            "Selected executable changed; detach before attaching again"
        }
        val image = executableImage ?: ElfImage(mapping.view).also { executableImage = it }
        return action(image)
    }

    fun <T> withMappedFile(mapping: ProcMapping, action: (ElfImage) -> T): T {
        val path = checkNotNull(mapping.path) { "Mapped module has no file path" }
        require(path.startsWith('/') && !path.endsWith(" (deleted)")) {
            "Mapped module file is unavailable"
        }
        val file = open(procPath("root$path"), O_RDONLY or O_CLOEXEC)
        check(file >= 0) { "Cannot open mapped module: $path (errno $errno)" }
        try {
            memScoped {
                val info = alloc<stat>()
                check(fstat(file, info.ptr) == 0) { "Cannot identify mapped module (errno $errno)" }
                val device = info.st_dev
                val major = ((device shr 8) and 0xfffu) or ((device shr 32) and 0xfffff000u)
                val minor = (device and 0xffu) or ((device shr 12) and 0xffffff00u)
                require(
                    mapping.deviceMajor.toULong() == major &&
                        mapping.deviceMinor.toULong() == minor &&
                        mapping.inode.toULong() == info.st_ino
                ) {
                    "Mapped module's path now identifies a different file"
                }
            }
            return MappedBinary("/proc/self/fd/$file").use { action(ElfImage(it.view)) }
        } finally {
            close(file)
        }
    }

    fun mappings(): List<ProcMapping> = ProcMapping.parse(readText("maps", 16 * 1024 * 1024))

    fun executableMappings(mappings: List<ProcMapping> = mappings()): List<ProcMapping> {
        val executable = open(procPath("exe"), O_RDONLY or O_CLOEXEC)
        check(executable >= 0) { "Cannot open loaded executable (errno $errno)" }
        try {
            return memScoped {
                val info = alloc<stat>()
                check(fstat(executable, info.ptr) == 0) {
                    "Cannot identify loaded executable (errno $errno)"
                }
                val device = info.st_dev
                val major = ((device shr 8) and 0xfffu) or ((device shr 32) and 0xfffff000u)
                val minor = (device and 0xffu) or ((device shr 12) and 0xffffff00u)
                mappings
                    .filter {
                        it.deviceMajor.toULong() == major &&
                            it.deviceMinor.toULong() == minor &&
                            it.inode.toULong() == info.st_ino
                    }
                    .also {
                        require(it.isNotEmpty()) { "Loaded executable has no process mappings" }
                    }
            }
        } finally {
            close(executable)
        }
    }

    fun alive(): Boolean = state()?.let { it !in setOf('Z', 'X', 'x') } ?: false

    /** Kernel task state only; this does not describe the game's pause or simulation state. */
    fun state(): Char? {
        val descriptor = open(procPath("stat"), O_RDONLY or O_CLOEXEC)
        if (descriptor < 0) {
            if (errno == ENOENT || errno == ESRCH) return null
            error("Cannot check process liveness (errno $errno)")
        }
        try {
            val status = readText(descriptor, 65536)
            // comm can contain spaces, parentheses and newlines; the final ')' terminates it.
            val delimiter = status.lastIndexOf(')')
            require(
                delimiter >= 0 && delimiter + 3 < status.length && status[delimiter + 1] == ' '
            ) {
                "Invalid process stat record"
            }
            return status[delimiter + 2]
        } finally {
            close(descriptor)
        }
    }

    fun readMemory(address: Long, size: Int): ByteArray {
        require(address >= 0 && size in 1..(16 * 1024 * 1024) && address <= Long.MAX_VALUE - size)
        val memory = open(procPath("mem"), O_RDONLY or O_CLOEXEC)
        check(memory >= 0) {
            "Cannot read process memory; Linux ptrace permission is required (errno $errno)"
        }
        try {
            val output = ByteArray(size)
            output.usePinned { bytes ->
                var offset = 0
                while (offset < size) {
                    val count =
                        pread(
                            memory,
                            bytes.addressOf(offset),
                            (size - offset).toULong(),
                            address + offset,
                        )
                    if (count < 0 && errno == EINTR) continue
                    check(count > 0) { "Incomplete process memory read (errno $errno)" }
                    offset += count.toInt()
                }
            }
            return output
        } finally {
            close(memory)
        }
    }

    /** Bootstrap scratch storage only. Never bypass read-only or executable mapping protections. */
    fun writeData(address: Long, data: ByteArray) {
        require(
            address >= 0 &&
                data.size in 1..(16 * 1024 * 1024) &&
                address <= Long.MAX_VALUE - data.size
        )
        require(
            mappings().any {
                it.readable &&
                    it.writable &&
                    !it.executable &&
                    address >= it.start &&
                    address + data.size <= it.end
            }
        ) {
            "Remote data write must fit a readable, writable, non-executable mapping"
        }
        val memory = open(procPath("mem"), O_WRONLY or O_CLOEXEC)
        check(memory >= 0) { "Cannot open process data for bootstrap (errno $errno)" }
        try {
            data.usePinned { bytes ->
                var offset = 0
                while (offset < data.size) {
                    val count =
                        pwrite(
                            memory,
                            bytes.addressOf(offset),
                            (data.size - offset).toULong(),
                            address + offset,
                        )
                    if (count < 0 && errno == EINTR) continue
                    check(count > 0) { "Incomplete bootstrap data write (errno $errno)" }
                    offset += count.toInt()
                }
            }
        } finally {
            close(memory)
        }
    }

    fun shadowStackEnabled(): Boolean {
        val features =
            readText("status", 65536)
                .lineSequence()
                .filter { it.startsWith("x86_Thread_features:") }
                .toList()
        require(features.size <= 1) { "Ambiguous Linux thread features" }
        return features.singleOrNull()?.substringAfter(':')?.split(' ', '\t')?.any {
            it == "shstk"
        } == true
    }

    private fun readText(name: String, maximum: Int): String {
        val file = open(procPath(name), O_RDONLY or O_CLOEXEC)
        check(file >= 0) { "Cannot read process $name (errno $errno)" }
        try {
            return readText(file, maximum)
        } finally {
            close(file)
        }
    }

    private fun readText(file: Int, maximum: Int): String {
        val chunks = mutableListOf<ByteArray>()
        var total = 0
        val buffer = ByteArray(8192)
        while (true) {
            val count = buffer.usePinned { read(file, it.addressOf(0), buffer.size.toULong()) }
            if (count < 0 && errno == EINTR) continue
            check(count >= 0) { "Cannot read process metadata (errno $errno)" }
            if (count == 0L) break
            check(count <= maximum - total) { "Process metadata exceeds supported bound" }
            total += count.toInt()
            chunks += buffer.copyOf(count.toInt())
        }
        val bytes = ByteArray(total)
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(bytes, offset)
            offset += chunk.size
        }
        return bytes.decodeToString(throwOnInvalidSequence = true)
    }

    override fun close() {
        executableMapping?.let {
            it.close()
            executableMapping = null
            executableImage = null
        }
        if (directory < 0) return
        // Linux releases the descriptor even when close reports EINTR; never retry a reused number.
        val file = directory
        directory = -1
        check(close(file) == 0 || errno == EINTR) { "Cannot close process handle (errno $errno)" }
    }
}

internal fun discoverProcesses(name: String): List<Int> {
    require(name.isNotBlank() && '/' !in name && '\u0000' !in name) {
        "Expected an executable base name"
    }
    val directory = checkNotNull(opendir("/proc")) { "Cannot enumerate /proc (errno $errno)" }
    try {
        val result = mutableListOf<Int>()
        while (true) {
            set_posix_errno(0)
            val entry = readdir(directory)
            if (entry == null) {
                check(errno == 0) { "Cannot enumerate processes (errno $errno)" }
                break
            }
            val pid = entry.pointed.d_name.toKString().toIntOrNull() ?: continue
            // Other users' processes and exits during discovery are ordinary nonmatches.
            val matches =
                runCatching {
                        ProcessHandle(pid).use { process ->
                            process.executablePath().substringAfterLast('/') == name &&
                                process.alive()
                        }
                    }
                    .getOrDefault(false)
            if (matches) result += pid
        }
        return result.sorted()
    } finally {
        closedir(directory)
    }
}
