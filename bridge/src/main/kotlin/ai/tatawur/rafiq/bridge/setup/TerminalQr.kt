package ai.tatawur.rafiq.bridge.setup

import io.nayuki.qrcodegen.QrCode

/**
 * Draws a QR code as text with Unicode half blocks, two modules per character cell, so it fits in a normal terminal.
 *
 * Light modules, including the quiet zone around the code, are drawn as blocks in the terminal's text colour, and
 * dark modules are left as the terminal's background. On the usual light-on-dark terminal that gives dark modules on
 * a light square, which every scanner reads. On a dark-on-light terminal the code comes out inverted, which the app's
 * scanner also reads.
 */
object TerminalQr {
    /** Modules of light border around the code. The QR spec asks for 4. */
    const val QUIET_ZONE = 4

    /** Renders [text] as a QR code with medium error correction. */
    fun render(text: String): String = render(QrCode.encodeText(text, QrCode.Ecc.MEDIUM))

    /** Renders [qr], one line per two rows of modules, each line ending in a newline. */
    fun render(qr: QrCode): String {
        val size = qr.size + 2 * QUIET_ZONE

        fun light(
            x: Int,
            y: Int,
        ): Boolean {
            val qx = x - QUIET_ZONE
            val qy = y - QUIET_ZONE
            val inside = qx in 0 until qr.size && qy in 0 until qr.size
            return !inside || !qr.getModule(qx, qy)
        }
        return buildString {
            for (y in 0 until size step 2) {
                for (x in 0 until size) {
                    val top = light(x, y)
                    // An odd size leaves a last row of only tops. The row under it is the terminal's background.
                    val bottom = y + 1 < size && light(x, y + 1)
                    append(
                        when {
                            top && bottom -> FULL
                            top -> UPPER
                            bottom -> LOWER
                            else -> ' '
                        },
                    )
                }
                append('\n')
            }
        }
    }

    const val FULL = '█'
    const val UPPER = '▀'
    const val LOWER = '▄'
}
