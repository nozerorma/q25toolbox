package com.kgr.q25toolbox.modules

import com.google.zxing.*
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream

/**
 * Reproduces the live failure: a real 720x720 screenshot of the Renfe test ticket, where the on-device
 * pipeline (AccessibilityService.takeScreenshot -> decodeBarcode) returned null while zxing-cpp decodes
 * the same screen fine. If this test PASSES, the Java decode path is not the problem (the content of the
 * takeScreenshot bitmap was), and the fix is to retry via root screencap on decode failure.
 */
class QrScreenshotDecodeTest {
    private fun loadRaw(name: String): IntArray {
        val stream = javaClass.classLoader.getResourceAsStream(name)
            ?: throw IllegalStateException("missing test resource $name")
        return DataInputStream(stream).use { s ->
            val w = s.readInt()
            val h = s.readInt()
            val px = IntArray(w * h)
            for (i in px.indices) px[i] = s.readInt()
            px
        }
    }

    private fun decodeLikeApp(pixels: IntArray): String? {
        val source = RGBLuminanceSource(720, 720, pixels)
        val hints = mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE, BarcodeFormat.AZTEC),
            DecodeHintType.TRY_HARDER to java.lang.Boolean.TRUE
        )
        val reader = MultiFormatReader()
        try {
            return reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text
        } catch (_: Throwable) {}
        try {
            return reader.decode(BinaryBitmap(GlobalHistogramBinarizer(source)), hints).text
        } catch (_: Throwable) {}
        try {
            return reader.decode(BinaryBitmap(HybridBinarizer(source.invert())), hints).text
        } catch (_: Throwable) {}
        return null
    }

    @Test fun javaDecoderReadsTheRealRenfeScreenshot() {
        val text = decodeLikeApp(loadRaw("screen_renfe.bin"))
        assertNotNull("Java ZXing must decode the real screenshot; if this fails the decoder needs work", text)
        assertTrue(text!!.startsWith("RENFE-TEST-TICKET"))
    }
}
