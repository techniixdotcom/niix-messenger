package app.niix.ui

import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import app.niix.AppContainer
import app.niix.ConnectivityService
import app.niix.R
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Pull down to check relays for messages now, instead of waiting for the background timer.
 *
 * Shared by the home screen and conversations so both behave the same way.
 */
object PullToRefresh {

    /**
     * How long the spinner waits. Relays are reached over Tor, where one slow circuit can take a
     * long time; the spinner should not hang for that long.
     */
    private const val TIMEOUT_MILLIS = 30_000L

    fun attach(
        activity: AppCompatActivity,
        layout: SwipeRefreshLayout,
        container: AppContainer,
        reload: () -> Unit,
    ) {
        layout.setColorSchemeResources(R.color.niix_primary)
        layout.setProgressBackgroundColorSchemeResource(R.color.niix_surface)
        layout.setOnRefreshListener {
            // Harmless if the connection is already up; brings it up if it was dropped, for
            // example by "Disconnect when locked".
            ConnectivityService.start(activity)
            activity.lifecycleScope.launch {
                // The work runs in the app's own scope, so leaving the screen or hitting the
                // timeout stops only the spinner. The check itself finishes in the background,
                // and whatever it collects is kept.
                val work = container.appScope.async { container.conversations.refreshNow() }
                val reached = withTimeoutOrNull(TIMEOUT_MILLIS) { work.await() }
                layout.isRefreshing = false
                reload()
                val message = when (reached) {
                    null -> R.string.refresh_still_checking
                    false -> R.string.refresh_offline
                    true -> null
                }
                message?.let { Toast.makeText(activity, it, Toast.LENGTH_SHORT).show() }
            }
        }
    }
}
