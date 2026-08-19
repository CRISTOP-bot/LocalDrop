package com.cristopher.localdrop.utils

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.util.Locale

fun Uri.displayName(resolver: ContentResolver): String {
    resolver.query(this, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) return c.getString(0)
    }
    return path?.substringAfterLast('/') ?: "archivo"
}

fun Uri.contentSize(resolver: ContentResolver): Long {
    resolver.query(this, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
    }
    return resolver.openAssetFileDescriptor(this, "r")?.use { it.length } ?: -1L
}

fun Context.deviceTypeName(): String = if (resources.configuration.smallestScreenWidthDp >= 600) "tablet" else "phone"
fun Long.readableSize(): String {
    if (this < 1024) return "$this B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = toDouble(); var index = -1
    do { value /= 1024; index++ } while (value >= 1024 && index < units.lastIndex)
    return String.format(Locale.getDefault(), "%.1f %s", value, units[index])
}
fun Long.readableRate(): String = if (this >= 1024) "${(this / 1024).readableSize()}/s" else "$this B/s"
