package com.cristopher.localdrop.presentation

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cristopher.localdrop.data.LocalDropRepositoryImpl
import com.cristopher.localdrop.domain.model.*
import com.cristopher.localdrop.domain.repository.LocalDropRepository
import com.cristopher.localdrop.domain.usecase.*
import com.cristopher.localdrop.utils.QrConnectionParser
import com.cristopher.localdrop.utils.contentSize
import com.cristopher.localdrop.utils.displayName
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: LocalDropRepository = LocalDropRepositoryImpl(application)
    private val sendFiles = SendFilesUseCase(repository)
    private val answerIncoming = RespondToIncomingUseCase(repository)
    private val refresh = RefreshDevicesUseCase(repository)
    private val scannedDevice = MutableStateFlow<LocalDevice?>(null)
    val devices = combine(repository.devices, scannedDevice) { found, scanned ->
        if (scanned != null && found.none { it.id == scanned.id }) found + scanned else found
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val history = repository.history.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val incoming = repository.incomingRequests.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val activeTransfer = repository.activeTransfer.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val settings = repository.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LocalSettings("Android", 0))
    private val _message = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val message = _message.asSharedFlow()
    private val _sharedFiles = MutableStateFlow<List<TransferFile>>(emptyList())
    val sharedFiles = _sharedFiles.asStateFlow()

    init { viewModelScope.launch { repository.start() } }
    fun refreshDevices() = viewModelScope.launch { refresh() }
    fun filesFromUris(uris: List<Uri>) {
        val resolver = getApplication<Application>().contentResolver
        _sharedFiles.value = uris.map { uri -> TransferFile(uri, uri.displayName(resolver), uri.contentSize(resolver).coerceAtLeast(0), resolver.getType(uri) ?: "application/octet-stream") }
    }
    fun sendTo(device: LocalDevice) = viewModelScope.launch {
        if (_sharedFiles.value.isEmpty()) { _message.emit("Selecciona al menos un archivo"); return@launch }
        sendFiles(_sharedFiles.value, device)
        _sharedFiles.value = emptyList()
    }
    fun cancel() { repository.cancelTransfer() }
    fun acceptIncoming(sessionId: String, folder: Uri?) = viewModelScope.launch { answerIncoming(sessionId, true, folder) }
    fun rejectIncoming(sessionId: String) = viewModelScope.launch { answerIncoming(sessionId, false, null) }
    fun saveSettings(settings: LocalSettings) = viewModelScope.launch { repository.updateSettings(settings); _message.emit("Configuración guardada") }
    fun deleteHistory(id: Long) = viewModelScope.launch { repository.deleteHistory(id) }
    fun onQrScanned(raw: String) {
        runCatching {
            val qr = QrConnectionParser.parse(raw)
            val device = LocalDevice(qr.deviceId, qr.name, qr.host, qr.port, DeviceType.UNKNOWN, DeviceStatus.CONNECTED, paired = true)
            scannedDevice.value = device
            viewModelScope.launch { repository.pairDevice(device); _message.emit("Dispositivo emparejado mediante QR") }
        }.onFailure { error -> viewModelScope.launch { _message.emit(error.message ?: "El QR no pertenece a LocalDrop") } }
    }
    override fun onCleared() { repository.close() }
}
