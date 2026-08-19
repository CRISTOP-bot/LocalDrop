package com.cristopher.localdrop.domain.usecase

import android.net.Uri
import com.cristopher.localdrop.domain.model.*
import com.cristopher.localdrop.domain.repository.LocalDropRepository

class SendFilesUseCase(private val repository: LocalDropRepository) { suspend operator fun invoke(files: List<TransferFile>, device: LocalDevice) = repository.send(files, device) }
class RespondToIncomingUseCase(private val repository: LocalDropRepository) { suspend operator fun invoke(sessionId: String, accepted: Boolean, folder: Uri?) = repository.answerIncoming(sessionId, accepted, folder) }
class RefreshDevicesUseCase(private val repository: LocalDropRepository) { suspend operator fun invoke() = repository.refreshDevices() }
class PairDeviceUseCase(private val repository: LocalDropRepository) { suspend operator fun invoke(device: LocalDevice) = repository.pairDevice(device) }
