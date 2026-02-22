package com.lilclaw.app.service

import android.content.Context
import android.util.Log
import com.lilclaw.app.service.gateway.ConfigWriter
import com.lilclaw.app.service.gateway.ProcessRunner
import com.lilclaw.app.service.gateway.RootfsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

sealed class GatewayState {
    data object Idle : GatewayState()
    data object Preparing : GatewayState()     // extracting bundled rootfs
    data object Downloading : GatewayState()   // downloading layers (fallback/update)
    data object Extracting : GatewayState()
    data object Starting : GatewayState()      // gateway process starting
    data object WaitingForUi : GatewayState()  // gateway up, polling serve-ui
    data object Running : GatewayState()       // both gateway + serve-ui ready
    data class Error(val message: String) : GatewayState()
}

/**
 * Orchestrates the gateway lifecycle: rootfs setup, config, process start/stop.
 * Delegates heavy lifting to [RootfsManager], [ProcessRunner], [ConfigWriter].
 */
class GatewayManager(private val context: Context) {

    companion object {
        private const val TAG = "GatewayManager"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val rootfs = RootfsManager(context)
    private val processes = ProcessRunner(context)

    private val _state = MutableStateFlow<GatewayState>(GatewayState.Idle)
    val state: StateFlow<GatewayState> = _state

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress

    private val _logLines = MutableSharedFlow<String>(replay = 50, extraBufferCapacity = 200)
    val logLines: SharedFlow<String> = _logLines

    val isRootfsReady: Boolean get() = rootfs.isReady

    private fun log(msg: String) {
        Log.i(TAG, msg)
        _logLines.tryEmit(msg)
    }

    /**
     * Full bootstrap: extract rootfs → write config → start gateway → poll UI ready.
     */
    fun bootstrap(
        provider: String,
        apiKey: String,
        model: String,
        port: Int = 3000,
        onReady: () -> Unit,
    ) {
        scope.launch {
            try {
                GatewayService.start(context)

                // Phase 1: Extract rootfs
                if (!rootfs.isReady) {
                    _state.value = GatewayState.Preparing
                    _progress.value = 0f
                    GatewayService.updateStatus(context, "正在准备...")

                    rootfs.extractAll(
                        onStateChange = { phase ->
                            _state.value = when (phase) {
                                "downloading" -> GatewayState.Downloading
                                "extracting" -> GatewayState.Extracting
                                else -> GatewayState.Preparing
                            }
                        },
                        log = ::log,
                    )
                }

                // Collect rootfs progress
                launch { rootfs.progress.collect { _progress.value = it } }

                // Phase 2: Write config
                log("写入配置...")
                ConfigWriter.writeGatewayConfig(rootfs.rootfsDir, port, provider, apiKey, model)
                log("配置完成 ✓")
                _progress.value = 0.8f

                // Phase 3: Start processes
                _state.value = GatewayState.Starting
                GatewayService.updateStatus(context, "正在启动引擎...")
                startProcesses(port)

                // Phase 4-5: Wait for ports
                waitForReady(port, onReady)

            } catch (e: Exception) {
                Log.e(TAG, "Bootstrap failed", e)
                log("错误: ${e.message}")
                _state.value = GatewayState.Error(e.message ?: "未知错误")
            }
        }
    }

    /**
     * Quick start for app restart (rootfs already extracted, config already written).
     */
    fun quickStart(
        provider: String = "",
        apiKey: String = "",
        model: String = "",
        port: Int = 3000,
        onReady: () -> Unit = {},
    ) {
        if (_state.value == GatewayState.Running) return
        scope.launch {
            try {
                GatewayService.start(context)

                if (!rootfs.isReady) {
                    _state.value = GatewayState.Error("环境未就绪")
                    return@launch
                }

                // Check for layer updates
                val stale = rootfs.getStaleLayers(::log)
                if (stale.isNotEmpty()) {
                    log("正在更新 ${stale.joinToString { it.name }}...")
                    rootfs.updateLayers(stale, ::log)
                }

                if (provider.isNotEmpty()) {
                    ConfigWriter.writeGatewayConfig(rootfs.rootfsDir, port, provider, apiKey, model)
                }

                // Start processes
                _state.value = GatewayState.Starting
                _progress.value = 0.3f
                GatewayService.updateStatus(context, "正在启动引擎...")
                rootfs.ensureExecutable()
                startProcesses(port)

                // Wait for ports
                waitForReady(port, onReady)

            } catch (e: Exception) {
                Log.e(TAG, "Quick start failed", e)
                log("错误: ${e.message}")
                _state.value = GatewayState.Error(e.message ?: "未知错误")
            }
        }
    }

    fun stop() {
        processes.stopAll()
        scope.launch {
            delay(5000)
            processes.forceStopAll()
        }
        _state.value = GatewayState.Idle
        GatewayService.stop(context)
    }

    fun restart(port: Int = 3000, provider: String = "", apiKey: String = "", model: String = "") {
        stop()
        scope.launch {
            delay(1000)
            quickStart(port = port, provider = provider, apiKey = apiKey, model = model)
        }
    }

    // ── Private helpers ───────────────────────────────────

    private suspend fun startProcesses(port: Int) {
        log("启动 AI 引擎...")
        val gwProcess = processes.startGateway(port)
        pumpProcessLog(gwProcess, "gw")

        log("启动聊天界面...")
        val uiProcess = processes.startServeUi()
        if (uiProcess != null) {
            pumpProcessLog(uiProcess, "ui")
            log("聊天界面已启动")
        } else {
            log("serve-ui.cjs 未找到，跳过聊天界面")
        }
    }

    private fun pumpProcessLog(process: Process, prefix: String) {
        scope.launch {
            process.inputStream?.bufferedReader()?.use { reader ->
                reader.lineSequence().forEach { line ->
                    Log.d(TAG, "[$prefix] $line")
                    if (prefix == "gw" && (
                        line.contains("listening") || line.contains("error", ignoreCase = true)
                        || line.contains("ready") || line.contains("started")
                        || line.contains("config") || line.contains("agent")
                    )) {
                        _logLines.tryEmit(line.take(120))
                    }
                }
            }
        }
    }

    private suspend fun waitForReady(port: Int, onReady: () -> Unit) {
        // Start serve-ui polling FIRST — it's fast (~2s)
        // The SPA handles offline gateway gracefully
        log("等待界面就绪...")
        waitForPort(3001, timeoutMs = 30_000)
        _progress.value = 0.7f
        log("Chat UI ready — loading WebView")

        // Signal that UI is ready (WebView can load now!)
        _state.value = GatewayState.WaitingForUi
        onReady()

        // Now wait for gateway in background
        log("Waiting for gateway...")
        waitForPort(port, timeoutMs = 60_000)
        _progress.value = 1f

        log("就绪")
        _state.value = GatewayState.Running
        GatewayService.updateStatus(context, "运行中")
        onReady()

        // Start monitoring gateway process for crash recovery
        monitorGateway(port)
    }

    private suspend fun waitForPort(port: Int, timeoutMs: Long) = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastError: String? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                val conn = URL("http://127.0.0.1:$port/").openConnection() as HttpURLConnection
                conn.connectTimeout = 2000
                conn.readTimeout = 2000
                conn.requestMethod = "GET"
                val code = conn.responseCode
                conn.disconnect()
                if (code in 100..499) {
                    log("Port $port ready (HTTP $code)")
                    return@withContext
                }
                lastError = "HTTP $code"
            } catch (e: Exception) {
                lastError = e.message
            }
            delay(500)
        }
        throw RuntimeException("Port $port not ready after ${timeoutMs / 1000}s: $lastError")
    }

    /**
     * Monitor gateway process health. If it dies, auto-restart up to 3 times.
     */
    private fun monitorGateway(port: Int) {
        scope.launch {
            var restartCount = 0
            val maxRestarts = 3
            while (restartCount < maxRestarts) {
                delay(5000) // Check every 5 seconds
                val process = processes.gatewayProcess
                if (process == null || !process.isAlive) {
                    if (_state.value == GatewayState.Idle) break // Intentional stop
                    restartCount++
                    log("Gateway crashed! Restarting ($restartCount/$maxRestarts)...")
                    _state.value = GatewayState.Starting
                    GatewayService.updateStatus(context, "Restarting... ($restartCount/$maxRestarts)")
                    try {
                        val gwProcess = processes.startGateway(port)
                        pumpProcessLog(gwProcess, "gw")
                        waitForPort(port, timeoutMs = 30_000)
                        _state.value = GatewayState.Running
                        GatewayService.updateStatus(context, "Running")
                        log("Gateway restarted successfully")
                        restartCount = 0 // Reset counter on success
                    } catch (e: Exception) {
                        log("Restart failed: ${e.message}")
                        _state.value = GatewayState.Error("Gateway crashed ($restartCount/$maxRestarts)")
                    }
                }
            }
        }
    }
}
