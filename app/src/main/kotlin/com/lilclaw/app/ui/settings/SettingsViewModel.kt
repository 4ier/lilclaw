package com.lilclaw.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lilclaw.app.service.GatewayManager
import com.lilclaw.app.service.GatewayState
import com.lilclaw.app.data.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsState(
    val provider: String = "",
    val model: String = "",
    val gatewayState: GatewayState = GatewayState.Stopped,
    val gatewayPort: Int = 3000,
)

class SettingsViewModel(
    private val settings: SettingsRepository,
    private val gatewayManager: GatewayManager,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state

    init {
        viewModelScope.launch {
            combine(
                settings.provider,
                settings.model,
                settings.gatewayPort,
                gatewayManager.state,
            ) { provider, model, port, gwState ->
                SettingsState(
                    provider = provider,
                    model = model,
                    gatewayPort = port,
                    gatewayState = gwState,
                )
            }.collect { _state.value = it }
        }
    }

    fun startGateway() = gatewayManager.start(_state.value.gatewayPort)
    fun stopGateway() = gatewayManager.stop()
    fun restartGateway() = gatewayManager.restart(_state.value.gatewayPort)
}
