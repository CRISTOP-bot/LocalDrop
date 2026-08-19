package com.cristopher.localdrop.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TransferManifestTest {
    @Test fun roundTripsMultipleFiles() {
        val original = listOf(ManifestFile("foto ñ.jpg", 12, "image/jpeg", "abc"), ManifestFile("empty", 0, "application/octet-stream", null))
        assertEquals(original, TransferManifest.decode(TransferManifest.encode(original)))
    }

    @Test fun rejectsMalformedRows() {
        assertThrows(IllegalArgumentException::class.java) { TransferManifest.decode("not-a-manifest") }
    }
}
