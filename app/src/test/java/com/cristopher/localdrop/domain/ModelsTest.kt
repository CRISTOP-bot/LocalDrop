package com.cristopher.localdrop.domain

import com.cristopher.localdrop.domain.model.TransferDirection
import com.cristopher.localdrop.domain.model.TransferHistory
import com.cristopher.localdrop.domain.model.TransferProgress
import com.cristopher.localdrop.domain.model.TransferState
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelsTest {
    @Test fun completedZeroByteTransferHasFullProgress() {
        val progress = TransferProgress("empty", 0, 0, state = TransferState.COMPLETED)
        assertEquals(1f, progress.fraction)
    }

    @Test fun failedTransferPreservesErrorAndDirection() {
        val item = TransferHistory(1, "a.bin", 10, 100, "Tablet", TransferDirection.RECEIVED, TransferState.FAILED, "incomplete")
        assertEquals("incomplete", item.error)
        assertEquals(TransferDirection.RECEIVED, item.direction)
    }
}
