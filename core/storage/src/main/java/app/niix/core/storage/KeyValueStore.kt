package app.niix.core.storage

/**
 * The bit of settings that logic classes actually need, so security decisions like
 * RemoteWipeSettings can be unit tested without a SQLCipher database.
 */
interface KeyValueStore {
    fun getString(key: String): String?
    fun setString(key: String, value: String)
    fun getBool(key: String, default: Boolean): Boolean
    fun setBool(key: String, value: Boolean)
    fun remove(key: String)
}
