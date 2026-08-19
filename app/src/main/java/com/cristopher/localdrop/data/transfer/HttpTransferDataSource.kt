package com.cristopher.localdrop.data.transfer

import android.content.ContentResolver
import android.net.Uri
import com.cristopher.localdrop.domain.model.TransferProgress
import com.cristopher.localdrop.utils.ManifestFile
import com.cristopher.localdrop.utils.TransferManifest
import com.cristopher.localdrop.utils.isPrivateIpv4
import com.cristopher.localdrop.utils.sha256Hex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import kotlin.coroutines.coroutineContext

data class UploadPart(val uri: Uri, val name: String, val mimeType: String, val size: Long)

class HttpTransferDataSource(private val resolver: ContentResolver) {
    suspend fun uploadSession(
        host: String, port: Int, sessionId: String, senderId: String, senderName: String,
        publicKey: String, fingerprint: String, sign: (String) -> String,
        parts: List<UploadPart>, verifyIntegrity: Boolean,
        onProgress: (TransferProgress) -> Unit
    ): List<String?> = withContext(Dispatchers.IO) {
        require(isPrivateIpv4(host)) { "Solo se permiten direcciones privadas de la red local" }
        require(port in 1..65535 && parts.isNotEmpty()) { "Transferencia local inválida" }
        val hashes = parts.map { part -> if (verifyIntegrity) resolver.openInputStream(part.uri)?.use(::sha256Hex) ?: error("No se pudo leer ${part.name}") else null }
        val manifest = TransferManifest.encode(parts.mapIndexed { index, part -> ManifestFile(part.name, part.size, part.mimeType, hashes[index]) })
        val signature = sign("$sessionId|$manifest")
        val totalSize = parts.sumOf { it.size }
        val connection = (URL("http://$host:$port/upload-session").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            doInput = true
            connectTimeout = 8_000
            readTimeout = 120_000
            useCaches = false
            setFixedLengthStreamingMode(totalSize)
            setRequestProperty("Content-Type", "application/octet-stream")
            setRequestProperty("X-LocalDrop-Session", sessionId)
            setRequestProperty("X-LocalDrop-Device-Id", senderId)
            setRequestProperty("X-LocalDrop-Device-Name", senderName)
            setRequestProperty("X-LocalDrop-Public-Key", publicKey)
            setRequestProperty("X-LocalDrop-Fingerprint", fingerprint)
            setRequestProperty("X-LocalDrop-Signature", signature)
            setRequestProperty("X-LocalDrop-Manifest", Base64.getUrlEncoder().withoutPadding().encodeToString(manifest.toByteArray(Charsets.UTF_8)))
        }
        try {
            val started = System.nanoTime(); var sentTotal = 0L; val buffer = ByteArray(DEFAULT_BUFFER)
            connection.outputStream.use { output ->
                parts.forEach { part ->
                    var sentFile = 0L
                    val input = resolver.openInputStream(part.uri) ?: error("No se pudo abrir ${part.name}")
                    input.use { source -> BufferedInputStream(source, DEFAULT_BUFFER).use { buffered ->
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = buffered.read(buffer); if (read < 0) break
                            output.write(buffer, 0, read); sentFile += read; sentTotal += read
                            val elapsed = (System.nanoTime() - started).coerceAtLeast(1)
                            onProgress(TransferProgress(part.name, sentFile, part.size, sentTotal * 1_000_000_000L / elapsed))
                        }
                    } }
                    if (sentFile != part.size) throw IOException("Transferencia incompleta: ${part.name} $sentFile/${part.size} bytes")
                }
            }
            if (sentTotal != totalSize) throw IOException("Sesión incompleta: $sentTotal/$totalSize bytes")
            if (connection.responseCode !in 200..299) error("El dispositivo rechazó la sesión (${connection.responseCode})")
            hashes
        } finally { connection.disconnect() }
    }
    companion object { private const val DEFAULT_BUFFER = 64 * 1024 }
}
