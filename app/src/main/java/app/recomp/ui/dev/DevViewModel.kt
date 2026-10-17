package app.recomp.ui.dev

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.recomp.data.ai.ClaudeSpikeClient
import app.recomp.data.ai.SpikeResult
import app.recomp.healthconnect.HealthConnectReport
import app.recomp.healthconnect.HealthConnectSpike
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface AiSpikeState {
    data object Idle : AiSpikeState
    data object Running : AiSpikeState
    data class Done(val result: SpikeResult) : AiSpikeState
    data class Failed(val error: String) : AiSpikeState
}

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

    private val _ai = MutableStateFlow<AiSpikeState>(AiSpikeState.Idle)
    val ai: StateFlow<AiSpikeState> = _ai

    private val _hc = MutableStateFlow<HcSpikeState>(HcSpikeState.Unknown)
    val hc: StateFlow<HcSpikeState> = _hc

    fun runAiSpike(apiKey: String, meal: String) {
        _ai.value = AiSpikeState.Running
        viewModelScope.launch {
            _ai.value = try {
                AiSpikeState.Done(withContext(Dispatchers.IO) { ClaudeSpikeClient.estimate(apiKey.trim(), meal) })
            } catch (e: Exception) {
                AiSpikeState.Failed("${e::class.java.simpleName}: ${e.message}")
            }
        }
    }

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
