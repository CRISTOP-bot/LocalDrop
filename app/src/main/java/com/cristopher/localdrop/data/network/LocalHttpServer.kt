package com.cristopher.localdrop.data.network

import com.cristopher.localdrop.domain.model.IncomingFile
import com.cristopher.localdrop.domain.model.IncomingRequest
import com.cristopher.localdrop.domain.model.LocalDevice
import com.cristopher.localdrop.utils.isPrivateIpv4
import kotlinx.coroutines.*
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.UUID

class LocalHttpServer(private val scope: CoroutineScope, private val onRequest: suspend (IncomingRequest, InputStream, Socket, Map<String, String>) -> Unit) {
    private var server: ServerSocket? = null
    var port: Int = 0; private set

    @Synchronized fun start(preferredPort: Int = 0) {
        if (server != null) return
        server = ServerSocket(preferredPort).also { port = it.localPort }
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                try { val socket = server?.accept() ?: break; launch { handle(socket) } }
                catch (_: IOException) { break }
            }
        }
    }

    private suspend fun handle(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 120_000
            if (!isPrivateIpv4(s.inetAddress.hostAddress.orEmpty())) { writeResponse(s, 403, "Local network only"); return }
            val input = BufferedInputStream(s.getInputStream())
            val rawHeaders = readHeaders(input) ?: return
            val lines = rawHeaders.split("\r\n")
            if (lines.firstOrNull()?.startsWith("POST /upload") != true) { writeResponse(s, 404, "Not found"); return }
            val headers = lines.drop(1).filter { it.contains(':') }.associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
            val session = headers["x-localdrop-session"]?.takeIf { it.length in 8..100 } ?: UUID.randomUUID().toString()
            val fileName = headers["x-localdrop-file-name"]?.let(::decodeName)?.takeIf { it.isNotBlank() } ?: "archivo"
            val declaredLength = headers["content-length"]?.toLongOrNull()
            val fileSize = headers["x-localdrop-file-size"]?.toLongOrNull() ?: declaredLength ?: -1L
            if (fileSize < 0L || (declaredLength != null && declaredLength != fileSize)) { writeResponse(s, 400, "Length required"); return }
            val request = IncomingRequest(
                sessionId = session,
                device = LocalDevice(
                    id = headers["x-localdrop-device-id"] ?: "unknown",
                    name = headers["x-localdrop-device-name"]?.take(64) ?: "Dispositivo local",
                    host = s.inetAddress.hostAddress ?: "?",
                    port = 0
                ),
                files = listOf(IncomingFile(fileName, fileSize, headers["x-localdrop-file-mime"] ?: "application/octet-stream", headers["x-localdrop-sha256"]))
            )
            onRequest(request, input, s, headers)
        }
    }

    private fun readHeaders(input: InputStream): String? {
        val output = ByteArrayOutputStream()
        var previous = -1
        while (output.size() <= MAX_HEADER_BYTES) {
            val current = input.read()
            if (current < 0) return null
            output.write(current)
            if (previous == '\r'.code && current == '\n'.code && output.toString(Charsets.UTF_8).endsWith("\r\n\r\n")) return output.toString(Charsets.UTF_8)
            previous = current
        }
        return null
    }

    fun close() { try { server?.close() } catch (_: Exception) { }; server = null }

    companion object {
        private const val MAX_HEADER_BYTES = 16 * 1024
        private fun decodeName(value: String): String? = try { Base64.getUrlDecoder().decode(value).toString(Charsets.UTF_8) } catch (_: IllegalArgumentException) { null }
        fun writeResponse(socket: Socket, code: Int, message: String) {
            try { socket.getOutputStream().bufferedWriter().use { it.write("HTTP/1.1 $code $message\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"); it.flush() } } catch (_: Exception) { }
        }
    }
}
