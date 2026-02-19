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
import java.util.zip.GZIPInputStream

sealed class GatewayState {
    data object Stopped : GatewayState()
    data object Downloading : GatewayState()
    data object Extracting : GatewayState()
    data object Starting : GatewayState()
    data object Running : GatewayState()
    data class Error(val message: String) : GatewayState()
}

class GatewayManager(private val context: Context) {

    companion object {
        private const val TAG = "GatewayManager"
        private const val ROOTFS_URL =
            "https://github.com/4ier/lilclaw/releases/download/v0.2.0/rootfs-v0.2.0.tar.gz"
        private const val ROOTFS_SIZE_BYTES = 57_000_000L // ~57MB for progress calculation
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<GatewayState>(GatewayState.Stopped)
    val state: StateFlow<GatewayState> = _state

    private val _extractionProgress = MutableStateFlow(0f)
    val extractionProgress: StateFlow<Float> = _extractionProgress

    private var gatewayProcess: Process? = null

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

    val isRootfsExtracted: Boolean
        get() = rootfsDir.exists() && File(rootfsDir, "usr/bin/node").exists()

    fun extractRootfs(onComplete: () -> Unit) {
        scope.launch {
            try {
                if (isRootfsExtracted) {
                    Log.i(TAG, "Rootfs already extracted, skipping")
                    onComplete()
                    return@launch
                }

                // Phase 1: Download
                _state.value = GatewayState.Downloading
                _extractionProgress.value = 0f
                val tarGzFile = File(context.cacheDir, "rootfs-arm64.tar.gz")

                downloadFile(ROOTFS_URL, tarGzFile) { progress ->
                    _extractionProgress.value = progress * 0.5f // 0-50% for download
                }

                // Phase 2: Extract
                _state.value = GatewayState.Extracting
                _extractionProgress.value = 0.5f

                extractTarGz(tarGzFile, rootfsDir) { progress ->
                    _extractionProgress.value = 0.5f + progress * 0.5f // 50-100% for extraction
                }

                // Cleanup downloaded file
                tarGzFile.delete()
                _extractionProgress.value = 1f

                // Verify
                if (!isRootfsExtracted) {
                    throw RuntimeException("Rootfs extraction failed: node binary not found")
                }

                Log.i(TAG, "Rootfs extracted successfully: ${rootfsDir.absolutePath}")
                _state.value = GatewayState.Stopped
                onComplete()
            } catch (e: Exception) {
                Log.e(TAG, "Rootfs extraction failed", e)
                _state.value = GatewayState.Error("Setup failed: ${e.message}")
                // Clean up partial extraction
                rootfsDir.deleteRecursively()
            }
        }
    }

    private suspend fun downloadFile(
        urlStr: String,
        target: File,
        onProgress: (Float) -> Unit
    ) = withContext(Dispatchers.IO) {
        Log.i(TAG, "Downloading $urlStr")
        var connection: HttpURLConnection? = null
        try {
            // Follow redirects (GitHub releases redirect to S3)
            var url = URL(urlStr)
            var redirectCount = 0
            while (redirectCount < 5) {
                connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 30_000
                connection.readTimeout = 60_000
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

            val totalBytes = connection?.contentLengthLong ?: ROOTFS_SIZE_BYTES
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
                            onProgress(
                                if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceAtMost(1f)
                                else 0f
                            )
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
        onProgress: (Float) -> Unit
    ) = withContext(Dispatchers.IO) {
        Log.i(TAG, "Extracting ${tarGzFile.name} to ${targetDir.absolutePath}")
        targetDir.mkdirs()

        // Use busybox tar from Alpine rootfs if available, otherwise use process
        // Since we're extracting the rootfs itself, use system tar via process
        val process = ProcessBuilder(
            "tar", "xzf", tarGzFile.absolutePath, "-C", targetDir.absolutePath
        ).redirectErrorStream(true).start()

        // Monitor extraction progress by checking directory size periodically
        val monitorJob = scope.launch {
            val totalExpected = 271_000_000L // ~271MB uncompressed
            while (process.isAlive) {
                delay(500)
                val currentSize = targetDir.walkTopDown().sumOf { it.length() }
                onProgress((currentSize.toFloat() / totalExpected).coerceAtMost(0.99f))
            }
        }

        val exitCode = process.waitFor()
        monitorJob.cancel()
        onProgress(1f)

        if (exitCode != 0) {
            val error = process.inputStream.bufferedReader().readText()
            throw RuntimeException("tar extraction failed (code $exitCode): $error")
        }

        Log.i(TAG, "Extraction complete")
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

                // Write OpenClaw config.yaml into rootfs (skip if already exists and no new values)
                if (provider.isNotEmpty() && apiKey.isNotEmpty()) {
                    writeGatewayConfig(port, provider, apiKey, model)
                } else {
                    val existingConfig = File(rootfsDir, "root/.config/openclaw/config.yaml")
                    if (!existingConfig.exists()) {
                        Log.w(TAG, "No config.yaml and no provider/apiKey — gateway may fail")
                    }
                }

                // Setup lib directory with correctly-named libtalloc
                setupLibDir()

                // Make rootfs binaries executable (Android extracts without +x)
                makeRootfsExecutable()

                // Ensure proot is executable
                prootBin.setExecutable(true)

                val env = mutableMapOf(
                    "HOME" to "/root",
                    "PATH" to "/usr/local/bin:/usr/bin:/bin:/usr/local/sbin:/usr/sbin:/sbin",
                    "PROOT_TMP_DIR" to context.cacheDir.absolutePath,
                    "PROOT_LOADER" to prootLoaderBin.absolutePath,
                    "LD_LIBRARY_PATH" to "${libDir.absolutePath}:${nativeLibDir.absolutePath}",
                )

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
                    "PATH=/usr/local/bin:/usr/bin:/bin:/usr/local/sbin:/usr/sbin:/sbin",
                    "node",
                    "/usr/local/bin/openclaw",
                    "gateway", "run", "--dev",
                    "--port", port.toString(),
                    "--token", "lilclaw-local",
                )

                Log.i(TAG, "Starting gateway: ${cmd.joinToString(" ")}")

                val processBuilder = ProcessBuilder(cmd)
                    .directory(rootfsDir)
                    .redirectErrorStream(true)

                // Set LD_LIBRARY_PATH so proot can find libtalloc
                processBuilder.environment().putAll(env)

                gatewayProcess = processBuilder.start()

                // Log gateway output in background and detect when it's listening
                var isListening = false
                scope.launch {
                    gatewayProcess?.inputStream?.bufferedReader()?.use { reader ->
                        reader.lineSequence().forEach { line ->
                            Log.d(TAG, "[gateway] $line")
                            if (!isListening && line.contains("listening on ws://")) {
                                isListening = true
                                _state.value = GatewayState.Running
                                Log.i(TAG, "Gateway is listening on port $port")
                            }
                        }
                    }
                }

                // Wait for startup, then check if process is alive
                delay(30000)
                if (!isListening && gatewayProcess?.isAlive == true) {
                    // Process alive but not listening yet — still set Running as fallback
                    _state.value = GatewayState.Running
                    Log.i(TAG, "Gateway is running on port $port (fallback)")
                    monitorProcess()
                } else if (gatewayProcess?.isAlive != true) {
                    val exitCode = gatewayProcess?.exitValue() ?: -1
                    _state.value = GatewayState.Error("Gateway exited with code $exitCode")
                } else {
                    // Already listening
                    monitorProcess()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Start failed", e)
                _state.value = GatewayState.Error("Start failed: ${e.message}")
            }
        }
    }

    fun stop() {
        gatewayProcess?.let { process ->
            Log.i(TAG, "Stopping gateway")
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

    fun restart(port: Int = 3000, provider: String = "", apiKey: String = "", model: String = "") {
        stop()
        scope.launch {
            delay(1000)
            start(port, provider, apiKey, model)
        }
    }

    private fun writeGatewayConfig(port: Int, provider: String, apiKey: String, model: String) {
        val configDir = File(rootfsDir, "root/.config/openclaw")
        configDir.mkdirs()
        val configFile = File(configDir, "config.yaml")

        // Map UI provider names to OpenClaw provider format
        val providerSlug = when (provider.lowercase()) {
            "openai" -> "openai"
            "anthropic" -> "anthropic"
            "aws bedrock" -> "amazon-bedrock"
            else -> provider.lowercase()
        }

        val defaultModel = when (providerSlug) {
            "openai" -> "gpt-4o"
            "anthropic" -> "claude-sonnet-4-20250514"
            "amazon-bedrock" -> "anthropic.claude-sonnet-4-20250514-v1:0"
            else -> "gpt-4o"
        }

        val effectiveModel = model.ifBlank { defaultModel }

        val config = buildString {
            appendLine("# LilClaw auto-generated config")
            appendLine("agents:")
            appendLine("  defaults:")
            appendLine("    model: \"$providerSlug/$effectiveModel\"")
            appendLine()
            appendLine("gateway:")
            appendLine("  port: $port")
            appendLine("  auth:")
            appendLine("    token: \"lilclaw-local\"")
            appendLine()
            // API key as environment-style config
            when (providerSlug) {
                "openai" -> {
                    appendLine("providers:")
                    appendLine("  openai:")
                    appendLine("    apiKey: \"$apiKey\"")
                }
                "anthropic" -> {
                    appendLine("providers:")
                    appendLine("  anthropic:")
                    appendLine("    apiKey: \"$apiKey\"")
                }
                "amazon-bedrock" -> {
                    // AWS uses env vars, write to profile
                    appendLine("# AWS credentials should be set via environment")
                }
            }
        }

        configFile.writeText(config)
        Log.i(TAG, "Wrote gateway config to ${configFile.absolutePath}")
    }

    private val libDir: File
        get() = File(context.filesDir, "lib")

    private fun setupLibDir() {
        // Android extracts native libs without version suffixes,
        // but proot needs libtalloc.so.2
        libDir.mkdirs()
        val target = File(libDir, "libtalloc.so.2")
        if (!target.exists()) {
            tallocLib.copyTo(target, overwrite = true)
            target.setReadable(true)
            Log.i(TAG, "Copied libtalloc.so.2 to ${target.absolutePath}")
        }
    }

    private fun makeRootfsExecutable() {
        // tar extraction on Android may strip execute bits.
        // Restore them for key binaries.
        val binaries = listOf(
            "bin/busybox", "bin/sh",
            "usr/bin/node",
            "usr/lib/libgcc_s.so.1", "usr/lib/libstdc++.so.6",
            "lib/ld-musl-aarch64.so.1",
        )
        for (bin in binaries) {
            val f = File(rootfsDir, bin)
            if (f.exists()) f.setExecutable(true, false)
        }
        // Also chmod all of /usr/bin and /bin
        File(rootfsDir, "bin").listFiles()?.forEach { it.setExecutable(true, false) }
        File(rootfsDir, "usr/bin").listFiles()?.forEach { it.setExecutable(true, false) }
        File(rootfsDir, "usr/lib").listFiles()?.filter { it.name.endsWith(".so") || it.name.contains(".so.") }
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
