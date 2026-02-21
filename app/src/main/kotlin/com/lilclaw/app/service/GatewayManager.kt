package com.lilclaw.app.service

import android.content.Context
import android.util.Log
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
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

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

data class LayerInfo(
    val name: String,
    val assetFile: String,       // filename in assets/rootfs/
    val fallbackUrl: String,     // network fallback
    val sizeBytes: Long,
    val displaySize: String,
    val version: String = assetFile.removeSuffix(".tar.gz").substringAfterLast("-"),
)

class GatewayManager(private val context: Context) {

    companion object {
        private const val TAG = "GatewayManager"
        private const val RELEASES_BASE =
            "https://github.com/4ier/lilclaw/releases/download/layers-v2"

        val LAYERS = listOf(
            LayerInfo(
                "base", "base-arm64-2.0.0.tar.gz",
                "$RELEASES_BASE/base-arm64-2.0.0.tar.gz",
                42_713_180L, "41 MB"
            ),
            LayerInfo(
                "openclaw", "openclaw-2026.2.17.tar.gz",
                "$RELEASES_BASE/openclaw-2026.2.17.tar.gz",
                33_380_716L, "32 MB"
            ),
            LayerInfo(
                "chatspa", "chatspa-0.3.3.tar.gz",
                "$RELEASES_BASE/chatspa-0.3.3.tar.gz",
                237_000L, "237 KB"
            ),
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<GatewayState>(GatewayState.Idle)
    val state: StateFlow<GatewayState> = _state

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress

    // Boot log lines for the UI console
    private val _logLines = MutableSharedFlow<String>(replay = 50, extraBufferCapacity = 200)
    val logLines: SharedFlow<String> = _logLines

    private var gatewayProcess: Process? = null
    private var serveUiProcess: Process? = null

    private val rootfsDir: File get() = File(context.filesDir, "rootfs")
    private val nativeLibDir: File get() = File(context.applicationInfo.nativeLibraryDir)
    private val prootBin: File get() = File(nativeLibDir, "libproot.so")
    private val tallocLib: File get() = File(nativeLibDir, "libtalloc.so")
    private val prootLoaderBin: File get() = File(nativeLibDir, "libproot_loader.so")
    private val layersJson: File get() = File(rootfsDir, ".layers.json")
    private val libDir: File get() = File(context.filesDir, "lib")

    val isRootfsReady: Boolean
        get() = layersJson.exists()
                && File(rootfsDir, "usr/bin/node").exists()
                && File(rootfsDir, "usr/local/bin/openclaw").exists()

    /** Returns layers whose installed version doesn't match LAYERS definition. */
    private fun getStaleLayers(): List<LayerInfo> {
        if (!layersJson.exists()) return LAYERS
        return try {
            val installed = JSONObject(layersJson.readText())
            LAYERS.filter { layer ->
                val obj = installed.optJSONObject(layer.name)
                obj == null || obj.optString("version") != layer.version
            }
        } catch (e: Exception) {
            LAYERS
        }
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        _logLines.tryEmit(msg)
    }

    /**
     * Full bootstrap: extract rootfs → write config → start gateway → poll UI ready.
     * Drives state from Preparing all the way to Running.
     * Call this once from SetupViewModel after user configures provider.
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
                // Start foreground service immediately to survive backgrounding
                GatewayService.start(context)

                // Phase 1: Extract rootfs (from assets or network)
                if (!isRootfsReady) {
                    _state.value = GatewayState.Preparing
                    _progress.value = 0f
                    GatewayService.updateStatus(context, "Setting up...")
                    extractRootfsInternal()
                }

                // Phase 2: Write config
                log("Writing configuration...")
                writeGatewayConfig(port, provider, apiKey, model)
                _progress.value = 0.8f

                // Phase 3: Start gateway
                _state.value = GatewayState.Starting
                GatewayService.updateStatus(context, "Starting gateway...")
                log("Starting gateway...")
                startGatewayProcess(port)
                _progress.value = 0.85f

                // Phase 4: Wait for gateway to be listening
                log("Waiting for gateway...")
                waitForPort(port, timeoutMs = 60_000)
                _progress.value = 0.9f

                // Phase 5: Start serve-ui
                _state.value = GatewayState.WaitingForUi
                log("Starting Chat UI...")
                startServeUiProcess()

                // Phase 6: Poll serve-ui readiness
                log("Waiting for Chat UI...")
                waitForPort(3001, timeoutMs = 30_000)
                _progress.value = 1f

                log("Ready")
                _state.value = GatewayState.Running
                GatewayService.updateStatus(context, "Running")
                onReady()

            } catch (e: Exception) {
                Log.e(TAG, "Bootstrap failed", e)
                log("Error: ${e.message}")
                _state.value = GatewayState.Error(e.message ?: "Unknown error")
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

                if (!isRootfsReady) {
                    _state.value = GatewayState.Error("Rootfs not ready")
                    return@launch
                }

                // Check for layer updates (e.g. chatspa version bump in new APK)
                val stale = getStaleLayers()
                if (stale.isNotEmpty()) {
                    log("Updating ${stale.joinToString { it.name }}...")
                    for (layer in stale) {
                        val tarGzFile = File(context.cacheDir, "${layer.name}.tar.gz")
                        val hasAsset = hasAssetFile("rootfs/${layer.assetFile}")
                        if (hasAsset) {
                            copyAssetToFile("rootfs/${layer.assetFile}", tarGzFile)
                        } else {
                            log("Downloading ${layer.name} (${layer.displaySize})...")
                            downloadFile(layer.fallbackUrl, tarGzFile) { }
                        }
                        extractTarGz(tarGzFile, rootfsDir)
                        tarGzFile.delete()
                        log("${layer.name} updated ✓")
                    }
                    writeLayersJson()
                }

                if (provider.isNotEmpty()) {
                    writeGatewayConfig(port, provider, apiKey, model)
                }

                _state.value = GatewayState.Starting
                _progress.value = 0.5f
                GatewayService.updateStatus(context, "Starting gateway...")
                log("Starting gateway...")
                setupLibDir()
                makeRootfsExecutable()
                startGatewayProcess(port)

                log("Waiting for gateway...")
                waitForPort(port, timeoutMs = 60_000)
                _progress.value = 0.8f

                _state.value = GatewayState.WaitingForUi
                log("Starting Chat UI...")
                startServeUiProcess()
                log("Waiting for Chat UI...")
                waitForPort(3001, timeoutMs = 30_000)

                _progress.value = 1f
                log("Ready")
                _state.value = GatewayState.Running
                GatewayService.updateStatus(context, "Running")
                onReady()
            } catch (e: Exception) {
                Log.e(TAG, "Quick start failed", e)
                log("Error: ${e.message}")
                _state.value = GatewayState.Error(e.message ?: "Unknown error")
            }
        }
    }

    // ── Rootfs extraction ─────────────────────────────────

    private suspend fun extractRootfsInternal() {
        val totalBytes = LAYERS.sumOf { it.sizeBytes }
        var completedBytes = 0L
        rootfsDir.mkdirs()

        for ((idx, layer) in LAYERS.withIndex()) {
            val tarGzFile = File(context.cacheDir, "${layer.name}.tar.gz")

            // Try bundled asset first, fall back to network
            val hasAsset = hasAssetFile("rootfs/${layer.assetFile}")
            if (hasAsset) {
                _state.value = GatewayState.Preparing
                log("Unpacking ${layer.name}...")
                copyAssetToFile("rootfs/${layer.assetFile}", tarGzFile)
            } else {
                _state.value = GatewayState.Downloading
                log("Downloading ${layer.name} (${layer.displaySize})...")
                downloadFile(layer.fallbackUrl, tarGzFile) { layerProgress ->
                    val layerDownloaded = (layer.sizeBytes * layerProgress).toLong()
                    _progress.value = ((completedBytes + layerDownloaded).toFloat() / totalBytes * 0.7f)
                        .coerceAtMost(0.7f)
                }
            }

            // Extract (can overlap with next download in the future)
            _state.value = GatewayState.Extracting
            log("Installing ${layer.name}...")
            extractTarGz(tarGzFile, rootfsDir)
            tarGzFile.delete()

            completedBytes += layer.sizeBytes
            _progress.value = (completedBytes.toFloat() / totalBytes * 0.7f).coerceAtMost(0.7f)
            log("${layer.name} ✓")
        }

        // Post-extraction setup
        log("Configuring environment...")
        _progress.value = 0.75f
        writeAndroidCompat()
        setupLibDir()
        makeRootfsExecutable()
        writeLayersJson()

        if (!isRootfsReady) {
            throw RuntimeException("Rootfs extraction failed: key binaries not found")
        }
        log("Environment ready ✓")
    }

    private fun hasAssetFile(path: String): Boolean {
        return try {
            context.assets.open(path).use { true }
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun copyAssetToFile(assetPath: String, target: File) = withContext(Dispatchers.IO) {
        target.parentFile?.mkdirs()
        context.assets.open(assetPath).use { input ->
            FileOutputStream(target).use { output ->
                input.copyTo(output, bufferSize = 65536)
            }
        }
    }

    // ── Gateway process management ────────────────────────

    private suspend fun startGatewayProcess(port: Int) = withContext(Dispatchers.IO) {
        setupLibDir()
        makeRootfsExecutable()
        prootBin.setExecutable(true)

        val gwCmd = buildProotCommand(
            "node /usr/local/bin/openclaw gateway run --allow-unconfigured --port $port --token lilclaw-local"
        )

        val processBuilder = ProcessBuilder(gwCmd)
            .directory(rootfsDir)
            .redirectErrorStream(true)

        gatewayProcess = processBuilder.start()

        // Pump gateway stdout to log
        scope.launch {
            gatewayProcess?.inputStream?.bufferedReader()?.use { reader ->
                reader.lineSequence().forEach { line ->
                    Log.d(TAG, "[gw] $line")
                    // Filter noisy lines, only emit meaningful ones
                    if (line.contains("listening") || line.contains("error", ignoreCase = true)
                        || line.contains("ready") || line.contains("started")
                        || line.contains("config") || line.contains("agent")
                    ) {
                        _logLines.tryEmit(line.take(120))
                    }
                }
            }
        }
    }

    private fun startServeUiProcess() {
        if (serveUiProcess?.isAlive == true) return

        val serveUiFile = File(rootfsDir, "root/lilclaw-ui/serve-ui.cjs")
        if (!serveUiFile.exists()) {
            log("serve-ui.cjs not found, skipping Chat UI server")
            return
        }

        val cmd = buildProotCommand("node /root/lilclaw-ui/serve-ui.cjs")
        val processBuilder = ProcessBuilder(cmd)
            .directory(rootfsDir)
            .redirectErrorStream(true)

        serveUiProcess = processBuilder.start()
        log("Chat UI server started")

        scope.launch {
            serveUiProcess?.inputStream?.bufferedReader()?.use { reader ->
                reader.lineSequence().forEach { line ->
                    Log.d(TAG, "[ui] $line")
                }
            }
        }
    }

    /**
     * Poll a local port until it responds with HTTP 200 (or any non-error).
     */
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

    // ── Helpers ────────────────────────────────────────────

    private fun buildProotCommand(cmd: String): List<String> {
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

    private suspend fun downloadFile(
        urlStr: String,
        target: File,
        onProgress: (Float) -> Unit,
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
            Log.i(TAG, "Downloaded ${downloadedBytes / 1024 / 1024}MB")
        } finally {
            connection?.disconnect()
        }
    }

    private suspend fun extractTarGz(
        tarGzFile: File,
        targetDir: File,
    ) = withContext(Dispatchers.IO) {
        targetDir.mkdirs()
        val process = ProcessBuilder(
            "tar", "xzf", tarGzFile.absolutePath, "-C", targetDir.absolutePath
        ).redirectErrorStream(true).start()

        val exitCode = process.waitFor()
        if (exitCode != 0) {
            val error = process.inputStream.bufferedReader().readText()
            throw RuntimeException("tar extraction failed (code $exitCode): $error")
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
    }

    private fun writeLayersJson() {
        val now = java.time.Instant.now().toString()
        val json = JSONObject()
        for (layer in LAYERS) {
            json.put(layer.name, JSONObject().apply {
                put("version", layer.version)
                put("installedAt", now)
            })
        }
        layersJson.writeText(json.toString(2))
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
        log("Configuration written ✓")
    }

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
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to copy libtalloc to ${target.absolutePath}: ${e.message}")
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
    }
}
