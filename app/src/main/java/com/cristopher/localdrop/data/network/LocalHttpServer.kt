package com.cristopher.localdrop.data.network

import com.cristopher.localdrop.domain.model.IncomingFile
import com.cristopher.localdrop.domain.model.IncomingRequest
import com.cristopher.localdrop.domain.model.LocalDevice
import com.cristopher.localdrop.utils.ManifestFile
import com.cristopher.localdrop.utils.TransferManifest
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
            val requestLine = lines.firstOrNull().orEmpty()
            val sessionUpload = requestLine.startsWith("POST /upload-session")
            val singleUpload = requestLine.startsWith("POST /upload")
            val chunkUpload = requestLine.startsWith("POST /upload-chunk")
            if (!sessionUpload && !singleUpload && !chunkUpload) { writeResponse(s, 404, "Not found"); return }
            val headers = lines.drop(1).filter { it.contains(':') }.associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
            val session = headers["x-localdrop-session"]?.takeIf { it.length in 8..100 } ?: UUID.randomUUID().toString()
            val manifest = runCatching { if (sessionUpload || chunkUpload) decodeManifest(headers["x-localdrop-manifest"]) else singleManifest(headers) }.getOrElse { writeResponse(s, 400, "Invalid manifest"); return }
            val files = runCatching { TransferManifest.decode(manifest) }.getOrElse { writeResponse(s, 400, "Invalid manifest"); return }
            if (!isValidManifest(files)) { writeResponse(s, 400, "Invalid manifest"); return }
            val declaredLength = headers["content-length"]?.toLongOrNull()
            val totalSize = files.sumOf { it.size }
            if (!chunkUpload && declaredLength != null && declaredLength != totalSize) { writeResponse(s, 400, "Length mismatch"); return }
            val request = IncomingRequest(
                sessionId = session,
                device = LocalDevice(
                    id = headers["x-localdrop-device-id"] ?: "unknown",
                    name = headers["x-localdrop-device-name"]?.take(64) ?: "Dispositivo local",
                    host = s.inetAddress.hostAddress ?: "?",
                    port = 0,
                    publicKey = headers["x-localdrop-public-key"],
                    fingerprint = headers["x-localdrop-fingerprint"]
                ),
                files = files.map { IncomingFile(it.name, it.size, it.mimeType, it.sha256) },
                signature = headers["x-localdrop-signature"],
                manifest = manifest
            )
            onRequest(request, input, s, headers)
        }
    }

    private fun decodeManifest(value: String?): String = value?.let { Base64.getUrlDecoder().decode(it).toString(Charsets.UTF_8) } ?: throw IllegalArgumentException("Manifest missing")
    private fun singleManifest(headers: Map<String, String>): String {
        val name = headers["x-localdrop-file-name"]?.let(::decodeName)?.takeIf { it.isNotBlank() } ?: "archivo"
        val size = headers["x-localdrop-file-size"]?.toLongOrNull() ?: headers["content-length"]?.toLongOrNull() ?: -1L
        return TransferManifest.encode(listOf(ManifestFile(name, size, headers["x-localdrop-file-mime"] ?: "application/octet-stream", headers["x-localdrop-sha256"])))
    }
    private fun isValidManifest(files: List<ManifestFile>): Boolean = files.isNotEmpty() && files.all { it.size >= 0 && it.name.length <= 255 && it.name.isNotBlank() }

    private fun readHeaders(input: InputStream): String? {
        val output = ByteArrayOutputStream()
        var previous = -1
        while (output.size() <= MAX_HEADER_BYTES) {
            val current = input.read(); if (current < 0) return null
            output.write(current)
            if (previous == '\r'.code && current == '\n'.code) {
                val headerText = output.toString(Charsets.UTF_8.name())
                if (headerText.endsWith("\r\n\r\n")) return headerText
            }
            previous = current
        }
        return null
    }

    fun close() { try { server?.close() } catch (_: Exception) { }; server = null }

    companion object {
        private const val MAX_HEADER_BYTES = 32 * 1024
        private fun decodeName(value: String): String? = try { Base64.getUrlDecoder().decode(value).toString(Charsets.UTF_8) } catch (_: IllegalArgumentException) { null }
        fun writeResponse(socket: Socket, code: Int, message: String, headers: Map<String, String> = emptyMap()) { try { socket.getOutputStream().bufferedWriter().use { writer -> writer.write("HTTP/1.1 $code $message\r\nContent-Length: 0\r\nConnection: close\r\n"); headers.forEach { (key, value) -> writer.write("$key: $value\r\n") }; writer.write("\r\n"); writer.flush() } } catch (_: Exception) { } }
    }
}
