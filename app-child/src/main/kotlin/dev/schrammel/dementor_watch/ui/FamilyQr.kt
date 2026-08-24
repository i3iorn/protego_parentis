package dev.schrammel.dementor_watch.ui

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * The family code, as something a camera can read.
 *
 * **The passphrase is deliberately not in here.** A QR code is a picture: it can
 * be photographed across a room, left on a screen in a shared space, or forwarded
 * by whoever was shown it once. Putting the family's secret in one would trade the
 * entire security model for a step of typing, and would contradict the rule the
 * rest of this app already follows — the passphrase is sealed in the keystore and
 * no screen can display it.
 *
 * So the code carries the same string the connected screen already prints in
 * plain text: transport, server and topic. Scanning it fills in the fiddly part
 * and leaves the secret to be spoken aloud, which is the half that should be hard
 * to intercept.
 *
 * That is not the whole defence in any case. A device that scans the code and
 * knows the passphrase reaches the topic and is then a stranger in a queue: it
 * cannot read a rule, answer a request or be believed about anything until a
 * parent approves it on their own phone.
 */
object FamilyQr {

    /**
     * Renders [content] as a square QR bitmap [size] pixels on a side.
     *
     * Drawn in black on white regardless of the app's theme. A dark-mode QR code
     * is an inverted QR code, and while some scanners cope, the ones that do not
     * fail in a way that looks like the code is wrong rather than the colours.
     *
     * Returns null if the content will not fit, which for a family token it never
     * will not — but a QR writer that can throw deserves a caller that can be told
     * so rather than a crash on a screen somebody is trying to set up.
     */
    fun encode(content: String, size: Int): Bitmap? = runCatching {
        val hints = mapOf(
            // A code that is going to be read off a screen at arm's length, once,
            // by someone standing next to you. Q tolerates a quarter of the
            // symbol being lost, which covers a fingerprint or a glare patch.
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.Q,
            EncodeHintType.CHARACTER_SET to "UTF-8",
            // Two modules of quiet zone rather than the default four: the code is
            // already inside a padded card, and the standard margin on top of that
            // shrinks the symbol enough to hurt the scan it is meant to help.
            EncodeHintType.MARGIN to 2,
        )

        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            val row = y * size
            for (x in 0 until size) {
                pixels[row + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
            }
        }

        Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }.getOrNull()
}
