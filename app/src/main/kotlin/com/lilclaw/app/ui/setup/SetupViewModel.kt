package com.lilclaw.app.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lilclaw.app.data.SettingsRepository
import com.lilclaw.app.service.GatewayManager
import com.lilclaw.app.service.GatewayState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SetupState(
    val step: SetupStep = SetupStep.WELCOME,
    val provider: String = "",
    val apiKey: String = "",
    val model: String = "",
    val isTestingConnection: Boolean = false,
    val connectionError: String? = null,
    val extractionProgress: Float = 0f,
    val isDownloading: Boolean = false,
)

enum class SetupStep { WELCOME, EXTRACT, PROVIDER, DONE }

class SetupViewModel(
    private val settings: SettingsRepository,
    private val gatewayManager: GatewayManager,
) : ViewModel() {

    private val _state = MutableStateFlow(SetupState())
    val state: StateFlow<SetupState> = _state

    init {
        viewModelScope.launch {
            settings.isSetupComplete.collect { complete ->
                if (complete) _state.update { it.copy(step = SetupStep.DONE) }
            }
        }
        viewModelScope.launch {
            gatewayManager.extractionProgress.collect { progress ->
                _state.update { it.copy(extractionProgress = progress) }
            }
        }
        viewModelScope.launch {
            gatewayManager.state.collect { gwState ->
                _state.update {
                    it.copy(isDownloading = gwState is GatewayState.Downloading)
                }
            }
        }
    }

    fun onNextFromWelcome() {
        if (gatewayManager.isRootfsExtracted) {
            _state.update { it.copy(step = SetupStep.PROVIDER) }
        } else {
            _state.update { it.copy(step = SetupStep.EXTRACT) }
            gatewayManager.extractRootfs {
                _state.update { it.copy(step = SetupStep.PROVIDER) }
            }
        }
    }

    fun onProviderChanged(provider: String) {
        _state.update { it.copy(provider = provider, connectionError = null) }
    }

    fun onApiKeyChanged(key: String) {
        _state.update { it.copy(apiKey = key, connectionError = null) }
    }

    fun onModelChanged(model: String) {
        _state.update { it.copy(model = model) }
    }

    fun onTestConnection() {
        _state.update { it.copy(isTestingConnection = true, connectionError = null) }
        viewModelScope.launch {
            // TODO: Actually test API key validity
            kotlinx.coroutines.delay(1000)
            _state.update { it.copy(isTestingConnection = false) }
        }
    }

    fun onComplete() {
        viewModelScope.launch {
            val s = _state.value
            settings.completeSetup(s.provider, s.apiKey, s.model)
            gatewayManager.start()
            _state.update { it.copy(step = SetupStep.DONE) }
        }
    }
}
