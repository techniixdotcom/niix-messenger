package app.niix.core.crypto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.state.PreKeyBundle

internal object PreKeyBundleCodec {

    private const val VERSION = 1

    fun encode(bundle: PreKeyBundle): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { stream ->
            stream.writeByte(VERSION)
            stream.writeInt(bundle.registrationId)
            stream.writeInt(bundle.deviceId)
            stream.writeInt(bundle.preKeyId)
            writeBlock(stream, bundle.preKey!!.serialize())
            stream.writeInt(bundle.signedPreKeyId)
            writeBlock(stream, bundle.signedPreKey.serialize())
            writeBlock(stream, bundle.signedPreKeySignature)
            writeBlock(stream, bundle.identityKey.serialize())
            stream.writeInt(bundle.kyberPreKeyId)
            writeBlock(stream, bundle.kyberPreKey.serialize())
            writeBlock(stream, bundle.kyberPreKeySignature)
        }
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): PreKeyBundle {
        DataInputStream(ByteArrayInputStream(bytes)).use { stream ->
            val version = stream.readByte().toInt()
            require(version == VERSION) { "Unsupported bundle version $version" }
            val registrationId = stream.readInt()
            val deviceId = stream.readInt()
            val preKeyId = stream.readInt()
            val preKeyPublic = ECPublicKey(readBlock(stream), 0)
            val signedPreKeyId = stream.readInt()
            val signedPreKeyPublic = ECPublicKey(readBlock(stream), 0)
            val signedPreKeySignature = readBlock(stream)
            val identityKey = IdentityKey(readBlock(stream), 0)
            val kyberPreKeyId = stream.readInt()
            val kyberPreKeyPublic = KEMPublicKey(readBlock(stream))
            val kyberPreKeySignature = readBlock(stream)

            return PreKeyBundle(
                registrationId,
                deviceId,
                preKeyId,
                preKeyPublic,
                signedPreKeyId,
                signedPreKeyPublic,
                signedPreKeySignature,
                identityKey,
                kyberPreKeyId,
                kyberPreKeyPublic,
                kyberPreKeySignature,
            )
        }
    }

    private fun writeBlock(stream: DataOutputStream, data: ByteArray) {
        stream.writeInt(data.size)
        stream.write(data)
    }

    private fun readBlock(stream: DataInputStream): ByteArray {
        val length = stream.readInt()
        require(length in 0..MAX_BLOCK) { "Invalid block length $length" }
        val data = ByteArray(length)
        stream.readFully(data)
        return data
    }

    private const val MAX_BLOCK = 8192
}
