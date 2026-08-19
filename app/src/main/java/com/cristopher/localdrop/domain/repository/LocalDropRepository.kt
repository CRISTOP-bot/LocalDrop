package com.cristopher.localdrop.domain.repository

import android.net.Uri
import com.cristopher.localdrop.domain.model.*
import kotlinx.coroutines.flow.Flow

interface LocalDropRepository {
    val devices: Flow<List<LocalDevice>>
    val history: Flow<List<TransferHistory>>
    val incomingRequests: Flow<List<IncomingRequest>>
    val activeTransfer: Flow<TransferProgress?>
    val settings: Flow<LocalSettings>
    val localIdentity: Flow<LocalIdentity>
    suspend fun start()
    suspend fun stop()
    suspend fun refreshDevices()
    suspend fun send(files: List<TransferFile>, device: LocalDevice)
    suspend fun answerIncoming(sessionId: String, accepted: Boolean, folder: Uri?)
    suspend fun pairDevice(device: LocalDevice)
    suspend fun updateSettings(settings: LocalSettings)
    suspend fun deleteHistory(id: Long)
    fun cancelTransfer()
    fun close()
}
