package com.lilclaw.app.service.gateway

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Builds and runs processes inside proot.
 * Manages gateway + serve-ui process lifecycle.
 */
class ProcessRunner(private val context: Context) {

    companion object {
        private const val TAG = "ProcessRunner"
    }

    private val rootfsDir: File get() = File(context.filesDir, "rootfs")
    private val nativeLibDir: File get() = File(context.applicationInfo.nativeLibraryDir)
    private val prootBin: File get() = File(nativeLibDir, "libproot.so")
    private val prootLoaderBin: File get() = File(nativeLibDir, "libproot_loader.so")
    private val libDir: File get() = File(context.filesDir, "lib")

    var gatewayProcess: Process? = null
        private set
    var serveUiProcess: Process? = null
        private set

    /**
     * Start the OpenClaw gateway process inside proot.
     * Returns the Process handle. Caller should pump stdout.
     */
    suspend fun startGateway(port: Int): Process = withContext(Dispatchers.IO) {
        prootBin.setExecutable(true)

        val cmd = buildProotCommand(
            "node /usr/local/bin/openclaw gateway run --allow-unconfigured --port $port --token lilclaw-local"
        )

        val process = ProcessBuilder(cmd)
            .directory(rootfsDir)
            .redirectErrorStream(true)
            .start()

        gatewayProcess = process
        process
    }

    /**
     * Start the serve-ui.cjs static file server inside proot.
     * Returns null if serve-ui.cjs doesn't exist.
     */
    fun startServeUi(): Process? {
        if (serveUiProcess?.isAlive == true) return serveUiProcess

        val serveUiFile = File(rootfsDir, "root/lilclaw-ui/serve-ui.cjs")
        if (!serveUiFile.exists()) {
            Log.w(TAG, "serve-ui.cjs not found, skipping Chat UI server")
            return null
        }

        val cmd = buildProotCommand("node /root/lilclaw-ui/serve-ui.cjs")
        val process = ProcessBuilder(cmd)
            .directory(rootfsDir)
            .redirectErrorStream(true)
            .start()

        serveUiProcess = process
        return process
    }

    /**
     * Stop all managed processes.
     */
    fun stopAll() {
        serveUiProcess?.destroy()
        serveUiProcess = null
        gatewayProcess?.destroy()
        gatewayProcess = null
    }

    /**
     * Force-kill all managed processes (after timeout).
     */
    fun forceStopAll() {
        serveUiProcess?.let { if (it.isAlive) it.destroyForcibly() }
        serveUiProcess = null
        gatewayProcess?.let { if (it.isAlive) it.destroyForcibly() }
        gatewayProcess = null
    }

    // ── Proot command builder ─────────────────────────────

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
}
