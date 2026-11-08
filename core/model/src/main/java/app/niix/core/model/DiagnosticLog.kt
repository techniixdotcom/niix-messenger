package app.niix.core.model

/**
 * A short in-memory record of what the app has been doing, for working out why something failed.
 *
 * The app writes no log files, which is good for privacy but makes failures like "the update won't
 * install" pure guesswork. This keeps the privacy side intact:
 *
 * - entries live in a fixed-size ring buffer in memory and never touch disk
 * - [clear] runs on lock and wipe
 * - nothing is sent anywhere; the user can read it and copy it if they want
 * - callers record what happened, never message content, contact ids or key material
 *
 * It's in core/model with no dependencies so every layer can use it.
 */
object DiagnosticLog {

    private const val CAPACITY = 200

    private val entries = java.util.ArrayList<Entry>(CAPACITY)

    data class Entry(val atEpochMillis: Long, val area: String, val message: String)

    @Synchronized
    fun record(area: String, message: String) {
        if (entries.size >= CAPACITY) entries.removeAt(0)
        entries.add(Entry(System.currentTimeMillis(), area, message))
    }

    /** Everything currently held, oldest first. */
    @Synchronized
    fun snapshot(): List<Entry> = entries.toList()

    /** Throws everything away. Called on lock and wipe. */
    @Synchronized
    fun clear() {
        entries.clear()
    }

    @Synchronized
    fun render(): String {
        if (entries.isEmpty()) return "No diagnostic events recorded."
        val now = System.currentTimeMillis()
        return entries.joinToString("\n") { entry: Entry ->
            val ago = ((now - entry.atEpochMillis) / 1000).coerceAtLeast(0)
            "[${ago}s ago] ${entry.area}: ${entry.message}"
        }
    }
}
