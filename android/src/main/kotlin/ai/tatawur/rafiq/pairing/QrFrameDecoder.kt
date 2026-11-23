package ai.tatawur.rafiq.pairing

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer

/**
 * Finds a QR code in one camera frame with ZXing, on the phone, with no Google Play services.
 *
 * It reads inverted codes too. `rafiq-bridge pair` draws its code with the terminal's text colour, so on a terminal
 * with dark text on a light background the code comes out light on dark.
 *
 * Not thread-safe: use one per analysis thread.
 */
class QrFrameDecoder {
    private val reader =
        MultiFormatReader().apply {
            setHints(
                mapOf(
                    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                    DecodeHintType.ALSO_INVERTED to true,
                ),
            )
        }

    /**
     * The text of the QR code in a frame's brightness plane, or null when the frame has none that reads.
     *
     * @param luma one byte of brightness per pixel, row after row.
     * @param rowStride bytes from the start of one row to the next, which can be more than [width].
     */
    fun decode(
        luma: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int,
    ): String? {
        val source = PlanarYUVLuminanceSource(luma, rowStride, height, 0, 0, width, height, false)
        return try {
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
        } catch (_: ReaderException) {
            // Most frames hold no readable code: the camera is still moving or focusing.
            null
        } finally {
            reader.reset()
        }
    }
}
