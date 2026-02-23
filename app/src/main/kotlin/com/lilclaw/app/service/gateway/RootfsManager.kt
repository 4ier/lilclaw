package com.lilclaw.app.service.gateway

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class LayerInfo(
    val name: String,
    val assetFile: String,       // filename in assets/rootfs/
    val fallbackUrl: String,     // network fallback
    val sizeBytes: Long,
    val displaySize: String,
    val version: String = assetFile.removeSuffix(".tar.gz").substringAfterLast("-"),
)

/**
 * Manages the rootfs filesystem: layer downloads, extraction, version tracking,
 * and binary permissions.
 */
class RootfsManager(private val context: Context) {

    companion object {
        private const val TAG = "RootfsManager"
        private const val RELEASES_BASE =
            "https://github.com/4ier/lilclaw/releases/download/layers-v3"
        private const val MANIFEST_URL = "$RELEASES_BASE/manifest.json"

        /** Fallback layer definitions used only when manifest can't be fetched. */
        val FALLBACK_LAYERS = listOf(
            LayerInfo(
                "base", "base-arm64-2.0.0.tar.gz",
                "$RELEASES_BASE/base-arm64-2.0.0.tar.gz",
                42_713_180L, "41 MB"
            ),
            LayerInfo(
                "openclaw", "openclaw-2026.2.17-bundled.tar.gz",
                "$RELEASES_BASE/openclaw-2026.2.17-bundled.tar.gz",
                43_335_483L, "42 MB"
            ),
            LayerInfo(
                "chatspa", "chatspa-0.7.0.tar.gz",
                "$RELEASES_BASE/chatspa-0.7.0.tar.gz",
                250_034L, "244 KB"
            ),
            LayerInfo(
                "config", "config-0.2.0.tar.gz",
                "$RELEASES_BASE/config-0.2.0.tar.gz",
                2_815L, "3 KB"
            ),
        )
    }

    val rootfsDir: File get() = File(context.filesDir, "rootfs")
    private val nativeLibDir: File get() = File(context.applicationInfo.nativeLibraryDir)
    private val tallocLib: File get() = File(nativeLibDir, "libtalloc.so")
    private val libDir: File get() = File(context.filesDir, "lib")
    private val layersJson: File get() = File(rootfsDir, ".layers.json")

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress

    /** Track which layers were actually installed so we can diff later. */
    private var lastInstalledLayers: List<LayerInfo> = FALLBACK_LAYERS

    val isReady: Boolean
        get() = layersJson.exists()
                && File(rootfsDir, "usr/bin/node").exists()
                && File(rootfsDir, "usr/local/bin/openclaw").exists()

    // ── Full extraction (first-time setup) ────────────────

    /**
     * Extract all layers from assets or network. Updates progress from 0 → 0.7.
     * @param onStateChange callback for UI state transitions
     * @param log callback for log lines
     */
    suspend fun extractAll(
        onStateChange: (String) -> Unit,  // "preparing" | "downloading" | "extracting"
        log: (String) -> Unit,
    ) {
        val layers = fetchManifestLayers(log) ?: FALLBACK_LAYERS
        lastInstalledLayers = layers
        val totalBytes = layers.sumOf { it.sizeBytes }
        var completedBytes = 0L
        rootfsDir.mkdirs()

        for (layer in layers) {
            val tarGzFile = File(context.cacheDir, "${layer.name}.tar.gz")
            val hasAsset = hasAssetFile("rootfs/${layer.assetFile}")

            if (hasAsset) {
                onStateChange("preparing")
                log("正在解包 ${layer.name}...")
                copyAssetToFile("rootfs/${layer.assetFile}", tarGzFile)
            } else {
                onStateChange("downloading")
                log("正在下载 ${layer.name} (${layer.displaySize})...")
                downloadFile(layer.fallbackUrl, tarGzFile) { layerProgress ->
                    val layerDownloaded = (layer.sizeBytes * layerProgress).toLong()
                    _progress.value = ((completedBytes + layerDownloaded).toFloat() / totalBytes * 0.7f)
                        .coerceAtMost(0.7f)
                }
            }

            onStateChange("extracting")
            log("正在安装 ${layer.name}...")
            extractTarGz(tarGzFile, rootfsDir)
            tarGzFile.delete()

            completedBytes += layer.sizeBytes
            _progress.value = (completedBytes.toFloat() / totalBytes * 0.7f).coerceAtMost(0.7f)
            log("${layer.name} ✓")
        }

        // Post-extraction setup
        log("配置环境...")
        _progress.value = 0.75f
        ConfigWriter.writeAndroidCompat(rootfsDir)
        ensureExecutable()
        writeLayersJson()

        if (!isReady) {
            throw RuntimeException("Rootfs extraction failed: key binaries not found")
        }
        log("环境就绪 ✓")
    }

    // ── Incremental updates (app restart) ─────────────────

    /**
     * Returns layers whose installed version doesn't match the latest manifest.
     */
    suspend fun getStaleLayers(log: (String) -> Unit): List<LayerInfo> {
        val latest = fetchManifestLayers(log) ?: FALLBACK_LAYERS
        if (!layersJson.exists()) return latest
        return try {
            val installed = JSONObject(layersJson.readText())
            latest.filter { layer ->
                val obj = installed.optJSONObject(layer.name)
                obj == null || obj.optString("version") != layer.version
            }
        } catch (e: Exception) {
            latest
        }
    }

    /**
     * Update specific layers (download/extract + merge version tracking).
     */
    suspend fun updateLayers(layers: List<LayerInfo>, log: (String) -> Unit) {
        for (layer in layers) {
            val tarGzFile = File(context.cacheDir, "${layer.name}.tar.gz")
            val hasAsset = hasAssetFile("rootfs/${layer.assetFile}")
            if (hasAsset) {
                copyAssetToFile("rootfs/${layer.assetFile}", tarGzFile)
            } else {
                log("正在下载 ${layer.name} (${layer.displaySize})...")
                downloadFile(layer.fallbackUrl, tarGzFile) { }
            }
            extractTarGz(tarGzFile, rootfsDir)
            tarGzFile.delete()
            log("${layer.name} 已更新 ✓")
        }
        mergeLayersJson(layers)
    }

    // ── Binary permissions ────────────────────────────────

    private var executableReady = false

    fun ensureExecutable() {
        if (executableReady) return
        setupLibDir()
        makeRootfsExecutable()
        executableReady = true
    }

    // ── Private: manifest fetching ────────────────────────

    private suspend fun fetchManifestLayers(log: (String) -> Unit): List<LayerInfo>? = withContext(Dispatchers.IO) {
        try {
            val url = URL(MANIFEST_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.instanceFollowRedirects = true
            try {
                if (conn.responseCode != 200) return@withContext null
                val text = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(text)
                val arr = json.getJSONArray("layers")
                val layers = mutableListOf<LayerInfo>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    layers.add(
                        LayerInfo(
                            name = obj.getString("name"),
                            assetFile = obj.getString("file"),
                            fallbackUrl = "$RELEASES_BASE/${obj.getString("file")}",
                            sizeBytes = obj.optLong("size", 0L),
                            displaySize = formatSize(obj.optLong("size", 0L)),
                            version = obj.getString("version"),
                        )
                    )
                }
                log("Manifest: ${layers.joinToString { "${it.name}@${it.version}" }}")
                layers
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch manifest: ${e.message}")
            null
        }
    }

    // ── Private: download / extract ───────────────────────

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

    private suspend fun extractTarGz(tarGzFile: File, targetDir: File) = withContext(Dispatchers.IO) {
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

    // ── Private: assets ───────────────────────────────────

    /**
     * Check if an asset file exists. Tries the exact path first,
     * then with ".bin" suffix (used to prevent AAPT from decompressing .tar.gz).
     */
    private fun hasAssetFile(path: String): Boolean {
        return tryOpenAsset(path) || tryOpenAsset("$path.bin")
    }

    private fun tryOpenAsset(path: String): Boolean {
        return try {
            context.assets.open(path).use { true }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Resolve the actual asset path, trying ".bin" suffix as fallback.
     */
    private fun resolveAssetPath(path: String): String {
        return if (tryOpenAsset(path)) path else "$path.bin"
    }

    private suspend fun copyAssetToFile(assetPath: String, target: File) = withContext(Dispatchers.IO) {
        val resolved = resolveAssetPath(assetPath)
        target.parentFile?.mkdirs()
        context.assets.open(resolved).use { input ->
            FileOutputStream(target).use { output ->
                input.copyTo(output, bufferSize = 65536)
            }
        }
    }

    // ── Private: version tracking ─────────────────────────

    private fun writeLayersJson() {
        val now = java.time.Instant.now().toString()
        val json = JSONObject()
        for (layer in lastInstalledLayers) {
            json.put(layer.name, JSONObject().apply {
                put("version", layer.version)
                put("installedAt", now)
            })
        }
        layersJson.writeText(json.toString(2))
    }

    private fun mergeLayersJson(updatedLayers: List<LayerInfo>) {
        val now = java.time.Instant.now().toString()
        val json = if (layersJson.exists()) {
            try { JSONObject(layersJson.readText()) } catch (e: Exception) { JSONObject() }
        } else {
            JSONObject()
        }
        for (layer in updatedLayers) {
            json.put(layer.name, JSONObject().apply {
                put("version", layer.version)
                put("installedAt", now)
            })
        }
        layersJson.writeText(json.toString(2))
    }

    // ── Private: permissions ──────────────────────────────

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

    // ── Private: util ─────────────────────────────────────

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1_048_576 -> "${bytes / 1_048_576} MB"
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }
}
