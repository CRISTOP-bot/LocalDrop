package com.cristopher.localdrop.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class QrConnectionParserTest {
    @Test fun parsesEncodedNameOnce() {
        val value = QrConnectionParser.parse("localdrop://connect?v=1&id=device-1234&host=192.168.1.8&port=48123&name=Mi%20Tablet")
        assertEquals("Mi Tablet", value.name)
        assertEquals(48123, value.port)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMissingVersion() { QrConnectionParser.parse("localdrop://connect?id=device-1234&host=192.168.1.8&port=48123&name=Tablet") }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPublicHost() { QrConnectionParser.parse("localdrop://connect?v=1&id=device-1234&host=8.8.8.8&port=48123&name=Tablet") }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidPort() { QrConnectionParser.parse("localdrop://connect?v=1&id=device-1234&host=192.168.1.8&port=99999&name=Tablet") }
}
