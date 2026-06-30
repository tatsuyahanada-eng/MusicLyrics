package com.netdiag.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.netdiag.core.wifi.ChannelLoad
import com.netdiag.core.wifi.WifiAp
import com.netdiag.core.wifi.WifiSurvey
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WifiUiState(
    val wifiEnabled: Boolean = false,
    val hasPermission: Boolean = false,
    val aps: List<WifiAp> = emptyList(),
    val channelLoad: List<ChannelLoad> = emptyList(),
    val connectedRssi: Int? = null,
    val scanning: Boolean = false,
)

class WifiViewModel(app: Application) : AndroidViewModel(app) {

    private val survey = WifiSurvey(app)
    private val _state = MutableStateFlow(WifiUiState(wifiEnabled = survey.isWifiEnabled()))
    val state: StateFlow<WifiUiState> = _state.asStateFlow()

    init {
        // Live connected-RSSI ticker; cheap and stops with the ViewModel.
        viewModelScope.launch {
            while (true) {
                _state.update { it.copy(connectedRssi = survey.connectedRssi()) }
                delay(2000)
            }
        }
    }

    fun onPermissionResult(granted: Boolean) {
        _state.update { it.copy(hasPermission = granted) }
        if (granted) refresh()
    }

    fun refresh() {
        if (!_state.value.hasPermission) return
        _state.update { it.copy(scanning = true, wifiEnabled = survey.isWifiEnabled()) }
        viewModelScope.launch {
            survey.requestScan()
            // Give the framework a moment to populate fresh results, then read.
            delay(1500)
            val aps = survey.scanResults()
            _state.update {
                it.copy(
                    aps = aps,
                    channelLoad = survey.channelLoad(aps),
                    scanning = false,
                )
            }
        }
    }
}
