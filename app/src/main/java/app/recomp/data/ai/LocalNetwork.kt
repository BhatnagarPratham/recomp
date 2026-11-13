package app.recomp.data.ai

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Android's local-network permission (TRD §6.2, AI-05). From Android 17, an app targeting API 37 can't reach an
 * address on the local network without it, so an OpenAI-compatible server on your own network needs it. The gate
 * reads [allowed] and says so when it's refused, rather than letting the request fail like a timeout.
 */
interface LocalNetwork {
    val allowed: StateFlow<Boolean>

    /** Reads the permission again: after its prompt is answered, and when the app comes back from system settings. */
    fun refresh()

    companion object {
        /** `Manifest.permission.ACCESS_LOCAL_NETWORK` (API 37), by name. */
        const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

        /** Android 17, the first version that has the permission and blocks local-network traffic without it. */
        const val FIRST_SDK = 37

        /**
         * Before Android 17 the permission doesn't exist: the local network comes with INTERNET, and asking for an
         * unknown permission is always refused. So an older phone is always allowed.
         */
        fun allowed(sdkInt: Int, granted: Boolean): Boolean = sdkInt < FIRST_SDK || granted

        /**
         * Whether *Allow* should open the app's page in system settings instead: the request came back refused at
         * once with no rationale to show, which is how Android answers once the prompt has been refused for good.
         */
        fun openSettings(granted: Boolean, showRationale: Boolean, answeredAfterMs: Long): Boolean =
            !granted && !showRationale && answeredAfterMs < PROMPT_MS

        /** Quicker than anyone can answer a prompt. */
        const val PROMPT_MS = 400L
    }
}

/** Where a server's name leads, for a name whose text doesn't say (TRD §6.2). Tests use a stand-in. */
interface HostResolver {
    /** The addresses [host] resolves to; empty when it can't be resolved in time. */
    suspend fun addresses(host: String): List<InetAddress>
}

@Singleton
class DnsHostResolver @Inject constructor() : HostResolver {
    override suspend fun addresses(host: String): List<InetAddress> = withContext(Dispatchers.IO) {
        withTimeoutOrNull(LOOKUP_MS) { runInterruptible { runCatching { InetAddress.getAllByName(host).toList() }.getOrDefault(emptyList()) } }.orEmpty()
    }

    private companion object {
        const val LOOKUP_MS = 3_000L
    }
}

@Singleton
class AndroidLocalNetwork @Inject constructor(@ApplicationContext private val context: Context) : LocalNetwork {
    private val state = MutableStateFlow(granted())
    override val allowed: StateFlow<Boolean> = state

    override fun refresh() {
        state.value = granted()
    }

    private fun granted() =
        LocalNetwork.allowed(Build.VERSION.SDK_INT, context.checkSelfPermission(LocalNetwork.PERMISSION) == PackageManager.PERMISSION_GRANTED)
}
