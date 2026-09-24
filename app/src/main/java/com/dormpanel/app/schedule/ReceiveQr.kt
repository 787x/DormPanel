package com.dormpanel.app.schedule

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter

/** White background and four-module quiet zone are preserved in the returned matrix. */
internal object ReceiveQr {
    fun encode(url: String, size: Int = 320): BitMatrix = QRCodeWriter().encode(url,
        BarcodeFormat.QR_CODE, size, size,
        mapOf(EncodeHintType.MARGIN to 4, EncodeHintType.CHARACTER_SET to "UTF-8"))
}
