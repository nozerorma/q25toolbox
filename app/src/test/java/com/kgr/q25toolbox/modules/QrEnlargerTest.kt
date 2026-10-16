package com.kgr.q25toolbox.modules

import com.google.zxing.*
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class QrEnlargerTest {

    @Test
    fun testEncodeAndDecodeQrCode() {
        val text = "RENFE-TEST-TICKET-12345"
        val matrix: BitMatrix = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, 200, 200)
        val w = matrix.width
        val h = matrix.height
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                pixels[y * w + x] = if (matrix.get(x, y)) -0x1000000 else -0x1 // 0xFF000000 : 0xFFFFFFFF
            }
        }

        val source = RGBLuminanceSource(w, h, pixels)
        val hints = mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE, BarcodeFormat.AZTEC),
            DecodeHintType.TRY_HARDER to java.lang.Boolean.TRUE
        )
        val reader = MultiFormatReader()
        val result = reader.decode(BinaryBitmap(HybridBinarizer(source)), hints)
        assertNotNull(result)
        assertEquals(text, result.text)
    }

    @Test
    fun testEncodeAndDecodeAztecCode() {
        val text = "AZTEC-TRAIN-PASS-98765"
        val matrix: BitMatrix = MultiFormatWriter().encode(text, BarcodeFormat.AZTEC, 200, 200)
        val w = matrix.width
        val h = matrix.height
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                pixels[y * w + x] = if (matrix.get(x, y)) -0x1000000 else -0x1
            }
        }

        val source = RGBLuminanceSource(w, h, pixels)
        val hints = mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE, BarcodeFormat.AZTEC),
            DecodeHintType.TRY_HARDER to java.lang.Boolean.TRUE
        )
        val reader = MultiFormatReader()
        val result = reader.decode(BinaryBitmap(HybridBinarizer(source)), hints)
        assertNotNull(result)
        assertEquals(text, result.text)
    }
}
