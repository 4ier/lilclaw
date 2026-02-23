package com.lilclaw.app.ui.webview

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.MediaStore
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream

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

    // Activity result launchers — must be registered in onCreate
    lateinit var cameraLauncher: ActivityResultLauncher<Intent>
    lateinit var galleryLauncher: ActivityResultLauncher<String>
    lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>

    private var pendingAction: String? = null // "camera" or "gallery" or "voice"

    fun registerLaunchers() {
        cameraLauncher = activity.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val bitmap = result.data?.extras?.get("data") as? Bitmap
                if (bitmap != null) {
                    sendImageToWeb(bitmap)
                }
            }
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
            speechRecognizer?.stopListening()
            isListening = false
        }
    }

    @JavascriptInterface
    fun haptic(type: String) {
        activity.runOnUiThread {
            val vibrator = activity.getSystemService(Vibrator::class.java) ?: return@runOnUiThread
            val duration = when (type) {
                "heavy" -> 30L
                "medium" -> 15L
                "selection" -> 5L
                else -> 10L
            }
            vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    // --- Internal methods ---

    private fun startVoiceInternal() {
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
    }

    private fun launchCamera() {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        cameraLauncher.launch(intent)
    }

    private fun sendImageToWeb(bitmap: Bitmap) {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
        val base64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        callJs("window.__lilclaw_onImagePicked?.('data:image/jpeg;base64,$base64')")
    }

    private fun sendUriToWeb(uri: Uri) {
        try {
            val inputStream = activity.contentResolver.openInputStream(uri) ?: return
            val bytes = inputStream.readBytes()
            inputStream.close()
            val mime = activity.contentResolver.getType(uri) ?: "application/octet-stream"
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            callJs("window.__lilclaw_onImagePicked?.('data:$mime;base64,$base64')")
        } catch (e: Exception) {
            Log.e("NativeBridge", "Failed to read URI", e)
            callJs("window.__lilclaw_onError?.('读取文件失败')")
        }
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
        speechRecognizer?.destroy()
        speechRecognizer = null
    }
}
