package app.niix.desktop.ui

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File
import javax.imageio.ImageIO
import org.gnome.gdk.MemoryFormat
import org.gnome.gdk.MemoryTexture
import org.gnome.gdk.Texture

/** QR codes: drawing the ones NiiX shows, and reading one from a picture of another device's. */
object QrCodes {

    /** A crisp black-on-white texture of [content], [scale] pixels per module. */
    fun texture(content: String, scale: Int = 8): Texture? = runCatching {
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 2))
        val width = matrix.width * scale
        val height = matrix.height * scale
        val pixels = ByteArray(width * height * 3)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val value = if (matrix.get(x / scale, y / scale)) 0 else 255
                val offset = (y * width + x) * 3
                pixels[offset] = value.toByte()
                pixels[offset + 1] = value.toByte()
                pixels[offset + 2] = value.toByte()
            }
        }
        MemoryTexture(width, height, MemoryFormat.R8G8B8, pixels, (width * 3).toLong())
    }.getOrNull()

    /**
     * The text of the QR code in an image file (a screenshot or photo of the phone's screen), or
     * null if there isn't a readable one. Images over 40 megapixels are refused before decoding.
     */
    fun decode(file: File): String? = runCatching {
        ImageIO.createImageInputStream(file).use { input ->
            val readers = ImageIO.getImageReaders(input)
            if (!readers.hasNext()) return null
            val reader = readers.next()
            try {
                reader.input = input
                val w = reader.getWidth(0)
                val h = reader.getHeight(0)
                if (w <= 0 || h <= 0 || w.toLong() * h > MAX_PIXELS) return null
                val image = reader.read(0)
                val argb = IntArray(image.width * image.height)
                image.getRGB(0, 0, image.width, image.height, argb, 0, image.width)
                val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(image.width, image.height, argb)))
                val hints = mapOf(
                    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                    DecodeHintType.TRY_HARDER to true,
                )
                MultiFormatReader().decode(bitmap, hints).text
            } finally {
                reader.dispose()
            }
        }
    }.getOrNull()

    private const val MAX_PIXELS = 40_000_000L
}
