package app.niix

import app.niix.core.storage.EncryptedFileStore
import app.niix.core.transport.OnionKeyStore

class EncryptedOnionKeyStore(private val files: EncryptedFileStore) : OnionKeyStore {

    override fun load(): String? = runCatching { files.getString(KEY_NAME) }.getOrNull()

    override fun save(privateKey: String) {
        runCatching { files.putString(KEY_NAME, privateKey) }
    }

    override fun clear() {
        runCatching { files.delete(KEY_NAME) }
    }

    companion object {
        private const val KEY_NAME = "onion.key"
    }
}
