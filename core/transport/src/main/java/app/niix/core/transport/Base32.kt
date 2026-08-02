package app.niix.core.transport

internal object Base32 {

    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz234567"

    fun encodeLower(data: ByteArray): String {
        if (data.isEmpty()) return ""
        val output = StringBuilder()
        var buffer = 0
        var bitsLeft = 0
        for (byte in data) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xFF)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                val index = (buffer shr (bitsLeft - 5)) and 0x1F
                output.append(ALPHABET[index])
                bitsLeft -= 5
            }
        }
        if (bitsLeft > 0) {
            val index = (buffer shl (5 - bitsLeft)) and 0x1F
            output.append(ALPHABET[index])
        }
        return output.toString()
    }
}
