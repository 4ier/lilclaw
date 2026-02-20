package com.lilclaw.app.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

sealed class GatewayState {
    data object Stopped : GatewayState()
    data object Downloading : GatewayState()
    data object Extracting : GatewayState()
    data object Starting : GatewayState()
    data object Running : GatewayState()
    data class Error(val message: String) : GatewayState()
}

data class LayerInfo(
    val name: String,
    val url: String,
    val sizeBytes: Long,
    val displaySize: String,
)

class GatewayManager(private val context: Context) {

    companion object {
        private const val TAG = "GatewayManager"
        private const val RELEASES_BASE =
            "https://github.com/4ier/lilclaw/releases/download/layers-v2"

        val LAYERS = listOf(
            LayerInfo("base", "$RELEASES_BASE/base-arm64-2.0.0.tar.gz", 42_713_180L, "41 MB"),
            LayerInfo("openclaw", "$RELEASES_BASE/openclaw-2026.2.17.tar.gz", 33_380_716L, "32 MB"),
            LayerInfo("chatspa", "$RELEASES_BASE/chatspa-0.3.0.tar.gz", 236_000L, "231 KB"),
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<GatewayState>(GatewayState.Stopped)
    val state: StateFlow<GatewayState> = _state

    private val _extractionProgress = MutableStateFlow(0f)
    val extractionProgress: StateFlow<Float> = _extractionProgress

    private val _currentLayer = MutableStateFlow("")
    val currentLayer: StateFlow<String> = _currentLayer

    private var gatewayProcess: Process? = null
    private var serveUiProcess: Process? = null

    private val rootfsDir: File
        get() = File(context.filesDir, "rootfs")

    private val nativeLibDir: File
        get() = File(context.applicationInfo.nativeLibraryDir)

    private val prootBin: File
        get() = File(nativeLibDir, "libproot.so")

    private val tallocLib: File
        get() = File(nativeLibDir, "libtalloc.so")

    private val prootLoaderBin: File
        get() = File(nativeLibDir, "libproot_loader.so")

    private val layersJson: File
        get() = File(rootfsDir, ".layers.json")

    val isRootfsExtracted: Boolean
        get() = layersJson.exists()
                && File(rootfsDir, "usr/bin/node").exists()
                && File(rootfsDir, "usr/local/bin/openclaw").exists()

    fun extractRootfs(onComplete: () -> Unit) {
        scope.launch {
            try {
                if (isRootfsExtracted) {
                    Log.i(TAG, "Rootfs already extracted, skipping")
                    onComplete()
                    return@launch
                }

                // Calculate total download size for progress
                val totalBytes = LAYERS.sumOf { it.sizeBytes }
                var completedBytes = 0L

                rootfsDir.mkdirs()

                for (layer in LAYERS) {
                    _currentLayer.value = layer.name
                    val tarGzFile = File(context.cacheDir, "${layer.name}.tar.gz")

                    // Download phase
                    _state.value = GatewayState.Downloading
                    downloadFile(layer.url, tarGzFile) { layerProgress ->
                        val layerDownloaded = (layer.sizeBytes * layerProgress).toLong()
                        _extractionProgress.value =
                            ((completedBytes + layerDownloaded).toFloat() / totalBytes * 0.7f)
                                .coerceAtMost(0.7f)
                    }

                    // Extract phase
                    _state.value = GatewayState.Extracting
                    extractTarGz(tarGzFile, rootfsDir)
                    tarGzFile.delete()

                    completedBytes += layer.sizeBytes
                    _extractionProgress.value = (completedBytes.toFloat() / totalBytes * 0.7f)
                        .coerceAtMost(0.7f)

                    Log.i(TAG, "Layer '${layer.name}' installed")
                }

                // Post-extraction setup
                _currentLayer.value = "setup"
                _extractionProgress.value = 0.8f

                writeAndroidCompat()
                makeRootfsExecutable()
                writeLayersJson()

                _extractionProgress.value = 1f
                _currentLayer.value = ""

                if (!isRootfsExtracted) {
                    throw RuntimeException("Rootfs extraction failed: key binaries not found")
                }

                Log.i(TAG, "All layers installed successfully")
                _state.value = GatewayState.Stopped
                onComplete()
            } catch (e: Exception) {
                Log.e(TAG, "Rootfs extraction failed", e)
                _state.value = GatewayState.Error("Setup failed: ${e.message}")
                _currentLayer.value = ""
                rootfsDir.deleteRecursively()
            }
        }
    }

    private fun writeAndroidCompat() {
        val file = File(rootfsDir, "root/android-compat.cjs")
        file.parentFile?.mkdirs()
        file.writeText(
            "const os = require('os');\n" +
            "const _ni = os.networkInterfaces;\n" +
            "os.networkInterfaces = function() {\n" +
            "  try { return _ni.call(this); } catch(e) {\n" +
            "    return { lo: [{ address: '127.0.0.1', netmask: '255.0.0.0', family: 'IPv4', mac: '00:00:00:00:00:00', internal: true, cidr: '127.0.0.1/8' }] };\n" +
            "  }\n" +
            "};\n"
        )
        Log.i(TAG, "Wrote android-compat.cjs")
    }

    private fun writeLayersJson() {
        val now = java.time.Instant.now().toString()
        val json = JSONObject().apply {
            put("base", JSONObject().apply {
                put("version", "2.0.0")
                put("installedAt", now)
            })
            put("openclaw", JSONObject().apply {
                put("version", "2026.2.17")
                put("installedAt", now)
            })
            put("chatspa", JSONObject().apply {
                put("version", "0.3.0")
                put("installedAt", now)
            })
        }
        layersJson.writeText(json.toString(2))
        Log.i(TAG, "Wrote .layers.json")
    }

    private suspend fun downloadFile(
        urlStr: String,
        target: File,
        onProgress: (Float) -> Unit
    ) = withContext(Dispatchers.IO) {
        Log.i(TAG, "Downloading $urlStr")
        var connection: HttpURLConnection? = null
        try {
            var url = URL(urlStr)
            var redirectCount = 0
            while (redirectCount < 5) {
                connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 30_000
                connection.readTimeout = 300_000
                connection.instanceFollowRedirects = false
                val code = connection.responseCode
                if (code in 301..302 || code == 307 || code == 308) {
                    val location = connection.getHeaderField("Location")
                    connection.disconnect()
                    url = URL(location)
                    redirectCount++
                    continue
                }
                break
            }

            val totalBytes = connection?.contentLengthLong ?: -1L
            var downloadedBytes = 0L

            target.parentFile?.mkdirs()
            connection?.inputStream?.let { inputStream ->
                BufferedInputStream(inputStream, 65536).use { bis ->
                    FileOutputStream(target).use { fos ->
                        val buffer = ByteArray(65536)
                        var read: Int
                        while (bis.read(buffer).also { read = it } != -1) {
                            fos.write(buffer, 0, read)
                            downloadedBytes += read
                            if (totalBytes > 0) {
                                onProgress((downloadedBytes.toFloat() / totalBytes).coerceAtMost(1f))
                            }
                        }
                    }
                }
            }
            Log.i(TAG, "Downloaded ${downloadedBytes / 1024 / 1024}MB to ${target.name}")
        } finally {
            connection?.disconnect()
        }
    }

    private suspend fun extractTarGz(
        tarGzFile: File,
        targetDir: File,
    ) = withContext(Dispatchers.IO) {
        Log.i(TAG, "Extracting ${tarGzFile.name} to ${targetDir.absolutePath}")
        targetDir.mkdirs()

        val process = ProcessBuilder(
            "tar", "xzf", tarGzFile.absolutePath, "-C", targetDir.absolutePath
        ).redirectErrorStream(true).start()

        val exitCode = process.waitFor()
        if (exitCode != 0) {
            val error = process.inputStream.bufferedReader().readText()
            throw RuntimeException("tar extraction failed (code $exitCode): $error")
        }

        Log.i(TAG, "Extraction of ${tarGzFile.name} complete")
    }

    private fun buildProotCommand(cmd: String, port: Int? = null): List<String> {
        return listOf(
            "sh", "-c",
            buildString {
                append("export LD_LIBRARY_PATH=${libDir.absolutePath}:${nativeLibDir.absolutePath} && ")
                append("export PROOT_TMP_DIR=${context.cacheDir.absolutePath} && ")
                append("export PROOT_LOADER=${prootLoaderBin.absolutePath} && ")
                append("exec ${prootBin.absolutePath}")
                append(" --link2symlink -0")
                append(" -r ${rootfsDir.absolutePath}")
                append(" -b /dev -b /proc -b /sys")
                append(" -w /root")
                append(" /usr/bin/env HOME=/root")
                append(" PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
                append(" NODE_OPTIONS='--require /root/android-compat.cjs'")
                append(" $cmd")
            }
        )
    }

    fun start(port: Int = 3000, provider: String = "", apiKey: String = "", model: String = "") {
        if (_state.value == GatewayState.Running) return
        scope.launch {
            _state.value = GatewayState.Starting
            try {
                if (!prootBin.exists()) {
                    throw RuntimeException("proot binary not found at ${prootBin.absolutePath}")
                }
                if (!isRootfsExtracted) {
                    throw RuntimeException("Rootfs not extracted")
                }

                if (provider.isNotEmpty()) {
                    writeGatewayConfig(port, provider, apiKey, model)
                }

                setupLibDir()
                makeRootfsExecutable()
                prootBin.setExecutable(true)

                // Start gateway
                val gwCmd = buildProotCommand(
                    "node /usr/local/bin/openclaw gateway run --allow-unconfigured --port $port --token lilclaw-local"
                )

                Log.i(TAG, "Starting gateway on port $port")
                val processBuilder = ProcessBuilder(gwCmd)
                    .directory(rootfsDir)
                    .redirectErrorStream(true)

                gatewayProcess = processBuilder.start()

                var isListening = false
                val allLines = mutableListOf<String>()
                scope.launch {
                    gatewayProcess?.inputStream?.bufferedReader()?.use { reader ->
                        reader.lineSequence().forEach { line ->
                            Log.d(TAG, "[gateway] $line")
                            synchronized(allLines) { allLines.add(line) }
                            if (!isListening && line.contains("listening on ws://")) {
                                isListening = true
                                _state.value = GatewayState.Running
                                Log.i(TAG, "Gateway is listening on port $port")

                                // Start serve-ui after gateway is up
                                startServeUi()
                            }
                        }
                    }
                }

                delay(30000)
                if (!isListening && gatewayProcess?.isAlive == true) {
                    _state.value = GatewayState.Running
                    Log.i(TAG, "Gateway is running on port $port (fallback)")
                    startServeUi()
                    monitorProcess()
                } else if (gatewayProcess?.isAlive != true) {
                    val exitCode = gatewayProcess?.exitValue() ?: -1
                    val output = synchronized(allLines) { allLines.toList() }
                    val errorKeyLines = output.filter {
                        it.contains("Error") || it.contains("Cannot") || it.contains("FATAL") || it.contains("error:")
                    }
                    val summary = if (errorKeyLines.isNotEmpty()) {
                        errorKeyLines.joinToString("\n")
                    } else {
                        output.takeLast(15).joinToString("\n")
                    }
                    Log.e(TAG, "Gateway failed (code $exitCode): $summary")
                    _state.value = GatewayState.Error("Exit $exitCode:\n$summary")
                } else {
                    monitorProcess()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Start failed", e)
                _state.value = GatewayState.Error("Start failed: ${e.message}")
            }
        }
    }

    private fun startServeUi() {
        if (serveUiProcess?.isAlive == true) return

        val serveUiFile = File(rootfsDir, "root/lilclaw-ui/serve-ui.cjs")
        if (!serveUiFile.exists()) {
            Log.w(TAG, "serve-ui.cjs not found, Chat SPA will not be available")
            return
        }

        scope.launch {
            try {
                val cmd = buildProotCommand("node /root/lilclaw-ui/serve-ui.cjs")
                val processBuilder = ProcessBuilder(cmd)
                    .directory(rootfsDir)
                    .redirectErrorStream(true)

                serveUiProcess = processBuilder.start()
                Log.i(TAG, "serve-ui.cjs started on port 3001")

                // Log output
                scope.launch {
                    serveUiProcess?.inputStream?.bufferedReader()?.use { reader ->
                        reader.lineSequence().forEach { line ->
                            Log.d(TAG, "[serve-ui] $line")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start serve-ui: ${e.message}")
            }
        }
    }

    fun stop() {
        serveUiProcess?.let { process ->
            Log.i(TAG, "Stopping serve-ui")
            process.destroy()
            scope.launch {
                delay(3000)
                if (process.isAlive) process.destroyForcibly()
            }
        }
        serveUiProcess = null

        gatewayProcess?.let { process ->
            Log.i(TAG, "Stopping gateway")
            process.destroy()
            scope.launch {
                delay(5000)
                if (process.isAlive) process.destroyForcibly()
            }
        }
        gatewayProcess = null
        _state.value = GatewayState.Stopped
    }

    fun restart(port: Int = 3000, provider: String = "", apiKey: String = "", model: String = "") {
        stop()
        scope.launch {
            delay(1000)
            start(port, provider, apiKey, model)
        }
    }

    private fun writeGatewayConfig(port: Int, provider: String, apiKey: String, model: String) {
        val configDir = File(rootfsDir, "root/.openclaw")
        configDir.mkdirs()
        val configFile = File(configDir, "openclaw.json")

        val providerSlug = when (provider.lowercase()) {
            "openai" -> "openai"
            "anthropic" -> "anthropic"
            "deepseek" -> "deepseek"
            "aws bedrock" -> "amazon-bedrock"
            else -> provider.lowercase()
        }

        val defaultModel = when (providerSlug) {
            "openai" -> "gpt-4o"
            "anthropic" -> "claude-sonnet-4-20250514"
            "deepseek" -> "deepseek-chat"
            "amazon-bedrock" -> "anthropic.claude-sonnet-4-20250514-v1:0"
            else -> "gpt-4o"
        }

        val effectiveModel = model.ifBlank { defaultModel }

        val config = JSONObject().apply {
            put("agents", JSONObject().apply {
                put("defaults", JSONObject().apply {
                    put("model", JSONObject().apply {
                        put("primary", "$providerSlug/$effectiveModel")
                    })
                    put("workspace", "/root/.openclaw/workspace-dev")
                    put("skipBootstrap", true)
                })
                put("list", org.json.JSONArray().apply {
                    put(JSONObject().apply {
                        put("id", "dev")
                        put("default", true)
                        put("workspace", "/root/.openclaw/workspace-dev")
                    })
                })
            })
            put("gateway", JSONObject().apply {
                put("mode", "local")
                put("port", port)
                put("bind", "loopback")
                put("auth", JSONObject().apply {
                    put("token", "lilclaw-local")
                })
                put("controlUi", JSONObject().apply {
                    put("allowInsecureAuth", true)
                })
            })
            put("commands", JSONObject().apply {
                put("native", "auto")
                put("nativeSkills", "auto")
            })

            when (providerSlug) {
                "openai" -> {
                    put("env", JSONObject().apply { put("OPENAI_API_KEY", apiKey) })
                }
                "anthropic" -> {
                    put("env", JSONObject().apply { put("ANTHROPIC_API_KEY", apiKey) })
                }
                "deepseek" -> {
                    put("models", JSONObject().apply {
                        put("mode", "merge")
                        put("providers", JSONObject().apply {
                            put("deepseek", JSONObject().apply {
                                put("baseUrl", "https://api.deepseek.com/v1")
                                put("apiKey", apiKey)
                                put("api", "openai-completions")
                                put("models", org.json.JSONArray().apply {
                                    put(JSONObject().apply {
                                        put("id", "deepseek-chat")
                                        put("name", "DeepSeek Chat")
                                        put("reasoning", false)
                                        put("input", org.json.JSONArray().put("text"))
                                        put("contextWindow", 64000)
                                        put("maxTokens", 8192)
                                    })
                                    put(JSONObject().apply {
                                        put("id", "deepseek-reasoner")
                                        put("name", "DeepSeek Reasoner")
                                        put("reasoning", true)
                                        put("input", org.json.JSONArray().put("text"))
                                        put("contextWindow", 64000)
                                        put("maxTokens", 8192)
                                    })
                                })
                            })
                        })
                    })
                }
            }
        }

        configFile.writeText(config.toString(2))
        Log.i(TAG, "Wrote gateway config to ${configFile.absolutePath}")
    }

    private val libDir: File
        get() = File(context.filesDir, "lib")

    private fun setupLibDir() {
        libDir.mkdirs()
        val targets = listOf(
            File(libDir, "libtalloc.so.2"),
            File(nativeLibDir, "libtalloc.so.2"),
            File(rootfsDir, "usr/lib/libtalloc.so.2"),
        )
        for (target in targets) {
            try {
                if (!target.exists()) {
                    target.parentFile?.mkdirs()
                    tallocLib.copyTo(target, overwrite = true)
                    target.setReadable(true)
                    target.setExecutable(true, false)
                    Log.i(TAG, "Copied libtalloc.so.2 to ${target.absolutePath}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to copy libtalloc.so.2 to ${target.absolutePath}: ${e.message}")
            }
        }
    }

    private fun makeRootfsExecutable() {
        val binaries = listOf(
            "bin/busybox", "bin/sh",
            "usr/bin/node",
            "usr/local/bin/openclaw",
            "usr/lib/libgcc_s.so.1", "usr/lib/libstdc++.so.6",
            "lib/ld-musl-aarch64.so.1",
        )
        for (bin in binaries) {
            val f = File(rootfsDir, bin)
            if (f.exists()) f.setExecutable(true, false)
        }
        File(rootfsDir, "bin").listFiles()?.forEach { it.setExecutable(true, false) }
        File(rootfsDir, "usr/bin").listFiles()?.forEach { it.setExecutable(true, false) }
        File(rootfsDir, "usr/local/bin").listFiles()?.forEach { it.setExecutable(true, false) }
        File(rootfsDir, "usr/lib").listFiles()
            ?.filter { it.name.endsWith(".so") || it.name.contains(".so.") }
            ?.forEach { it.setReadable(true); it.setExecutable(true, false) }
        File(rootfsDir, "lib").listFiles()?.forEach { it.setExecutable(true, false) }
        Log.i(TAG, "Made rootfs binaries executable")
    }

    private fun monitorProcess() {
        scope.launch {
            gatewayProcess?.let { process ->
                try {
                    val exitCode = process.waitFor()
                    if (_state.value == GatewayState.Running) {
                        Log.w(TAG, "Gateway exited unexpectedly (code $exitCode)")
                        _state.value =
                            GatewayState.Error("Gateway exited unexpectedly (code $exitCode)")
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
