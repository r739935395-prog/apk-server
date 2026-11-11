package com.rafat.whatshub.ui.components

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object QrBitmapGenerator {
    suspend fun generate(qrPayload: String, sizePx: Int = 640): ImageBitmap? =
        withContext(Dispatchers.Default) {
            if (qrPayload.isBlank() || sizePx <= 0) return@withContext null
            runCatching {
                val hints = mapOf(
                    EncodeHintType.MARGIN to 2,
                    EncodeHintType.CHARACTER_SET to "UTF-8"
                )
                val matrix = QRCodeWriter().encode(qrPayload, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
                val pixels = IntArray(sizePx * sizePx)
                for (y in 0 until sizePx) {
                    val rowOffset = y * sizePx
                    for (x in 0 until sizePx) {
                        pixels[rowOffset + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
                    }
                }
                Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.ARGB_8888).asImageBitmap()
            }.getOrNull()
        }
}
