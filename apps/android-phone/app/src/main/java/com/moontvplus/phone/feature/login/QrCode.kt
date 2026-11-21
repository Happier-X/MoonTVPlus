package com.moontvplus.phone.feature.login

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/**
 * 本地生成二维码（zxing），不依赖服务端的图片接口。
 * 白底 + 4 模块静默区，保证手机在深色界面下也能扫出来。
 */
@Composable
fun QrCode(text: String, size: Dp = 200.dp) {
    val bitmap = remember(text) { runCatching { encode(text, 512) }.getOrNull() }
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White)
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "登录二维码",
                modifier = Modifier.size(size - 16.dp),
            )
        }
    }
}

private fun encode(text: String, pixels: Int): Bitmap {
    val hints = mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.CHARACTER_SET to "UTF-8")
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, pixels, pixels, hints)
    val width = matrix.width
    val height = matrix.height
    val pixelsArray = IntArray(width * height)
    for (y in 0 until height) {
        for (x in 0 until width) {
            pixelsArray[y * width + x] = if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
    }
    return Bitmap.createBitmap(pixelsArray, width, height, Bitmap.Config.ARGB_8888)
}
