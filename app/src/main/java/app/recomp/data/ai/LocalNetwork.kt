package app.recomp.data.ai

import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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
    }
}

@Singleton
class AndroidLocalNetwork @Inject constructor(@ApplicationContext private val context: Context) : LocalNetwork {
    private val state = MutableStateFlow(granted())
    override val allowed: StateFlow<Boolean> = state

    override fun refresh() {
        state.value = granted()
    }

    private fun granted() = context.checkSelfPermission(LocalNetwork.PERMISSION) == PackageManager.PERMISSION_GRANTED
}
