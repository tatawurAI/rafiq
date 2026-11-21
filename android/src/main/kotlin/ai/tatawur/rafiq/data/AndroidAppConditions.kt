package ai.tatawur.rafiq.data

import ai.tatawur.rafiq.client.sync.AppConditions
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [AppConditions] from the process lifecycle and the default network. M2 has no foreground service,
 * so sockets and polling stop when the last screen goes to the background.
 *
 * Create it on the main thread: the lifecycle observer must be added there.
 */
class AndroidAppConditions(
    context: Context,
) : AppConditions {
    private val processLifecycle = ProcessLifecycleOwner.get().lifecycle
    private val isForeground = MutableStateFlow(processLifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val defaultNetwork =
        connectivity.activeNetwork.let { active -> DefaultNetwork(active, active.isUsable()) }

    override val foreground: StateFlow<Boolean> = isForeground.asStateFlow()
    override val networkAvailable: StateFlow<Boolean> = defaultNetwork.usable

    init {
        processLifecycle.addObserver(
            LifecycleEventObserver { owner, _ ->
                isForeground.value = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            },
        )
        connectivity.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities,
                ) {
                    defaultNetwork.onCapabilities(
                        network,
                        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                    )
                }

                override fun onLost(network: Network) {
                    defaultNetwork.onLost(network)
                }
            },
        )
    }

    private fun Network?.isUsable(): Boolean =
        this != null &&
            connectivity.getNetworkCapabilities(this)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ==
            true
}
