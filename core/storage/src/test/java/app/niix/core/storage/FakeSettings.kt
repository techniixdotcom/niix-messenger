package app.niix.core.storage

/** An in-memory KeyValueStore, so logic that only needs key-value storage can be tested. */
class FakeSettings : KeyValueStore {
    private val values = mutableMapOf<String, String>()
    override fun getString(key: String): String? = values[key]
    override fun setString(key: String, value: String) { values[key] = value }
    override fun getBool(key: String, default: Boolean): Boolean = values[key]?.toBoolean() ?: default
    override fun setBool(key: String, value: Boolean) = setString(key, value.toString())
    override fun remove(key: String) { values.remove(key) }
}
