package com.cristopher.localdrop.utils

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

fun copyExactly(
    input: InputStream,
    output: OutputStream,
    expectedBytes: Long,
    digest: MessageDigest? = null,
    bufferSize: Int = 64 * 1024,
    onBytes: (Long) -> Unit = {},
    isCancelled: () -> Boolean = { false }
): Long {
    require(expectedBytes >= 0) { "expectedBytes must not be negative" }
    var copied = 0L
    val buffer = ByteArray(bufferSize)
    while (copied < expectedBytes) {
        if (isCancelled()) throw java.util.concurrent.CancellationException("copy cancelled")
        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), expectedBytes - copied).toInt())
        if (count < 0) break
        output.write(buffer, 0, count)
        digest?.update(buffer, 0, count)
        copied += count
        onBytes(copied)
    }
    if (copied != expectedBytes) throw IOException("Incomplete transfer: $copied/$expectedBytes bytes")
    return copied
}
