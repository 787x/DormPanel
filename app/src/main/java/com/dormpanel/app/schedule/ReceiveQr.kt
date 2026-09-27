package com.dormpanel.app.schedule

import com.google.zxing.common.BitMatrix
import com.dormpanel.app.lan.LocalQr

/** White background and four-module quiet zone are preserved in the returned matrix. */
internal object ReceiveQr {
    fun encode(url: String, size: Int = 320): BitMatrix = LocalQr.encode(url, size)
}
