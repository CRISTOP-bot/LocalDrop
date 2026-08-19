package com.cristopher.localdrop.utils

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

fun sha256Hex(input: InputStream, bufferSize: Int = 64 * 1024): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(bufferSize)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

fun sha256Hex(file: File): String = file.inputStream().use(::sha256Hex)

fun isPrivateIpv4(host: String): Boolean {
    val parts = host.split('.').mapNotNull { it.toIntOrNull() }
    if (parts.size != 4 || parts.any { it !in 0..255 }) return false
    return parts[0] == 10 || (parts[0] == 192 && parts[1] == 168) || (parts[0] == 172 && parts[1] in 16..31)
}

fun uniqueFileName(original: String, exists: (String) -> Boolean): String {
    val clean = original.substringAfterLast('/').ifBlank { "archivo" }
    if (!exists(clean)) return clean
    val dot = clean.lastIndexOf('.')
    val base = if (dot > 0) clean.substring(0, dot) else clean
    val extension = if (dot > 0) clean.substring(dot) else ""
    var index = 1
    var candidate: String
    do { candidate = "$base ($index)$extension"; index++ } while (exists(candidate))
    return candidate
}
