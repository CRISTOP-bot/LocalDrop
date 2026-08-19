package com.cristopher.localdrop.presentation

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix

@Composable
fun QrCode(payload: String, modifier: Modifier = Modifier) {
    val bitmap = remember(payload) { createQr(payload) }
    Image(bitmap = bitmap.asImageBitmap(), contentDescription = "Código QR de conexión", modifier = modifier.size(260.dp))
}

private fun createQr(value: String): Bitmap {
    val matrix: BitMatrix = MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, 700, 700, mapOf(EncodeHintType.MARGIN to 1))
    return Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888).also { bitmap ->
        for (x in 0 until matrix.width) for (y in 0 until matrix.height) bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    }
}
