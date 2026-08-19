package com.cristopher.localdrop.utils

import android.net.Uri

data class QrConnection(val deviceId: String, val host: String, val port: Int, val name: String, val version: Int)

object QrConnectionParser {
    private const val CURRENT_VERSION = 1
    fun parse(raw: String): QrConnection {
        val uri = Uri.parse(raw.trim())
        require(uri.scheme == "localdrop") { "Esquema no válido" }
        require(uri.host == "connect") { "Host QR no válido" }
        val version = uri.getQueryParameter("v")?.toIntOrNull() ?: invalid("Falta la versión")
        require(version == CURRENT_VERSION) { "Versión QR no compatible" }
        val id = uri.getQueryParameter("id")?.trim().orEmpty()
        val host = uri.getQueryParameter("host")?.trim().orEmpty()
        val port = uri.getQueryParameter("port")?.toIntOrNull() ?: invalid("Puerto inválido")
        val name = uri.getQueryParameter("name")?.trim().orEmpty()
        require(id.length in 4..128) { "Identificador inválido" }
        require(isPrivateIpv4(host)) { "La dirección no pertenece a una red local" }
        require(port in 1..65535) { "Puerto inválido" }
        require(name.isNotEmpty() && name.length <= 64) { "Nombre inválido" }
        return QrConnection(id, host, port, name, version)
    }
    private fun invalid(message: String): Nothing = throw IllegalArgumentException(message)
}
