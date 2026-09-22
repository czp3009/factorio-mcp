@file:OptIn(ExperimentalForeignApi::class)

package com.hiczp.factorio.mcp

import factorio.bridge.*
import kotlinx.cinterop.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import platform.posix.*

/** Linux's local SOCK_SEQPACKET preserves packet boundaries; no application retransmission. */
internal class LinuxResidentWire(private val fd: Int) : ResidentWire {
    override fun send(id: String, operation: ResidentOperation, source: String, timeoutMillis: Int, generation: ULong) =
        memScoped {
            val body = source.encodeToByteArray()
            require(body.size <= FR_MAX_REQUEST.toInt() && timeoutMillis > 0) { "Invalid resident request" }
            val headerSize = sizeOf<FrRequest>().toInt()
            val packet = allocArray<ByteVar>(headerSize + body.size)
            memset(packet, 0, headerSize.toULong())
            val header = packet.reinterpret<FrRequest>().pointed
            header.version = FR_VERSION
            header.operation = when (operation) {
                ResidentOperation.Status -> FrOperation.FR_STATUS
                ResidentOperation.Task -> FrOperation.FR_LUA
                ResidentOperation.BindWorld -> FrOperation.FR_BIND_WORLD
            }.value
            val identifier = id.encodeToByteArray()
            require(identifier.size < sizeOf<FrId>()) { "Invalid task ID" }
            identifier.usePinned { memcpy(header.id.value, it.addressOf(0), identifier.size.toULong()) }
            header.length = body.size.toUInt()
            header.timeout_ms = timeoutMillis.toUInt()
            header.expected_generation = generation
            if (body.isNotEmpty()) body.usePinned { memcpy(packet + headerSize, it.addressOf(0), body.size.toULong()) }
            check(
                send(
                    fd,
                    packet,
                    (headerSize + body.size).toULong(),
                    MSG_DONTWAIT or MSG_NOSIGNAL
                ) == (headerSize + body.size).toLong()
            ) {
                "Cannot submit task to the resident: ${strerror(errno)?.toKString()}"
            }
        }

    override suspend fun receive(): ResidentPacket {
        while (true) {
            currentCoroutineContext().ensureActive()
            val ready = memScoped {
                val descriptor = alloc<pollfd>()
                descriptor.fd = fd
                descriptor.events = POLLIN.toShort()
                poll(descriptor.ptr, 1u, 100)
            }
            if (ready < 0 && errno == EINTR) continue
            check(ready >= 0) { "Cannot wait for resident IPC" }
            if (ready == 0) continue
            currentCoroutineContext().ensureActive()
            val packet = memScoped {
                val capacity = sizeOf<FrResponse>().toInt() + FR_MAX_RESPONSE.toInt()
                val bytes = allocArray<ByteVar>(capacity)
                val count = recv(fd, bytes, capacity.toULong(), MSG_DONTWAIT or MSG_TRUNC)
                if (count < 0 && (errno == EAGAIN || errno == EINTR)) return@memScoped null
                check(count in sizeOf<FrResponse>()..capacity.toLong()) { "Resident disconnected or sent an invalid packet" }
                val header = bytes.reinterpret<FrResponse>().pointed
                check(header.version == FR_VERSION) { "Resident version mismatch; restart Factorio before attaching this executable" }
                check(header.length.toLong() == count - sizeOf<FrResponse>()) { "Invalid resident response header" }
                check(memchr(header.id.value, 0, sizeOf<FrId>().toULong()) != null) { "Invalid response ID" }
                val body = (bytes + sizeOf<FrResponse>())!!.readBytes(header.length.toInt())
                check(header.kind == FrMessageKind.FR_COMPLETION.value || header.kind == FrMessageKind.FR_WORLD_CHANGED.value) { "Unknown resident message kind" }
                ResidentPacket(
                    header.id.value.toKString(),
                    header.failed != 0u,
                    body,
                    header.kind == FrMessageKind.FR_WORLD_CHANGED.value
                )
            }
            if (packet != null) return packet
        }
    }

    override fun decodeState(bytes: ByteArray): ResidentState = memScoped {
        check(bytes.size.toLong() == sizeOf<FrStatus>()) { "Invalid resident status" }
        val status = alloc<FrStatus>()
        bytes.usePinned { memcpy(status.ptr, it.addressOf(0), bytes.size.toULong()) }
        ResidentState(
            status.instance, status.generation, status.in_game != 0u, status.ready != 0u, status.main_menu != 0u,
            status.descriptor, status.installed == FrHook.FR_HOOK_COUNT.value, status.binding_requested != 0u
        )
    }

    override fun close() {
        close(fd)
    }
}
