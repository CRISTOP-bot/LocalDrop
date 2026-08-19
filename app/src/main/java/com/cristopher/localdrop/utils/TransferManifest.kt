package com.cristopher.localdrop.utils

import com.cristopher.localdrop.domain.model.IncomingFile
import java.util.Base64

data class ManifestFile(val name: String, val size: Long, val mimeType: String, val sha256: String?)

object TransferManifest {
    fun encode(files: List<ManifestFile>): String = files.joinToString("\n") { file ->
        listOf(encodeText(file.name), file.size.toString(), encodeText(file.mimeType), encodeText(file.sha256.orEmpty())).joinToString("\t")
    }
    fun decode(value: String): List<ManifestFile> = value.split('\n').filter(String::isNotBlank).map { row ->
        val columns = row.split('\t')
        require(columns.size == 4) { "Manifest inválido" }
        ManifestFile(decodeText(columns[0]), columns[1].toLongOrNull() ?: error("Tamaño inválido"), decodeText(columns[2]), decodeText(columns[3]).ifBlank { null })
    }
    private fun encodeText(value: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))
    private fun decodeText(value: String): String = Base64.getUrlDecoder().decode(value).toString(Charsets.UTF_8)
}
