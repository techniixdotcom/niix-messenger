package app.niix.core.storage

import app.niix.core.model.DiagnosticLog
import java.io.File
import java.lang.foreign.MemorySegment
import java.util.function.Function
import java.security.SecureRandom
import java.util.Base64
import org.gnome.glib.GLib
import org.gnome.glib.HashTable
import org.gnome.secret.Secret
import org.javagi.interop.Interop

/**
 * The desktop's stand-in for the Android keystore: 32-byte keys kept outside NiiX's own folder.
 *
 * Normally they live in the desktop keyring through libsecret. Inside a Flatpak that is the Secret
 * portal, which gives the app a keyring of its own unlocked by the user's login keyring; outside
 * one it's the Secret Service (GNOME Keyring, KWallet). Where neither exists, the keys are kept in
 * a file only the user can read, which protects nothing against someone who can read the user's
 * files. A passcode is what protects the data then, and Settings says so.
 *
 * Which of the two was chosen is recorded once, when the first key is made, and never changes on
 * its own: if the keyring is missing later, using a fresh file key instead would make every key
 * already in the keyring unreachable. The calls fail instead until the keyring is back.
 */
internal object KeyVault {

    enum class Backend { KEYRING, FILE }

    private const val MARKER_FILE = "vault"
    private const val KEYS_DIR = "vault-keys"
    private const val KEY_BYTES = 32
    private const val APP_ATTRIBUTE = "app.niix.NiiX"

    @Volatile
    private var dir: File? = null

    /** For tests, and for builds that must never touch the keyring. */
    @Volatile
    internal var keyringDisabled: Boolean = System.getenv("NIIX_NO_KEYRING") == "1"

    private val random = SecureRandom()

    fun configure(directory: File) {
        StoragePaths.ensurePrivateDirectory(directory)
        dir = directory
    }

    /** Which store the keys are in, or null before the first key is made. */
    @Synchronized
    fun backend(): Backend? = readMarker()?.first

    /** The key called [alias], made first if [create] and it doesn't exist. Null if it doesn't. */
    @Synchronized
    fun key(alias: String, create: Boolean): ByteArray? {
        val (backend, instance) = readMarker() ?: if (create) chooseBackend() else return null
        return when (backend) {
            Backend.KEYRING -> keyringKey(instance, alias, create)
            Backend.FILE -> fileKey(alias, create)
        }
    }

    @Synchronized
    fun delete(alias: String) {
        val (backend, instance) = readMarker() ?: return
        when (backend) {
            Backend.KEYRING -> runCatching { keyringClear(instance, alias) }
            Backend.FILE -> runCatching { keyFile(alias).delete() }
        }
    }

    /** Forgets every key and which store was used, so the next key starts afresh. */
    @Synchronized
    fun destroy(aliases: List<String>) {
        aliases.forEach(::delete)
        val directory = dir ?: return
        runCatching { File(directory, KEYS_DIR).deleteRecursively() }
        runCatching { File(directory, MARKER_FILE).delete() }
    }

    private fun chooseBackend(): Pair<Backend, String> {
        val instance = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val backend = if (!keyringDisabled && keyringWorks(instance)) {
            Backend.KEYRING
        } else {
            DiagnosticLog.record("storage", "no keyring available, keys are kept in a private file")
            Backend.FILE
        }
        writeMarker(backend, instance)
        return backend to instance
    }

    /** Stores and reads back a test entry. Anything short of that counts as no keyring. */
    private fun keyringWorks(instance: String): Boolean = runCatching {
        val probe = "probe-" + ByteArray(8).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        keyringStore(instance, "probe", probe)
        val back = keyringLookup(instance, "probe")
        keyringClear(instance, "probe")
        back == probe
    }.getOrElse {
        DiagnosticLog.record("storage", "keyring not usable (${it.javaClass.simpleName})")
        false
    }

    private fun keyringKey(instance: String, alias: String, create: Boolean): ByteArray? {
        val stored = keyringLookup(instance, alias)
        if (stored != null) {
            val decoded = runCatching { Base64.getDecoder().decode(stored) }.getOrNull()
            check(decoded != null && decoded.size == KEY_BYTES) { "Keyring entry for $alias is damaged" }
            return decoded
        }
        if (!create) return null
        val fresh = ByteArray(KEY_BYTES).also(random::nextBytes)
        keyringStore(instance, alias, Base64.getEncoder().encodeToString(fresh))
        val check = keyringLookup(instance, alias)
        check(check != null && Base64.getDecoder().decode(check).contentEquals(fresh)) { "Keyring did not keep the key" }
        return fresh
    }

    private fun fileKey(alias: String, create: Boolean): ByteArray? {
        val file = keyFile(alias)
        if (file.isFile) {
            val bytes = file.readBytes()
            check(bytes.size == KEY_BYTES) { "Key file for $alias is damaged" }
            return bytes
        }
        if (!create) return null
        val fresh = ByteArray(KEY_BYTES).also(random::nextBytes)
        writePrivately(file, fresh)
        return fresh
    }

    private fun keyFile(alias: String): File {
        val directory = File(requireDir(), KEYS_DIR)
        StoragePaths.ensurePrivateDirectory(directory)
        return File(directory, alias.filter { it.isLetterOrDigit() || it == '_' } + ".key")
    }

    private fun attributes(instance: String, alias: String): HashTable<String, String> {
        val readString = Function<MemorySegment, String> { Interop.getString(it).orEmpty() }
        val table = HashTable<String, String>(GLib::strHash, GLib::strEqual, readString, readString)
        table["application"] = APP_ATTRIBUTE
        table["instance"] = instance
        table["slot"] = alias
        return table
    }

    private fun keyringLookup(instance: String, alias: String): String? =
        Secret.passwordLookupvSync(null, attributes(instance, alias), null)

    private fun keyringStore(instance: String, alias: String, value: String) {
        val stored = Secret.passwordStorevSync(
            null,
            attributes(instance, alias),
            Secret.COLLECTION_DEFAULT,
            "NiiX ($alias)",
            value,
            null,
        )
        check(stored) { "Keyring refused to store $alias" }
    }

    private fun keyringClear(instance: String, alias: String) {
        Secret.passwordClearvSync(null, attributes(instance, alias), null)
    }

    private fun readMarker(): Pair<Backend, String>? {
        val file = File(requireDir(), MARKER_FILE)
        if (!file.isFile) return null
        val parts = file.readText(Charsets.UTF_8).trim().split(':')
        check(parts.size == 2) { "Key store marker is damaged" }
        val backend = when (parts[0]) {
            "keyring" -> Backend.KEYRING
            "file" -> Backend.FILE
            else -> error("Key store marker is damaged")
        }
        return backend to parts[1]
    }

    private fun writeMarker(backend: Backend, instance: String) {
        val name = if (backend == Backend.KEYRING) "keyring" else "file"
        writePrivately(File(requireDir(), MARKER_FILE), "$name:$instance".toByteArray(Charsets.UTF_8))
    }

    private fun requireDir(): File = dir ?: error("KeyVault used before configure()")

    private fun writePrivately(target: File, data: ByteArray) {
        target.parentFile?.let(StoragePaths::ensurePrivateDirectory)
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.delete()
        tmp.createNewFile()
        StoragePaths.makePrivate(tmp)
        java.io.FileOutputStream(tmp).use { it.write(data); it.fd.sync() }
        if (!tmp.renameTo(target)) {
            target.delete()
            check(tmp.renameTo(target)) { "Unable to persist key material" }
        }
    }
}
