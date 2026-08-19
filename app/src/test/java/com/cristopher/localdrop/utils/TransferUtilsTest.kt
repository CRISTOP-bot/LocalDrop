package com.cristopher.localdrop.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class TransferUtilsTest {
    @Test fun uniqueNamesAvoidSafDuplicates() {
        val existing = mutableSetOf("photo.jpg", "photo (1).jpg", "photo (2).jpg")
        assertEquals("photo (3).jpg", uniqueFileName("photo.jpg") { existing.contains(it) })
    }

    @Test fun uniqueNamesPreserveFilesWithoutExtension() {
        assertEquals("README (1)", uniqueFileName("README") { it == "README" })
    }

    @Test fun sha256IsStreamingAndDeterministic() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256Hex(ByteArrayInputStream("abc".toByteArray())))
    }

    @Test fun privateNetworkValidationRejectsInternet() {
        assertTrue(isPrivateIpv4("192.168.1.10"))
        assertTrue(isPrivateIpv4("10.0.0.4"))
        assertFalse(isPrivateIpv4("8.8.8.8"))
        assertFalse(isPrivateIpv4("127.0.0.1"))
    }
}
