package app.niix

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import io.matthewnelson.kmp.tor.runtime.NetworkObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Follows the phone's default network. Tor is told when it goes and when it comes back, and
 * [changes] tells the rest of the app when a network appears or replaces another.
 *
 * Without this, Tor only finds out from timeouts. After a switch from Wi-Fi to mobile data it keeps
 * using connections that died with the old network, and after an outage it backs off its own
 * reconnect attempts. Either way messages could wait many minutes after the network was fine.
 */
class NetworkMonitor(context: Context, private val scope: CoroutineScope) : NetworkObserver() {

    private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    @Volatile
    private var current: Network? = null

    // Android calls these one at a time, on a thread of its own.
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val previous = current
            current = network
            if (previous == network) return
            if (previous == null) {
                report(NetworkObserver.Connectivity.Connected)
            } else {
                // A switch. Turning Tor's network off and on again closes every connection over the
                // old network instead of leaving Tor to find out they're dead. The pause has to be
                // longer than the delay kmp-tor waits before acting, or it only sees the second.
                report(NetworkObserver.Connectivity.Disconnected)
                scope.launch {
                    delay(SWITCH_PAUSE_MILLIS)
                    if (current == network) report(NetworkObserver.Connectivity.Connected)
                }
            }
            _changes.tryEmit(Unit)
        }

        override fun onLost(network: Network) {
            if (current != network) return
            current = null
            report(NetworkObserver.Connectivity.Disconnected)
        }
    }

    init {
        manager.registerDefaultNetworkCallback(callback)
    }

    override fun isNetworkConnected(): Boolean = manager.activeNetwork != null

    private fun report(connectivity: NetworkObserver.Connectivity) = notify(connectivity)

    private companion object {
        const val SWITCH_PAUSE_MILLIS = 1_000L
    }
}
