package com.lilclaw.app.service

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed class GatewayState {
    data object Stopped : GatewayState()
    data object Extracting : GatewayState()
    data object Starting : GatewayState()
    data object Running : GatewayState()
    data class Error(val message: String) : GatewayState()
}

class GatewayManager(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<GatewayState>(GatewayState.Stopped)
    val state: StateFlow<GatewayState> = _state

    private val _extractionProgress = MutableStateFlow(0f)
    val extractionProgress: StateFlow<Float> = _extractionProgress

    private var gatewayProcess: Process? = null

    private val rootfsDir: File
        get() = File(context.filesDir, "rootfs")

    private val prootBin: File
        get() = File(context.filesDir, "proot")

    val isRootfsExtracted: Boolean
        get() = rootfsDir.exists() && File(rootfsDir, "usr/bin/node").exists()

    fun extractRootfs(onComplete: () -> Unit) {
        scope.launch {
            _state.value = GatewayState.Extracting
            try {
                // TODO: Download and extract rootfs tar.gz
                // For now, simulate extraction progress
                for (i in 1..10) {
                    delay(200)
                    _extractionProgress.value = i / 10f
                }
                _state.value = GatewayState.Stopped
                onComplete()
            } catch (e: Exception) {
                _state.value = GatewayState.Error("Extraction failed: ${e.message}")
            }
        }
    }

    fun start(port: Int = 3000) {
        if (_state.value == GatewayState.Running) return
        scope.launch {
            _state.value = GatewayState.Starting
            try {
                val cmd = listOf(
                    prootBin.absolutePath,
                    "--link2symlink",
                    "-0",
                    "-r", rootfsDir.absolutePath,
                    "-b", "/dev",
                    "-b", "/proc",
                    "-b", "/sys",
                    "-w", "/root",
                    "/usr/bin/env",
                    "HOME=/root",
                    "PATH=/usr/local/bin:/usr/bin:/bin",
                    "node",
                    "/root/.npm-global/bin/openclaw",
                    "gateway", "start", "--foreground",
                    "--port", port.toString(),
                )

                val processBuilder = ProcessBuilder(cmd)
                    .directory(rootfsDir)
                    .redirectErrorStream(true)

                gatewayProcess = processBuilder.start()

                // Wait briefly for startup, then check if process is alive
                delay(2000)
                if (gatewayProcess?.isAlive == true) {
                    _state.value = GatewayState.Running
                    // Monitor process in background
                    monitorProcess()
                } else {
                    val exitCode = gatewayProcess?.exitValue() ?: -1
                    _state.value = GatewayState.Error("Gateway exited with code $exitCode")
                }
            } catch (e: Exception) {
                _state.value = GatewayState.Error("Start failed: ${e.message}")
            }
        }
    }

    fun stop() {
        gatewayProcess?.let { process ->
            process.destroy()
            scope.launch {
                delay(5000)
                if (process.isAlive) {
                    process.destroyForcibly()
                }
            }
        }
        gatewayProcess = null
        _state.value = GatewayState.Stopped
    }

    fun restart(port: Int = 3000) {
        stop()
        scope.launch {
            delay(1000)
            start(port)
        }
    }

    private fun monitorProcess() {
        scope.launch {
            gatewayProcess?.let { process ->
                try {
                    val exitCode = process.waitFor()
                    if (_state.value == GatewayState.Running) {
                        _state.value = GatewayState.Error("Gateway exited unexpectedly (code $exitCode)")
                        // Auto-restart with backoff
                        delay(3000)
                        start()
                    }
                } catch (_: InterruptedException) {
                    // Expected on stop
                }
            }
        }
    }
}
