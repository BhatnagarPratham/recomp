package app.recomp.data.ai

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Whether the phone has a working connection, for `AiGate`'s OFFLINE state (TRD §6.2). */
interface Connectivity {
    val online: Flow<Boolean>
}

/** The default network, counted as online once it has internet and Android has validated it. */
@Singleton
class AndroidConnectivity @Inject constructor(@ApplicationContext context: Context) : Connectivity {
    private val manager = context.getSystemService(ConnectivityManager::class.java)

    private fun NetworkCapabilities?.usable() =
        this != null && hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

    override val online: Flow<Boolean> = callbackFlow {
        trySend(manager.getNetworkCapabilities(manager.activeNetwork).usable())
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(caps.usable())
            }

            override fun onLost(network: Network) {
                trySend(false)
            }
        }
        manager.registerDefaultNetworkCallback(callback)
        awaitClose { manager.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()
}
