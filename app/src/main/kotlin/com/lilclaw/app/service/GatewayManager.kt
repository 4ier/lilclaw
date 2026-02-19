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
            "https://github.com/4ier/lilclaw/releases/download/v0.1.0/rootfs-arm64.tar.gz"
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

    fun start(port: Int = 3000) {
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

                // Ensure proot is executable
                prootBin.setExecutable(true)

                val env = mutableMapOf(
                    "HOME" to "/root",
                    "PATH" to "/root/.npm-global/bin:/usr/local/bin:/usr/bin:/bin",
                    "PROOT_TMP_DIR" to context.cacheDir.absolutePath,
                    "LD_LIBRARY_PATH" to nativeLibDir.absolutePath,
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
                    "PATH=/root/.npm-global/bin:/usr/local/bin:/usr/bin:/bin",
                    "node",
                    "/root/.npm-global/bin/openclaw",
                    "gateway", "start", "--foreground",
                    "--port", port.toString(),
                )

                Log.i(TAG, "Starting gateway: ${cmd.joinToString(" ")}")

                val processBuilder = ProcessBuilder(cmd)
                    .directory(rootfsDir)
                    .redirectErrorStream(true)

                // Set LD_LIBRARY_PATH so proot can find libtalloc
                processBuilder.environment().putAll(env)

                gatewayProcess = processBuilder.start()

                // Log gateway output in background
                scope.launch {
                    gatewayProcess?.inputStream?.bufferedReader()?.use { reader ->
                        reader.lineSequence().forEach { line ->
                            Log.d(TAG, "[gateway] $line")
                        }
                    }
                }

                // Wait for startup, then check if process is alive
                delay(3000)
                if (gatewayProcess?.isAlive == true) {
                    _state.value = GatewayState.Running
                    Log.i(TAG, "Gateway is running on port $port")
                    monitorProcess()
                } else {
                    val exitCode = gatewayProcess?.exitValue() ?: -1
                    _state.value = GatewayState.Error("Gateway exited with code $exitCode")
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
