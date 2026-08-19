package com.cristopher.localdrop.utils

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

data class QrConnection(val deviceId: String, val host: String, val port: Int, val name: String, val version: Int, val publicKey: String, val fingerprint: String)

object QrConnectionParser {
    private const val CURRENT_VERSION = 1
    fun parse(raw: String): QrConnection {
        val uri = URI(raw.trim())
        require(uri.scheme == "localdrop") { "Esquema no válido" }
        require(uri.host == "connect") { "Host QR no válido" }
        val query = parseQuery(uri.rawQuery.orEmpty())
        val version = query["v"]?.toIntOrNull() ?: invalid("Falta la versión")
        require(version == CURRENT_VERSION) { "Versión QR no compatible" }
        val id = query["id"]?.trim().orEmpty()
        val host = query["host"]?.trim().orEmpty()
        val port = query["port"]?.toIntOrNull() ?: invalid("Puerto inválido")
        val name = query["name"]?.trim().orEmpty()
        val publicKey = query["pk"]?.trim().orEmpty()
        val fingerprint = query["fp"]?.trim()?.lowercase().orEmpty()
        require(id.length in 4..128) { "Identificador inválido" }
        require(isPrivateIpv4(host)) { "La dirección no pertenece a una red local" }
        require(port in 1..65535) { "Puerto inválido" }
        require(name.isNotEmpty() && name.length <= 64) { "Nombre inválido" }
        val publicKeyDer = runCatching { Base64.getUrlDecoder().decode(publicKey) }.getOrElse { invalid("Clave pública inválida") }
        require(publicKeyDer.size in 50..512) { "Clave pública inválida" }
        require(fingerprint.matches(Regex("[0-9a-f]{64}"))) { "Fingerprint inválido" }
        val actualFingerprint = MessageDigest.getInstance("SHA-256").digest(publicKeyDer).joinToString("") { "%02x".format(it) }
        require(actualFingerprint == fingerprint) { "Fingerprint no coincide con la clave" }
        return QrConnection(id, host, port, name, version, publicKey, fingerprint)
    }
    private fun parseQuery(raw: String): Map<String, String> = raw.split('&').asSequence().filter { it.isNotEmpty() }.mapNotNull { pair ->
        val key = pair.substringBefore('=')
        val value = pair.substringAfter('=', "")
        if (key.isEmpty()) null else decode(key) to decode(value)
    }.toMap()
    private fun decode(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    private fun invalid(message: String): Nothing = throw IllegalArgumentException(message)
}
