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
 * Pull down to retry anything waiting to send and re-check the Nostr relays. Shared by the home
 * screen and conversations.
 */
object PullToRefresh {

    /**
     * How long the spinner waits. Everything goes over Tor, where a slow circuit can take a while.
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
            // Does nothing if the connection is up; brings it back if it was dropped, e.g. by
            // "Disconnect when locked".
            ConnectivityService.start(activity)
            activity.lifecycleScope.launch {
                // Runs in the app scope, so leaving the screen or timing out only stops the
                // spinner; the work itself finishes.
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
