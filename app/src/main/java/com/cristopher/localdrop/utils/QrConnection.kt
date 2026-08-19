package com.cristopher.localdrop.utils

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

data class QrConnection(val deviceId: String, val host: String, val port: Int, val name: String, val version: Int)

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
        require(id.length in 4..128) { "Identificador inválido" }
        require(isPrivateIpv4(host)) { "La dirección no pertenece a una red local" }
        require(port in 1..65535) { "Puerto inválido" }
        require(name.isNotEmpty() && name.length <= 64) { "Nombre inválido" }
        return QrConnection(id, host, port, name, version)
    }
    private fun parseQuery(raw: String): Map<String, String> = raw.split('&').asSequence().filter { it.isNotEmpty() }.mapNotNull { pair ->
        val key = pair.substringBefore('=')
        val value = pair.substringAfter('=', "")
        if (key.isEmpty()) null else decode(key) to decode(value)
    }.toMap()
    private fun decode(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    private fun invalid(message: String): Nothing = throw IllegalArgumentException(message)
}
