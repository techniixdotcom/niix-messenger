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
            // Each key is its own length-prefixed block holding exactly its serialised bytes, so
            // the whole block is the key. Passing the length explicitly means libsignal is told
            // how many bytes belong to the key, instead of reading from an offset.
            val preKeyBlock = readBlock(stream)
            val preKeyPublic = ECPublicKey(preKeyBlock, 0, preKeyBlock.size)
            val signedPreKeyId = stream.readInt()
            val signedPreKeyBlock = readBlock(stream)
            val signedPreKeyPublic = ECPublicKey(signedPreKeyBlock, 0, signedPreKeyBlock.size)
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
