package com.cristopher.localdrop.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import com.cristopher.localdrop.data.discovery.NsdDiscoveryDataSource
import com.cristopher.localdrop.data.local.*
import com.cristopher.localdrop.data.network.LocalHttpServer
import com.cristopher.localdrop.data.security.LocalIdentityStore
import com.cristopher.localdrop.data.transfer.HttpTransferDataSource
import com.cristopher.localdrop.data.transfer.TransferService
import com.cristopher.localdrop.data.transfer.UploadPart
import com.cristopher.localdrop.domain.model.*
import com.cristopher.localdrop.domain.repository.LocalDropRepository
import com.cristopher.localdrop.utils.uniqueFileName
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class LocalDropRepositoryImpl(context: Context) : LocalDropRepository {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private val db = LocalDropDatabase.create(app)
    private val nsd = NsdDiscoveryDataSource(app)
    private val server = LocalHttpServer(scope, ::handleIncoming)
    private val uploader = HttpTransferDataSource(app.contentResolver)
    private val deviceId = android.provider.Settings.Secure.getString(app.contentResolver, android.provider.Settings.Secure.ANDROID_ID)?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
    private val identityStore = LocalIdentityStore(app, deviceId)
    private val _identity = MutableStateFlow(identityStore.identity)
    private val _devices = MutableStateFlow<List<LocalDevice>>(emptyList())
    private val _incoming = MutableStateFlow<Map<String, IncomingRequest>>(emptyMap())
    private val _active = MutableStateFlow<TransferProgress?>(null)
    private val _settings = MutableStateFlow(LocalSettings(Build.MODEL.ifBlank { "Android" }, 0))
    private val pendingAnswers = ConcurrentHashMap<String, CompletableDeferred<IncomingDecision>>()
    private val approvedSessions = ConcurrentHashMap<String, Uri>()
    private val destinationMutex = Mutex()
    private var discoveryJob: Job? = null
    private var queueJob: Job? = null
    private var currentTransferJob: Job? = null
    private var networkRestartJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var started = false

    override val devices: Flow<List<LocalDevice>> = _devices.asStateFlow()
    override val incomingRequests: Flow<List<IncomingRequest>> = _incoming.map { it.values.toList() }.stateIn(scope, SharingStarted.Eagerly, emptyList())
    override val activeTransfer: Flow<TransferProgress?> = _active.asStateFlow()
    override val settings: Flow<LocalSettings> = _settings.asStateFlow()
    override val localIdentity: Flow<LocalIdentity> = _identity.asStateFlow()
    override val history: Flow<List<TransferHistory>> = db.historyDao().observeAll().map { list -> list.map { it.toDomain() } }

    init {
        scope.launch { db.settingsDao().observe().collect { value -> value?.let { _settings.value = it.toDomain() } } }
        queueJob = scope.launch {
            db.transferQueueDao().resetRunning()
            while (isActive) {
                val queued = db.transferQueueDao().nextPending()
                if (queued == null) delay(250) else {
                    val batch = if (queued.batchId.isBlank()) listOf(queued) else db.transferQueueDao().pendingBatch(queued.batchId)
                    currentTransferJob = launch { processQueueBatch(batch) }
                    try { currentTransferJob?.join() } finally { currentTransferJob = null }
                }
            }
        }
    }

    override suspend fun start() { if (!started) { started = true; startNetworkStack(); registerNetworkCallback(); TransferService.start(app) } }
    private suspend fun startNetworkStack() {
        val configured = _settings.value
        runCatching { server.start(configured.port) }.getOrElse { server.start(0) }
        val effective = configured.copy(port = server.port)
        _settings.value = effective
        db.settingsDao().save(effective.toEntity())
        nsd.register(effective.deviceName, server.port, deviceId, deviceType())
        if (effective.autoDiscovery) startDiscovery()
    }
    private fun registerNetworkCallback() {
        if (networkCallback != null || connectivity == null) return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = scheduleNetworkRestart()
            override fun onLinkPropertiesChanged(network: Network, properties: android.net.LinkProperties) = scheduleNetworkRestart()
            override fun onLost(network: Network) { _devices.value = _devices.value.map { it.copy(status = DeviceStatus.DISCONNECTED) }; scheduleNetworkRestart() }
        }
        networkCallback = callback
        runCatching { connectivity.registerDefaultNetworkCallback(callback) }.onFailure { networkCallback = null }
    }
    private fun scheduleNetworkRestart() { if (started) { networkRestartJob?.cancel(); networkRestartJob = scope.launch { delay(NETWORK_DEBOUNCE_MS); restartNetworkStack() } } }
    private suspend fun restartNetworkStack() { if (!started) return; discoveryJob?.cancelAndJoin(); discoveryJob = null; nsd.stopDiscovery(); server.close(); startNetworkStack() }
    private fun startDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = scope.launch { nsd.discover(deviceId).collect { discovered ->
            val merged = discovered.map { device -> val old = db.pairedDeviceDao().findById(device.id); device.copy(paired = old?.paired == true, publicKey = old?.publicKey, fingerprint = old?.fingerprint) }
            _devices.value = merged
            merged.forEach { device -> db.pairedDeviceDao().upsert(device.toEntity(device.paired)) }
        } }
    }
    override suspend fun stop() { networkRestartJob?.cancelAndJoin(); networkRestartJob = null; unregisterNetworkCallback(); discoveryJob?.cancelAndJoin(); discoveryJob = null; nsd.stopDiscovery(); nsd.unregister(); server.close(); started = false }
    private fun unregisterNetworkCallback() { networkCallback?.let { runCatching { connectivity?.unregisterNetworkCallback(it) } }; networkCallback = null }
    override suspend fun refreshDevices() { if (!started) start(); discoveryJob?.cancelAndJoin(); discoveryJob = null; nsd.stopDiscovery(); _devices.value = emptyList(); if (_settings.value.autoDiscovery) startDiscovery() }

    override suspend fun send(files: List<TransferFile>, device: LocalDevice) {
        require(files.isNotEmpty()) { "No hay archivos para enviar" }
        val batchId = UUID.randomUUID().toString()
        db.transferQueueDao().insertAll(files.map { file -> QueuedTransferEntity(batchId = batchId, uri = file.uri.toString(), fileName = file.name, size = file.size, mimeType = file.mimeType, deviceId = device.id, deviceName = device.name, host = device.host, port = device.port, createdAt = System.currentTimeMillis()) })
    }

    private suspend fun processQueueBatch(items: List<QueuedTransferEntity>) {
        if (items.isEmpty()) return
        val ids = items.map { it.id }; val first = items.first(); val device = LocalDevice(first.deviceId, first.deviceName, first.host, first.port, paired = true)
        val files = items.map { TransferFile(Uri.parse(it.uri), it.fileName, it.size, it.mimeType) }
        db.transferQueueDao().markRunning(ids)
        try {
            _active.value = TransferProgress("${files.first().name} (${files.size} archivos)", 0, files.sumOf { it.size }, state = TransferState.RUNNING)
            notifyProgress(_active.value)
            val parts = files.map { UploadPart(it.uri, it.name, it.mimeType, it.size) }
            val hashes = uploader.uploadSession(device.host, device.port, UUID.randomUUID().toString(), deviceId, _settings.value.deviceName, _identity.value.publicKey, _identity.value.fingerprint, identityStore::sign, parts, _settings.value.verifyIntegrity) { progress -> _active.value = progress; notifyProgress(progress) }
            db.transferQueueDao().markFinished(ids, TransferState.COMPLETED.name, null)
            files.forEachIndexed { index, file -> addHistory(file, device.name, TransferDirection.SENT, TransferState.COMPLETED, null, hashes[index]) }
        } catch (cancelled: CancellationException) {
            db.transferQueueDao().markFinished(ids, TransferState.CANCELLED.name, "Cancelada"); files.forEach { addHistory(it, device.name, TransferDirection.SENT, TransferState.CANCELLED, "Cancelada", null) }; _active.value = _active.value?.copy(state = TransferState.CANCELLED, error = "Cancelada")
        } catch (error: Exception) {
            if (first.attempts < MAX_TRANSFER_ATTEMPTS) { db.transferQueueDao().retry(ids, error.message ?: "Error temporal"); delay(RETRY_DELAYS[first.attempts.coerceIn(0, RETRY_DELAYS.lastIndex)]) }
            else { val state = if (error.message?.contains("SHA-256", true) == true) TransferState.CORRUPTED else TransferState.FAILED; db.transferQueueDao().markFinished(ids, state.name, error.message); files.forEach { addHistory(it, device.name, TransferDirection.SENT, state, error.message, null) }; _active.value = _active.value?.copy(state = state, error = error.message) }
        } finally { withContext(NonCancellable) { delay(300); _active.value = null; clearNotification() } }
    }

    override suspend fun answerIncoming(sessionId: String, accepted: Boolean, folder: Uri?) { pendingAnswers.remove(sessionId)?.complete(IncomingDecision(accepted, folder)); _incoming.update { it - sessionId } }
    override suspend fun pairDevice(device: LocalDevice) { val paired = device.copy(paired = true); _devices.update { list -> list.filterNot { it.id == paired.id } + paired }; db.pairedDeviceDao().upsert(paired.toEntity(true)) }
    override suspend fun revokeDevice(deviceId: String) { db.pairedDeviceDao().revoke(deviceId); _devices.update { list -> list.map { if (it.id == deviceId) it.copy(paired = false, publicKey = null, fingerprint = null) else it } } }

    private suspend fun handleIncoming(request: IncomingRequest, body: InputStream, socket: Socket, headers: Map<String, String>) {
        val known = db.pairedDeviceDao().findById(request.device.id); val authenticated = isAuthenticated(request, known)
        if ((known?.paired == true && !authenticated) || (!_settings.value.confirmIncoming && !authenticated)) { request.files.forEach { addIncomingHistory(request, it, TransferState.FAILED, "Autenticación de dispositivo inválida") }; LocalHttpServer.writeResponse(socket, 401, "Authentication required"); return }
        if (headers["x-localdrop-chunk"] == "1") { handleChunk(request, body, socket, headers, authenticated); return }
        val decision = CompletableDeferred<IncomingDecision>(); pendingAnswers[request.sessionId] = decision; _incoming.update { it + (request.sessionId to request) }
        if (!_settings.value.confirmIncoming) decision.complete(IncomingDecision(true, _settings.value.defaultFolder))
        val answer = withTimeoutOrNull(INCOMING_TIMEOUT_MS) { decision.await() } ?: IncomingDecision(false, null)
        pendingAnswers.remove(request.sessionId); _incoming.update { it - request.sessionId }
        if (!answer.accepted) { request.files.forEach { addIncomingHistory(request, it, TransferState.REJECTED, "Rechazada por el usuario") }; LocalHttpServer.writeResponse(socket, 403, "Rejected"); return }
        try {
            val result = saveSession(request, body, answer.folder ?: _settings.value.defaultFolder)
            result.files.forEachIndexed { index, saved -> addIncomingHistory(request, request.files[index], TransferState.COMPLETED, null, saved.sha256) }
            LocalHttpServer.writeResponse(socket, 200, "OK")
        } catch (error: Exception) {
            val state = if (error is IntegrityException) TransferState.CORRUPTED else TransferState.FAILED
            request.files.forEach { addIncomingHistory(request, it, state, error.message, it.sha256) }; LocalHttpServer.writeResponse(socket, 500, "Transfer failed")
        }
    }
    private suspend fun handleChunk(request: IncomingRequest, body: InputStream, socket: Socket, headers: Map<String, String>, authenticated: Boolean) {
        val folder = if (approvedSessions.containsKey(request.sessionId)) approvedSessions[request.sessionId]?.takeUnless { it == Uri.EMPTY } else run {
            val decision = CompletableDeferred<IncomingDecision>(); pendingAnswers[request.sessionId] = decision; _incoming.update { it + (request.sessionId to request) }
            if (!_settings.value.confirmIncoming) decision.complete(IncomingDecision(true, _settings.value.defaultFolder))
            val answer = withTimeoutOrNull(INCOMING_TIMEOUT_MS) { decision.await() } ?: IncomingDecision(false, null)
            pendingAnswers.remove(request.sessionId); _incoming.update { it - request.sessionId }
            if (!answer.accepted) { request.files.forEach { addIncomingHistory(request, it, TransferState.REJECTED, "Rechazada por el usuario") }; LocalHttpServer.writeResponse(socket, 403, "Rejected"); return }
            (answer.folder ?: _settings.value.defaultFolder).also { approvedSessions[request.sessionId] = it ?: Uri.EMPTY }
        }
        try {
            val result = saveChunk(request, body, headers, folder)
            if (result.completed) { addIncomingHistory(request, request.files[result.index], TransferState.COMPLETED, null, result.sha256); approvedSessions.remove(request.sessionId) }
            LocalHttpServer.writeResponse(socket, 200, "OK", mapOf("X-LocalDrop-Next-Offset" to result.nextOffset.toString()))
        } catch (error: Exception) { LocalHttpServer.writeResponse(socket, 500, "Chunk failed") }
    }

    private suspend fun saveChunk(request: IncomingRequest, body: InputStream, headers: Map<String, String>, folder: Uri?): ChunkResult = withContext(Dispatchers.IO) {
        val index = headers["x-localdrop-file-index"]?.toIntOrNull() ?: error("Índice inválido")
        val file = request.files.getOrNull(index) ?: error("Archivo inválido")
        val offset = headers["x-localdrop-offset"]?.toLongOrNull() ?: error("Offset inválido")
        val length = headers["content-length"]?.toLongOrNull() ?: error("Longitud inválida")
        val directory = File(app.filesDir, "localdrop-sessions").apply { mkdirs() }
        val temp = File(directory, "${request.sessionId.replace(Regex("[^a-zA-Z0-9_-]"), "_")}-$index.part")
        val current = if (temp.exists()) temp.length() else 0L
        if (offset > current) error("Falta un fragmento anterior")
        if (offset < current) { var remaining = length; val discard = ByteArray(BUFFER_SIZE); while (remaining > 0) { val read = body.read(discard, 0, minOf(discard.size.toLong(), remaining).toInt()); if (read < 0) error("Fragmento incompleto"); remaining -= read }; return@withContext ChunkResult(index, current, current == file.size, null) }
        RandomAccessFile(temp, "rw").use { random -> random.seek(offset); var remaining = length; val buffer = ByteArray(BUFFER_SIZE); while (remaining > 0) { ensureActive(); val read = body.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt()); if (read < 0) error("Fragmento incompleto"); random.write(buffer, 0, read); remaining -= read } }
        val next = temp.length(); if (next > file.size) error("Fragmento excede el tamaño")
        if (next < file.size) return@withContext ChunkResult(index, next, false, null)
        val sha = temp.inputStream().use { input -> val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(BUFFER_SIZE); while (true) { val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read) }; digest.digest().joinToString("") { "%02x".format(it) } }
        if (file.sha256 != null && !file.sha256.equals(sha, true)) error("SHA-256 no coincide: ${file.name}")
        val destination = destinationMutex.withLock { createDestination(folder, file.name, file.mimeType) }; try { val output = openOutput(destination) ?: error("No se pudo crear ${file.name}"); output.use { out -> temp.inputStream().use { it.copyTo(out, BUFFER_SIZE) } }; finalizeDestination(destination); temp.delete() } catch (error: Exception) { deleteDestination(destination); throw error }
        ChunkResult(index, next, true, sha)
    }

    private fun isAuthenticated(request: IncomingRequest, known: PairedDeviceEntity?): Boolean {
        val publicKey = request.device.publicKey ?: return false; val fingerprint = request.device.fingerprint ?: return false
        if (known?.publicKey != null && known.publicKey != publicKey) return false
        if (known?.fingerprint != null && known.fingerprint != fingerprint) return false
        val manifest = request.manifest ?: return false
        return request.signature?.let { LocalIdentityStore.verify(publicKey, "${request.sessionId}|$manifest", it) } == true
    }

    private suspend fun saveSession(request: IncomingRequest, body: InputStream, folder: Uri?): SaveResult = withContext(Dispatchers.IO) {
        val destinations = mutableListOf<Uri>(); val saved = mutableListOf<SaveFile>()
        val total = request.files.sumOf { it.size }; var overall = 0L
        try {
            request.files.forEach { file ->
                val destination = destinationMutex.withLock { createDestination(folder, file.name, file.mimeType) }; destinations += destination
                val digest = MessageDigest.getInstance("SHA-256"); var copied = 0L; val started = System.nanoTime(); val buffer = ByteArray(BUFFER_SIZE)
                val output = openOutput(destination) ?: error("No se pudo crear ${file.name}")
                output.use { out ->
                    while (copied < file.size) {
                        ensureActive(); val read = body.read(buffer, 0, minOf(buffer.size.toLong(), file.size - copied).toInt()); if (read < 0) break
                        out.write(buffer, 0, read); digest.update(buffer, 0, read); copied += read; overall += read
                        val elapsed = (System.nanoTime() - started).coerceAtLeast(1); _active.value = TransferProgress(file.name, copied, file.size, overall * 1_000_000_000L / elapsed); notifyProgress(_active.value)
                    }
                }
                if (copied != file.size) throw IOException("Transferencia incompleta: ${file.name} $copied/${file.size} bytes")
                val actual = digest.digest().joinToString("") { "%02x".format(it) }; if (file.sha256 != null && !file.sha256.equals(actual, true)) throw IntegrityException("SHA-256 no coincide: ${file.name}")
                finalizeDestination(destination); saved += SaveFile(file.name, actual)
            }
            _active.value = TransferProgress("Sesión completa", total, total, state = TransferState.COMPLETED); notifyProgress(_active.value); SaveResult(saved)
        } catch (error: Exception) { destinations.forEach(::deleteDestination); throw error }
    }

    private fun createDestination(folder: Uri?, original: String, mime: String): Uri { val name = uniqueFileName(original) { exists(folder, it) }; if (folder != null) { val tree = DocumentFile.fromTreeUri(app, folder) ?: error("Carpeta no disponible"); return (tree.createFile(mime, name) ?: error("No se pudo crear el archivo")).uri }; if (Build.VERSION.SDK_INT >= 29) { val values = ContentValues().apply { put(MediaStore.Downloads.DISPLAY_NAME, name); put(MediaStore.Downloads.MIME_TYPE, mime); put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/LocalDrop"); put(MediaStore.Downloads.IS_PENDING, 1) }; return app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("No se pudo guardar en Descargas") }; val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "LocalDrop").apply { mkdirs() }; return Uri.fromFile(File(directory, name)) }
    private fun exists(folder: Uri?, name: String): Boolean { if (folder != null) return DocumentFile.fromTreeUri(app, folder)?.findFile(name) != null; if (Build.VERSION.SDK_INT >= 29) { val selection = "${MediaStore.Downloads.RELATIVE_PATH}=? AND ${MediaStore.Downloads.DISPLAY_NAME}=?"; app.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Downloads._ID), selection, arrayOf(Environment.DIRECTORY_DOWNLOADS + "/LocalDrop/", name), null)?.use { return it.moveToFirst() } }; return File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "LocalDrop/$name").exists() }
    private fun openOutput(uri: Uri): OutputStream? = if (uri.scheme == "file") FileOutputStream(requireNotNull(uri.path)) else app.contentResolver.openOutputStream(uri, "w")
    private fun finalizeDestination(uri: Uri) { if (Build.VERSION.SDK_INT >= 29 && uri.authority == "media") app.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) }
    private fun deleteDestination(uri: Uri) { if (uri.scheme == "file") File(uri.path.orEmpty()).delete() else app.contentResolver.delete(uri, null, null) }

    private suspend fun addHistory(file: TransferFile, deviceName: String, direction: TransferDirection, state: TransferState, error: String?, sha256: String?) = db.historyDao().insert(HistoryEntity(fileName = file.name, size = file.size, timestamp = System.currentTimeMillis(), deviceName = deviceName, direction = direction.name, state = state.name, error = error, sha256 = sha256))
    private suspend fun addIncomingHistory(request: IncomingRequest, file: IncomingFile, state: TransferState, error: String?, sha256: String? = null) = db.historyDao().insert(HistoryEntity(fileName = file.name, size = file.size, timestamp = System.currentTimeMillis(), deviceName = request.device.name, direction = TransferDirection.RECEIVED.name, state = state.name, error = error, sha256 = sha256))
    override suspend fun updateSettings(settings: LocalSettings) { _settings.value = settings; db.settingsDao().save(settings.toEntity()); if (started) { stop(); start() } }
    override suspend fun deleteHistory(id: Long) = db.historyDao().delete(id)
    override fun cancelTransfer() { currentTransferJob?.cancel() }
    override fun close() { discoveryJob?.cancel(); networkRestartJob?.cancel(); currentTransferJob?.cancel(); queueJob?.cancel(); pendingAnswers.values.forEach { it.cancel() }; pendingAnswers.clear(); _incoming.value = emptyMap(); unregisterNetworkCallback(); nsd.unregister(); server.close(); scope.cancel(); db.close(); TransferService.stop(app); started = false }
    private fun notifyProgress(progress: TransferProgress?) { val manager = app.getSystemService(NotificationManager::class.java); if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Transferencias LocalDrop", NotificationManager.IMPORTANCE_LOW)); progress ?: return; manager.notify(NOTIFICATION_ID, NotificationCompat.Builder(app, CHANNEL_ID).setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle("LocalDrop: ${progress.fileName}").setContentText("${(progress.fraction * 100).toInt()}% • ${progress.bytesPerSecond} B/s").setProgress(100, (progress.fraction * 100).toInt(), false).setOngoing(progress.state == TransferState.RUNNING).build()) }
    private fun clearNotification() { app.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID) }
    private fun deviceType(): DeviceType = if (app.resources.configuration.smallestScreenWidthDp >= 600) DeviceType.TABLET else DeviceType.PHONE
    private fun LocalDevice.toEntity(paired: Boolean) = PairedDeviceEntity(id, name, host, port, System.currentTimeMillis(), paired, publicKey, fingerprint)
    private fun HistoryEntity.toDomain() = TransferHistory(id, fileName, size, timestamp, deviceName, TransferDirection.valueOf(direction), TransferState.valueOf(state), error, sha256)
    private fun SettingsEntity.toDomain() = LocalSettings(deviceName, port, defaultFolder?.let(Uri::parse), autoDiscovery, confirmIncoming, verifyIntegrity)
    private fun LocalSettings.toEntity() = SettingsEntity(1, deviceName, port, defaultFolder?.toString(), autoDiscovery, confirmIncoming, verifyIntegrity)
    private data class IncomingDecision(val accepted: Boolean, val folder: Uri?)
    private data class ChunkResult(val index: Int, val nextOffset: Long, val completed: Boolean, val sha256: String?)
    private data class SaveResult(val files: List<SaveFile>)
    private data class SaveFile(val name: String, val sha256: String)
    private class IntegrityException(message: String) : IOException(message)
    companion object { private const val BUFFER_SIZE = 64 * 1024; private const val INCOMING_TIMEOUT_MS = 120_000L; private const val NETWORK_DEBOUNCE_MS = 600L; private const val MAX_TRANSFER_ATTEMPTS = 3; private val RETRY_DELAYS = longArrayOf(1_000L, 3_000L, 8_000L); private const val CHANNEL_ID = "localdrop_transfer"; private const val NOTIFICATION_ID = 42 }
}
