package ai.tatawur.rafiq.pairing

import ai.tatawur.rafiq.protocol.api.PairingLink
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QrFrameDecoderTest {
    private val link = PairingLink.build("https://box.example.ts.net", "ABCD-EFGH")

    /** A camera-like frame: the code drawn [scale] times, with padding at the end of each row as cameras add. */
    private fun frame(
        inverted: Boolean,
        scale: Int = 4,
        padding: Int = 16,
    ): Triple<ByteArray, Int, Int> {
        val matrix = QRCodeWriter().encode(link, BarcodeFormat.QR_CODE, 0, 0)
        val width = (matrix.width + 8) * scale
        val stride = width + padding
        val luma = ByteArray(stride * width)
        for (y in 0 until width) {
            for (x in 0 until width) {
                val mx = x / scale - 4
                val my = y / scale - 4
                val dark = mx in 0 until matrix.width && my in 0 until matrix.height && matrix[mx, my]
                luma[y * stride + x] = (if (dark != inverted) DARK else LIGHT).toByte()
            }
        }
        return Triple(luma, width, stride)
    }

    @Test
    fun a_frame_with_the_pairing_code_gives_the_link() {
        val (luma, width, stride) = frame(inverted = false)

        assertEquals(link, QrFrameDecoder().decode(luma, width, width, stride))
    }

    @Test
    fun a_code_printed_light_on_dark_reads_too() {
        val (luma, width, stride) = frame(inverted = true)

        assertEquals(link, QrFrameDecoder().decode(luma, width, width, stride))
    }

    @Test
    fun a_frame_with_no_code_gives_null() {
        val width = 64
        val blank = ByteArray(width * width) { LIGHT.toByte() }

        assertNull(QrFrameDecoder().decode(blank, width, width, width))
    }

    private companion object {
        const val DARK = 0x10
        const val LIGHT = 0xF0
    }
}
