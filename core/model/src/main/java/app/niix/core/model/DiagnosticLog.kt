package app.niix.core.model

/**
 * A short, in-memory record of what the app has been doing, for diagnosing failures.
 *
 * This app deliberately writes no logs anywhere. That protects the user -- there is no file for
 * anyone seizing the device to read, and nothing that survives a wipe -- but it also means that
 * when something fails there is no way to find out why. A whole class of problems (an update
 * that won't install, a connection that won't establish) becomes guesswork.
 *
 * The compromise here keeps the privacy property intact:
 *
 *  - entries live only in memory, in a fixed-size ring buffer. Nothing is ever written to disk,
 *    so there is no artifact to seize, and the process ending destroys it.
 *  - [clear] is called on lock and on wipe, so a locked or wiped device holds nothing.
 *  - nothing is transmitted anywhere. The user can read it, and copy it if they choose to.
 *  - callers are expected to record *what happened*, never message content, contact identifiers,
 *    or key material. See [record]'s contract.
 *
 * Deliberately in `core/model` with no Android or storage dependencies, so every layer can
 * record to it without inverting its dependencies.
 */
object DiagnosticLog {

    private const val CAPACITY = 200

    private val entries = java.util.ArrayList<Entry>(CAPACITY)

    data class Entry(val atEpochMillis: Long, val area: String, val message: String)

    /**
     * Records a diagnostic event.
     *
     * [area] is a coarse subsystem name ("update", "tor", "relay"). [message] must describe
     * *what happened*, not *what was said*: "download failed, 12MB of 68MB" is right, message
     * bodies, onion addresses, contact names, and anything derived from key material are not.
     * The buffer is capped, so old entries are dropped rather than growing without bound.
     */
    @Synchronized
    fun record(area: String, message: String) {
        if (entries.size >= CAPACITY) entries.removeAt(0)
        entries.add(Entry(System.currentTimeMillis(), area, message))
    }

    /** Everything currently held, oldest first. */
    @Synchronized
    fun snapshot(): List<Entry> = entries.toList()

    /** Discards everything. Called when the app locks and when data is wiped, so a locked or
     * wiped device is holding nothing that could describe what the user was doing. */
    @Synchronized
    fun clear() {
        entries.clear()
    }

    /** A plain-text rendering the user can read or copy. Timestamps are relative ("12s ago")
     * rather than absolute, so exporting this doesn't hand over a timeline of exactly when the
     * device was in use. */
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
