package com.lilclaw.app.ui.webview

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.content.Intent
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Handles native bridge calls from the SPA WebView.
 * Manages: voice input, camera, gallery, haptic feedback.
 */
class NativeBridge(
    private val activity: ComponentActivity,
    private var webViewRef: () -> WebView?,
) {
    fun setWebView(ref: () -> WebView?) {
        webViewRef = ref
    }
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var voiceTimeoutRunnable: Runnable? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    // Camera: TakePicture writes full-size image to this URI
    private var cameraImageUri: Uri? = null

    // Pending image result: cached until JS callback is registered
    private var pendingImageResult: String? = null

    // Activity result launchers — must be registered in onCreate
    lateinit var cameraLauncher: ActivityResultLauncher<Uri>
    lateinit var galleryLauncher: ActivityResultLauncher<String>
    lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>

    private var pendingAction: String? = null // "camera" or "voice"

    fun registerLaunchers() {
        cameraLauncher = activity.registerForActivityResult(
            ActivityResultContracts.TakePicture()
        ) { success ->
            if (success) {
                val uri = cameraImageUri
                if (uri != null) {
                    sendUriToWeb(uri)
                }
            }
            // Clean up temp file after a delay (give time to read)
            cameraImageUri = null
        }

        galleryLauncher = activity.registerForActivityResult(
            ActivityResultContracts.GetContent()
        ) { uri: Uri? ->
            if (uri != null) {
                sendUriToWeb(uri)
            }
        }

        permissionLauncher = activity.registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val allGranted = permissions.values.all { it }
            if (allGranted) {
                when (pendingAction) {
                    "camera" -> launchCamera()
                    "voice" -> startVoiceInternal()
                }
            } else {
                callJs("window.__lilclaw_onError?.('权限被拒绝')")
            }
            pendingAction = null
        }
    }

    var onSettingsClick: (() -> Unit)? = null

    /** JavaScript interface exposed as window.LilClaw */
    @JavascriptInterface
    fun openSettings() {
        activity.runOnUiThread {
            onSettingsClick?.invoke()
        }
    }

    @JavascriptInterface
    fun isSystemDarkMode(): Boolean {
        return (activity.resources.configuration.uiMode
            and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    /** Called by SPA after page load to check if an image was picked while JS context was dead */
    @JavascriptInterface
    fun getPendingImage(): String {
        val result = pendingImageResult ?: ""
        pendingImageResult = null
        return result
    }

    @JavascriptInterface
    fun takePhoto() {
        activity.runOnUiThread {
            if (hasCameraPermission()) {
                launchCamera()
            } else {
                pendingAction = "camera"
                permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
            }
        }
    }

    @JavascriptInterface
    fun pickImage() {
        activity.runOnUiThread {
            galleryLauncher.launch("image/*")
        }
    }

    @JavascriptInterface
    fun pickFile() {
        activity.runOnUiThread {
            galleryLauncher.launch("*/*")
        }
    }

    @JavascriptInterface
    fun startVoice() {
        Log.d("NativeBridge", "startVoice called, hasAudioPermission=${hasAudioPermission()}")
        activity.runOnUiThread {
            if (hasAudioPermission()) {
                startVoiceInternal()
            } else {
                pendingAction = "voice"
                permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            }
        }
    }

    @JavascriptInterface
    fun stopVoice() {
        activity.runOnUiThread {
            voiceTimeoutRunnable?.let { handler.removeCallbacks(it) }
            speechRecognizer?.stopListening()
            isListening = false
        }
    }

    @JavascriptInterface
    fun haptic(type: String) {
        activity.runOnUiThread {
            try {
                val vibrator = activity.getSystemService(Vibrator::class.java) ?: return@runOnUiThread
                val duration = when (type) {
                    "heavy" -> 30L
                    "medium" -> 15L
                    "selection" -> 5L
                    else -> 10L
                }
                vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
            } catch (e: SecurityException) {
                Log.w("NativeBridge", "Vibrate permission denied, skipping haptic feedback")
            }
        }
    }

    // --- Internal methods ---

    private fun startVoiceInternal() {
        Log.d("NativeBridge", "startVoiceInternal, recognition available=${SpeechRecognizer.isRecognitionAvailable(activity)}")
        if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
            callJs("window.__lilclaw_onError?.('语音识别不可用')")
            return
        }

        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(activity).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    isListening = true
                    callJs("window.__lilclaw_onVoiceState?.('listening')")
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    isListening = false
                    callJs("window.__lilclaw_onVoiceState?.('processing')")
                }
                override fun onError(error: Int) {
                    voiceTimeoutRunnable?.let { handler.removeCallbacks(it) }
                    // Skip ERROR_CLIENT (5) if we already stopped (e.g. from our timeout cancel)
                    if (!isListening && error == SpeechRecognizer.ERROR_CLIENT) return
                    isListening = false
                    val msg = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "没有听清，请再试一次"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没有检测到语音"
                        SpeechRecognizer.ERROR_NETWORK,
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络错误"
                        else -> "语音识别出错 ($error)"
                    }
                    callJs("window.__lilclaw_onVoiceError?.('$msg')")
                }
                override fun onResults(results: Bundle?) {
                    voiceTimeoutRunnable?.let { handler.removeCallbacks(it) }
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull() ?: ""
                    if (text.isNotEmpty()) {
                        val escaped = text.replace("'", "\\'").replace("\n", "\\n")
                        callJs("window.__lilclaw_onVoiceText?.('$escaped')")
                    }
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull() ?: ""
                    if (text.isNotEmpty()) {
                        val escaped = text.replace("'", "\\'").replace("\n", "\\n")
                        callJs("window.__lilclaw_onVoicePartial?.('$escaped')")
                    }
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        speechRecognizer?.startListening(intent)

        // Safety timeout: force-stop after 20s if no result/error from SpeechRecognizer
        voiceTimeoutRunnable?.let { handler.removeCallbacks(it) }
        voiceTimeoutRunnable = Runnable {
            Log.d("NativeBridge", "Voice timeout fired, isListening=$isListening")
            if (isListening) {
                speechRecognizer?.cancel()
                isListening = false
                callJs("window.__lilclaw_onVoiceError?.('录音超时，请再试一次')")
            }
        }.also {
            Log.d("NativeBridge", "Posting voice timeout in 20s")
            handler.postDelayed(it, 20_000)
        }
    }

    private fun launchCamera() {
        try {
            // Create temp file for full-size camera image
            val imageFile = File.createTempFile("lilclaw_photo_", ".jpg", activity.cacheDir)
            cameraImageUri = FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.fileprovider",
                imageFile
            )
            cameraLauncher.launch(cameraImageUri!!)
        } catch (e: Exception) {
            Log.e("NativeBridge", "Failed to launch camera", e)
            callJs("window.__lilclaw_onError?.('无法打开相机')")
        }
    }

    private fun sendUriToWeb(uri: Uri) {
        try {
            val inputStream = activity.contentResolver.openInputStream(uri) ?: return
            val bytes = inputStream.readBytes()
            inputStream.close()

            val mime = activity.contentResolver.getType(uri) ?: "image/jpeg"
            val finalBytes = if (mime.startsWith("image/") && bytes.size > 500_000) {
                downscaleImage(bytes, maxDimension = 1600)
            } else {
                bytes
            }

            val base64 = Base64.encodeToString(finalBytes, Base64.NO_WRAP)
            val finalMime = if (finalBytes !== bytes) "image/jpeg" else mime
            val dataUrl = "data:$finalMime;base64,$base64"

            // Try to deliver via JS callback; if WebView is not ready, cache it
            val webView = webViewRef()
            if (webView != null) {
                callJs("window.__lilclaw_onImagePicked?.('$dataUrl') ?? window.__lilclaw_pendingCheck?.()")
                // Also cache in case the JS callback wasn't registered yet
                pendingImageResult = dataUrl
            } else {
                pendingImageResult = dataUrl
            }
        } catch (e: Exception) {
            Log.e("NativeBridge", "Failed to read URI", e)
            callJs("window.__lilclaw_onError?.('读取文件失败')")
        }
    }

    private fun downscaleImage(bytes: ByteArray, maxDimension: Int): ByteArray {
        // Decode bounds only
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)

        val w = options.outWidth
        val h = options.outHeight
        if (w <= maxDimension && h <= maxDimension) return bytes

        // Calculate sample size
        var sampleSize = 1
        while (w / sampleSize > maxDimension || h / sampleSize > maxDimension) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions) ?: return bytes

        val stream = ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, stream)
        bitmap.recycle()
        return stream.toByteArray()
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun callJs(script: String) {
        activity.runOnUiThread {
            webViewRef()?.evaluateJavascript(script, null)
        }
    }

    fun destroy() {
        voiceTimeoutRunnable?.let { handler.removeCallbacks(it) }
        speechRecognizer?.destroy()
        speechRecognizer = null
    }
}
