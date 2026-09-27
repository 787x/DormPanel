package com.dormpanel.app.lan

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter

object LocalQr {
    fun encode(url: String, size: Int = 320): BitMatrix = QRCodeWriter().encode(url,
        BarcodeFormat.QR_CODE, size, size,
        mapOf(EncodeHintType.MARGIN to 4, EncodeHintType.CHARACTER_SET to "UTF-8"))

    fun bitmap(url: String, size: Int = 320): Bitmap {
        val matrix = encode(url, size)
        val pixels = IntArray(size * size) { index -> if (matrix[index % size, index / size]) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.RGB_565)
    }
}
