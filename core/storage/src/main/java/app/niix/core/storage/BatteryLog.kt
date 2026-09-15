package app.niix.core.storage

/**
 * Records what the app did that costs battery, when the user has asked it to.
 *
 * Off by default and does nothing at all until switched on. The point is to replace guesswork
 * with numbers: how long Tor takes to bootstrap on a cold start, how often the sleep alarms
 * actually fire versus how often they were scheduled, how much of the day the connection was up.
 * Those figures decide whether polling every 30 minutes is sensible or whether holding the
 * connection open would have been cheaper, and they cannot be reasoned out from documentation.
 *
 * Written to the encrypted database rather than memory, because the measurement has to survive
 * the service stopping and the process being killed, which is exactly what is being measured. It
 * is wiped with everything else.
 *
 * Deliberately records only timing and lifecycle events. There is no message content, no contact,
 * no address, nothing about who was talked to. Something a user is invited to paste to a
 * developer must not carry anything they would not choose to share.
 */
class BatteryLog internal constructor(private val settings: SettingsStore) {

    fun isEnabled(): Boolean = settings.getBool(SettingsStore.KEY_BATTERY_LOG_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        settings.setBool(SettingsStore.KEY_BATTERY_LOG_ENABLED, enabled)
        if (enabled) {
            // Start clean so a run measures one period rather than blending it with an older one.
            settings.remove(SettingsStore.KEY_BATTERY_LOG)
            record("logging started")
        }
    }

    /**
     * Appends one event.
     *
     * Silent when disabled, so call sites do not need to check first and cannot accidentally
     * record while the feature is off.
     */
    fun record(event: String) {
        if (!isEnabled()) return
        runCatching {
            val line = "${System.currentTimeMillis()}\t${event.replace('\n', ' ').take(120)}"
            val existing = settings.getString(SettingsStore.KEY_BATTERY_LOG).orEmpty()
            val lines = (if (existing.isEmpty()) emptyList() else existing.split('\n')) + line
            // Bounded. A week of wakeups would otherwise grow without limit inside the database.
            val trimmed = if (lines.size > MAX_ENTRIES) lines.takeLast(MAX_ENTRIES) else lines
            settings.setString(SettingsStore.KEY_BATTERY_LOG, trimmed.joinToString("\n"))
        }
    }

    fun entries(): List<Pair<Long, String>> =
        settings.getString(SettingsStore.KEY_BATTERY_LOG).orEmpty()
            .split('\n')
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val at = line.substringBefore('\t').toLongOrNull() ?: return@mapNotNull null
                at to line.substringAfter('\t')
            }

    fun clear() {
        settings.remove(SettingsStore.KEY_BATTERY_LOG)
    }

    /**
     * A report to paste somewhere.
     *
     * Times are relative to the first entry, not absolute, so the report does not double as a
     * record of exactly when the device was in use.
     */
    fun render(): String {
        val all = entries()
        if (all.isEmpty()) return "No battery events recorded yet."
        val start = all.first().first
        val sb = StringBuilder()
        sb.append("NiiX battery log\n")
        sb.append("${all.size} events over ${formatDuration(all.last().first - start)}\n\n")

        // A summary first: the counts and totals are what actually answer the question, and the
        // event list below is only there to check the summary against.
        val counts = all.groupingBy { it.second.substringBefore(':') }.eachCount()
        sb.append("Summary\n")
        counts.entries.sortedByDescending { it.value }.forEach { (what, n) ->
            sb.append("  ${n.toString().padStart(4)}  $what\n")
        }

        var connectedMillis = 0L
        var lastUp: Long? = null
        for ((at, what) in all) {
            if (what.startsWith("connected")) lastUp = at
            if (what.startsWith("disconnected") && lastUp != null) {
                connectedMillis += at - lastUp
                lastUp = null
            }
        }
        val total = all.last().first - start
        if (total > 0) {
            val percent = (connectedMillis * 100 / total)
            sb.append("\n  connected for ${formatDuration(connectedMillis)} of ${formatDuration(total)} ($percent%)\n")
        }

        sb.append("\nEvents\n")
        for ((at, what) in all) {
            sb.append("  +${formatDuration(at - start).padEnd(10)} $what\n")
        }
        return sb.toString()
    }

    private fun formatDuration(millis: Long): String {
        val seconds = millis / 1000
        return when {
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "${seconds / 60}m${seconds % 60}s"
            else -> "${seconds / 3600}h${(seconds % 3600) / 60}m"
        }
    }

    private companion object {
        const val MAX_ENTRIES = 2000
    }
}
