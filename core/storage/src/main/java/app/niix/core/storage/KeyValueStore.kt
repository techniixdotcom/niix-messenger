package app.niix.core.storage

/**
 * The small slice of settings that logic classes actually need.
 *
 * Exists so classes holding security decisions can be tested without a database. RemoteWipeSettings
 * decides whether a message erases the device, and that logic was untestable purely because it
 * named a concrete SettingsStore whose constructor needs SQLCipher. Depending on what is used
 * rather than where it lives costs nothing and makes the decision checkable.
 */
interface KeyValueStore {
    fun getString(key: String): String?
    fun setString(key: String, value: String)
    fun getBool(key: String, default: Boolean): Boolean
    fun setBool(key: String, value: Boolean)
    fun remove(key: String)
}
