package com.cristopher.localdrop.domain.model

import android.net.Uri

enum class DeviceType { PHONE, TABLET, DESKTOP, UNKNOWN }
enum class DeviceStatus { AVAILABLE, CONNECTED, DISCONNECTED }
enum class TransferDirection { SENT, RECEIVED }

enum class TransferState { PENDING, RUNNING, COMPLETED, FAILED, CANCELLED, REJECTED, CORRUPTED }

data class LocalDevice(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val type: DeviceType = DeviceType.UNKNOWN,
    val status: DeviceStatus = DeviceStatus.AVAILABLE,
    val paired: Boolean = false
)

data class TransferFile(
    val uri: Uri,
    val name: String,
    val size: Long,
    val mimeType: String
)

data class TransferProgress(
    val fileName: String,
    val transferred: Long,
    val total: Long,
    val bytesPerSecond: Long = 0,
    val state: TransferState = TransferState.RUNNING,
    val error: String? = null
) {
    val fraction: Float get() = if (total > 0) (transferred.toFloat() / total).coerceIn(0f, 1f) else if (state == TransferState.COMPLETED) 1f else 0f
}

data class TransferHistory(
    val id: Long,
    val fileName: String,
    val size: Long,
    val timestamp: Long,
    val deviceName: String,
    val direction: TransferDirection,
    val state: TransferState,
    val error: String? = null,
    val sha256: String? = null
)

data class IncomingRequest(
    val sessionId: String,
    val device: LocalDevice,
    val files: List<IncomingFile>
)

data class IncomingFile(val name: String, val size: Long, val mimeType: String, val sha256: String? = null)

data class LocalSettings(
    val deviceName: String,
    val port: Int,
    val defaultFolder: Uri? = null,
    val autoDiscovery: Boolean = true,
    val confirmIncoming: Boolean = true,
    val verifyIntegrity: Boolean = true
)
