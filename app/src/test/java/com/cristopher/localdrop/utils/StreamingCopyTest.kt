package com.cristopher.localdrop.utils

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class StreamingCopyTest {
    @Test fun copiesZeroBytes() {
        val output = ByteArrayOutputStream()
        assertEquals(0L, copyExactly(ByteArrayInputStream(byteArrayOf()), output, 0))
    }

    @Test fun copiesSmallAndLargePayloadsWithoutReadBytes() {
        val input = ByteArray(256 * 1024) { (it % 251).toByte() }
        val output = ByteArrayOutputStream()
        assertEquals(input.size.toLong(), copyExactly(ByteArrayInputStream(input), output, input.size.toLong(), bufferSize = 1024))
        assertArrayEquals(input, output.toByteArray())
    }

    @Test(expected = IOException::class)
    fun incompleteInputNeverCompletes() { copyExactly(ByteArrayInputStream(byteArrayOf(1, 2)), ByteArrayOutputStream(), 3) }

    @Test(expected = java.util.concurrent.CancellationException::class)
    fun cancellationStopsCopy() { copyExactly(ByteArrayInputStream(ByteArray(10_000)), ByteArrayOutputStream(), 10_000, isCancelled = { true }) }
}
