package com.cristopher.localdrop.utils

import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

class QrConnectionParserTest {
    @Test fun parsesEncodedNameOnceAndValidatesFingerprint() {
        val publicKey = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(65) { (it + 1).toByte() })
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(Base64.getUrlDecoder().decode(publicKey)).joinToString("") { "%02x".format(it) }
        val value = QrConnectionParser.parse("localdrop://connect?v=1&id=device-1234&host=192.168.1.8&port=48123&name=Mi%20Tablet&pk=$publicKey&fp=$fingerprint")
        assertEquals("Mi Tablet", value.name)
        assertEquals(48123, value.port)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMissingVersion() { QrConnectionParser.parse("localdrop://connect?id=device-1234&host=192.168.1.8&port=48123&name=Tablet") }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPublicHost() { QrConnectionParser.parse("localdrop://connect?v=1&id=device-1234&host=8.8.8.8&port=48123&name=Tablet") }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidPort() { QrConnectionParser.parse("localdrop://connect?v=1&id=device-1234&host=192.168.1.8&port=99999&name=Tablet") }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMismatchedFingerprint() { QrConnectionParser.parse("localdrop://connect?v=1&id=device-1234&host=192.168.1.8&port=48123&name=Tablet&pk=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8&fp=0000000000000000000000000000000000000000000000000000000000000000") }
}
