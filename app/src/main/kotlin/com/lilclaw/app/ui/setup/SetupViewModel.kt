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
    val progress: Float = 0f,
    val logLines: List<String> = emptyList(),
    val error: String? = null,
    val statusText: String = "正在准备...",
)

enum class SetupStep {
    WELCOME,      // splash screen
    PROVIDER,     // configure AI provider
    LAUNCHING,    // single loading animation for everything
    DONE,         // ready, navigate to WebView
}

class SetupViewModel(
    private val settings: SettingsRepository,
    private val gatewayManager: GatewayManager,
) : ViewModel() {

    private val _state = MutableStateFlow(SetupState())
    val state: StateFlow<SetupState> = _state

    init {
        // Check if setup already complete → quick start
        viewModelScope.launch {
            settings.isSetupComplete.collect { complete ->
                if (complete && _state.value.step == SetupStep.WELCOME) {
                    when (gatewayManager.state.value) {
                        is GatewayState.Running -> {
                            _state.update { it.copy(step = SetupStep.DONE) }
                        }
                        is GatewayState.Idle, is GatewayState.Error -> {
                            // App restarted, need to start gateway again
                            _state.update {
                                it.copy(
                                    step = SetupStep.LAUNCHING,
                                    statusText = "正在唤醒...",
                                )
                            }
                            startQuick()
                        }
                        else -> {
                            // Already starting, go to launching screen
                            _state.update {
                                it.copy(
                                    step = SetupStep.LAUNCHING,
                                    statusText = "启动中...",
                                )
                            }
                        }
                    }
                }
            }
        }

        // Observe progress
        viewModelScope.launch {
            gatewayManager.progress.collect { p ->
                _state.update { it.copy(progress = p) }
            }
        }

        // Observe gateway state for status text (only during LAUNCHING)
        viewModelScope.launch {
            gatewayManager.state.collect { gwState ->
                _state.update { current ->
                    // Only update status text if we're on the launching screen
                    if (current.step != SetupStep.LAUNCHING) return@update current

                    val statusText = when (gwState) {
                        is GatewayState.Preparing -> "正在准备..."
                        is GatewayState.Downloading -> "正在下载组件..."
                        is GatewayState.Extracting -> "正在安装..."
                        is GatewayState.Starting -> "正在启动 AI 引擎..."
                        is GatewayState.WaitingForUi -> "马上就好..."
                        is GatewayState.Running -> "准备就绪！"
                        is GatewayState.Error -> "出了点问题"
                        is GatewayState.Idle -> "准备中..."
                    }
                    current.copy(
                        statusText = statusText,
                        error = if (gwState is GatewayState.Error) gwState.message else null,
                    )
                }
            }
        }

        // Collect log lines
        viewModelScope.launch {
            gatewayManager.logLines.collect { line ->
                _state.update { s ->
                    val newLines = (s.logLines + line).takeLast(50)
                    s.copy(logLines = newLines)
                }
            }
        }
    }

    fun onGetStarted() {
        _state.update { it.copy(step = SetupStep.PROVIDER) }
    }

    fun onProviderChanged(provider: String) {
        _state.update { it.copy(provider = provider, error = null) }
    }

    fun onApiKeyChanged(key: String) {
        _state.update { it.copy(apiKey = key, error = null) }
    }

    fun onModelChanged(model: String) {
        _state.update { it.copy(model = model) }
    }

    /**
     * User pressed Continue on the provider screen.
     * Transition to Launching → bootstrap everything.
     */
    fun onContinue() {
        val s = _state.value
        _state.update {
            it.copy(
                step = SetupStep.LAUNCHING,
                progress = 0f,
                logLines = emptyList(),
                error = null,
                statusText = "正在准备...",
            )
        }

        viewModelScope.launch {
            settings.completeSetup(s.provider, s.apiKey, s.model)
        }

        gatewayManager.bootstrap(
            provider = s.provider,
            apiKey = s.apiKey,
            model = s.model,
        ) {
            _state.update { it.copy(step = SetupStep.DONE) }
        }
    }

    fun onRetry() {
        _state.update {
            it.copy(
                progress = 0f,
                logLines = emptyList(),
                error = null,
                statusText = "正在重试...",
            )
        }
        viewModelScope.launch {
            // Use saved settings if state doesn't have them (e.g. quick start retry)
            val provider = _state.value.provider.ifEmpty { settings.providerValue() }
            val apiKey = _state.value.apiKey.ifEmpty { settings.apiKeyValue() }
            val model = _state.value.model.ifEmpty { settings.modelValue() }

            gatewayManager.bootstrap(
                provider = provider,
                apiKey = apiKey,
                model = model,
            ) {
                _state.update { it.copy(step = SetupStep.DONE) }
            }
        }
    }

    private fun startQuick() {
        viewModelScope.launch {
            val provider = settings.providerValue()
            val apiKey = settings.apiKeyValue()
            val model = settings.modelValue()

            gatewayManager.quickStart(
                provider = provider,
                apiKey = apiKey,
                model = model,
            ) {
                _state.update { it.copy(step = SetupStep.DONE) }
            }
        }
    }
}
