package com.cristopher.localdrop.data.transfer

import android.content.ContentResolver
import android.net.Uri
import com.cristopher.localdrop.domain.model.TransferProgress
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

class HttpTransferDataSource(private val resolver: ContentResolver) {
    suspend fun upload(
        host: String,
        port: Int,
        sessionId: String,
        senderId: String,
        senderName: String,
        uri: Uri,
        name: String,
        mime: String,
        size: Long,
        verifyIntegrity: Boolean,
        onProgress: (TransferProgress) -> Unit
    ): String? = withContext(Dispatchers.IO) {
        require(isPrivateIpv4(host)) { "Solo se permiten direcciones privadas de la red local" }
        require(port in 1..65535) { "Puerto local inválido" }
        require(size >= 0) { "Tamaño de archivo inválido" }
        val sha256 = if (verifyIntegrity) resolver.openInputStream(uri)?.use(::sha256Hex) ?: error("No se pudo leer $name") else null
        val connection = (URL("http://$host:$port/upload").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            doInput = true
            connectTimeout = 8_000
            readTimeout = 120_000
            useCaches = false
            setFixedLengthStreamingMode(size)
            setRequestProperty("Content-Type", "application/octet-stream")
            setRequestProperty("X-LocalDrop-Session", sessionId)
            setRequestProperty("X-LocalDrop-Device-Id", senderId)
            setRequestProperty("X-LocalDrop-Device-Name", senderName)
            setRequestProperty("X-LocalDrop-File-Name", Base64.getUrlEncoder().withoutPadding().encodeToString(name.toByteArray(Charsets.UTF_8)))
            setRequestProperty("X-LocalDrop-File-Mime", mime)
            setRequestProperty("X-LocalDrop-File-Size", size.toString())
            sha256?.let { setRequestProperty("X-LocalDrop-Sha256", it) }
        }
        try {
            val input = resolver.openInputStream(uri) ?: error("No se pudo abrir $name")
            val started = System.nanoTime(); var sent = 0L; val buffer = ByteArray(DEFAULT_BUFFER)
            input.use { source -> connection.outputStream.use { output ->
                BufferedInputStream(source, DEFAULT_BUFFER).use { buffered ->
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = buffered.read(buffer); if (read < 0) break
                        output.write(buffer, 0, read); sent += read
                        val elapsed = (System.nanoTime() - started).coerceAtLeast(1)
                        onProgress(TransferProgress(name, sent, size, (sent * 1_000_000_000L / elapsed).coerceAtLeast(0)))
                    }
                }
            } }
            if (sent != size) throw IOException("Transferencia incompleta: $sent/$size bytes")
            if (connection.responseCode !in 200..299) error("El dispositivo rechazó $name (${connection.responseCode})")
            sha256
        } finally { connection.disconnect() }
    }
    companion object { private const val DEFAULT_BUFFER = 64 * 1024 }
}
