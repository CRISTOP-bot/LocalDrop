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
        val encodedManifest = Base64.getUrlEncoder().withoutPadding().encodeToString(manifest.toByteArray(Charsets.UTF_8))
        val started = System.nanoTime(); var sentTotal = 0L
        parts.forEachIndexed { index, part ->
            var offset = 0L
            while (offset < part.size || (part.size == 0L && offset == 0L)) {
                coroutineContext.ensureActive()
                val chunkSize = if (part.size == 0L) 0L else minOf(CHUNK_SIZE.toLong(), part.size - offset)
                val bytes = ByteArray(chunkSize.toInt())
                resolver.openInputStream(part.uri)?.use { source ->
                    var skipped = 0L
                    while (skipped < offset) { val n = source.skip(offset - skipped); if (n <= 0) { if (source.read() < 0) error("No se pudo reanudar ${part.name}"); skipped++ } else skipped += n }
                    var read = 0
                    while (read < bytes.size) { val n = source.read(bytes, read, bytes.size - read); if (n < 0) break; read += n }
                    if (read != bytes.size) error("Archivo incompleto: ${part.name}")
                } ?: error("No se pudo abrir ${part.name}")
                val connection = (URL("http://$host:$port/upload-chunk").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; doOutput = true; doInput = true; connectTimeout = 8_000; readTimeout = 120_000; useCaches = false; setFixedLengthStreamingMode(bytes.size)
                    setRequestProperty("Content-Type", "application/octet-stream"); setRequestProperty("X-LocalDrop-Session", sessionId); setRequestProperty("X-LocalDrop-Device-Id", senderId); setRequestProperty("X-LocalDrop-Device-Name", senderName); setRequestProperty("X-LocalDrop-Public-Key", publicKey); setRequestProperty("X-LocalDrop-Fingerprint", fingerprint); setRequestProperty("X-LocalDrop-Signature", signature); setRequestProperty("X-LocalDrop-Manifest", encodedManifest); setRequestProperty("X-LocalDrop-Chunk", "1"); setRequestProperty("X-LocalDrop-File-Index", index.toString()); setRequestProperty("X-LocalDrop-Offset", offset.toString()); setRequestProperty("X-LocalDrop-File-Size", part.size.toString())
                }
                try {
                    connection.outputStream.use { it.write(bytes) }
                    if (connection.responseCode !in 200..299) error("El dispositivo rechazó el fragmento (${connection.responseCode})")
                    val next = connection.getHeaderField("X-LocalDrop-Next-Offset")?.toLongOrNull() ?: (offset + bytes.size)
                    if (next <= offset || next > part.size || (part.size == 0L && next != 0L)) error("Offset de reanudación inválido")
                    offset = next; sentTotal = parts.take(index).sumOf { it.size } + offset
                    val elapsed = (System.nanoTime() - started).coerceAtLeast(1); onProgress(TransferProgress(part.name, offset, part.size, sentTotal * 1_000_000_000L / elapsed))
                } finally { connection.disconnect() }
                if (part.size == 0L) break
            }
        }
        hashes
    }
    companion object { private const val DEFAULT_BUFFER = 64 * 1024; private const val CHUNK_SIZE = 1024 * 1024 }
}
