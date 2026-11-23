package ai.tatawur.rafiq.bridge.setup

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * Reads a QR code back from what [TerminalQr] printed, as a terminal with light text on a dark background shows it:
 * a block is light, a space is dark. Lines that hold no QR characters are skipped, so the whole `pair` output can be
 * passed in.
 */
internal fun decodeTerminalQr(printed: String): String {
    val rows =
        printed.lines().filter { line ->
            line.isNotEmpty() &&
                line.all { it == ' ' || it == TerminalQr.FULL || it == TerminalQr.UPPER || it == TerminalQr.LOWER }
        }
    val modules =
        rows.flatMap { line ->
            listOf(
                line.map { it == TerminalQr.FULL || it == TerminalQr.UPPER },
                line.map { it == TerminalQr.FULL || it == TerminalQr.LOWER },
            )
        }
    val scale = 4
    val width = modules.first().size * scale
    val height = modules.size * scale
    val pixels =
        IntArray(width * height) { index ->
            val light = modules[index / width / scale][index % width / scale]
            if (light) WHITE else BLACK
        }
    val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width, height, pixels)))
    val hints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))
    return QRCodeReader().decode(bitmap, hints).text
}

private const val WHITE = 0xFFFFFF
private const val BLACK = 0x000000
