package app.recomp.ui.dev

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.recomp.healthconnect.HealthConnectReport
import app.recomp.healthconnect.HealthConnectSpike
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface HcSpikeState {
    data object Unknown : HcSpikeState
    data class Unavailable(val reason: String) : HcSpikeState
    data class NeedsPermission(val granted: Int, val total: Int) : HcSpikeState
    data object Loading : HcSpikeState
    data class Ready(val report: HealthConnectReport) : HcSpikeState
    data class Failed(val error: String) : HcSpikeState
}

@HiltViewModel
class DevViewModel @Inject constructor(@ApplicationContext context: Context) : ViewModel() {

    val healthConnect = HealthConnectSpike(context)

    private val _hc = MutableStateFlow<HcSpikeState>(HcSpikeState.Unknown)
    val hc: StateFlow<HcSpikeState> = _hc

    fun refreshHealthConnect() {
        when (val status = healthConnect.sdkStatus()) {
            HealthConnectClient.SDK_AVAILABLE -> Unit
            else -> {
                _hc.value = HcSpikeState.Unavailable("Health Connect SDK status $status")
                return
            }
        }
        _hc.update { HcSpikeState.Loading }
        viewModelScope.launch {
            _hc.value = try {
                val granted = healthConnect.grantedPermissions().intersect(healthConnect.permissions)
                if (granted.size < healthConnect.permissions.size) {
                    HcSpikeState.NeedsPermission(granted.size, healthConnect.permissions.size)
                } else {
                    HcSpikeState.Ready(healthConnect.report())
                }
            } catch (e: Exception) {
                HcSpikeState.Failed("${e::class.java.simpleName}: ${e.message}")
            }
        }
    }
}
