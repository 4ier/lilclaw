package com.lilclaw.app.ui.webview

import android.annotation.SuppressLint
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import com.lilclaw.app.MainActivity

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewScreen(
    onSettingsClick: () -> Unit,
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    val context = LocalContext.current
    val mainActivity = context as? MainActivity

    // Wire up webView reference and settings callback to bridge
    DisposableEffect(webView) {
        if (mainActivity != null) {
            mainActivity.nativeBridge.setWebView { webView }
            mainActivity.nativeBridge.onSettingsClick = onSettingsClick
        }
        onDispose {
            mainActivity?.nativeBridge?.setWebView { null }
            mainActivity?.nativeBridge?.onSettingsClick = null
        }
    }

    BackHandler(enabled = webView?.canGoBack() == true) {
        webView?.goBack()
    }

    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    val systemBarsInsets = WindowInsets.systemBars

    val imeBottomPx = imeInsets.getBottom(density)
    val systemTopDp = with(density) { systemBarsInsets.getTop(density).toDp() }
    val systemBottomPx = systemBarsInsets.getBottom(density)

    val kbHeightCssPx = with(density) {
        val extra = (imeBottomPx - systemBottomPx).coerceAtLeast(0)
        (extra / density.density).toInt()
    }

    LaunchedEffect(kbHeightCssPx) {
        webView?.evaluateJavascript(
            "document.documentElement.style.setProperty('--kb-height','${kbHeightCssPx}px')",
            null
        )
    }

    val systemBottomDp = with(density) { systemBottomPx.toDp() }

    AndroidView(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = systemTopDp, bottom = systemBottomDp)
            .consumeWindowInsets(WindowInsets.systemBars)
            .consumeWindowInsets(WindowInsets.ime),
        factory = { ctx ->
            WebView(ctx).apply {
                WebView.setWebContentsDebuggingEnabled(true)

                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                overScrollMode = WebView.OVER_SCROLL_NEVER

                val isDark = (ctx.resources.configuration.uiMode
                    and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
                setBackgroundColor(if (isDark) android.graphics.Color.parseColor("#1a1410") else android.graphics.Color.WHITE)

                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                @Suppress("DEPRECATION")
                settings.databaseEnabled = true
                settings.allowFileAccess = false
                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

                // Add the native bridge — use the activity's bridge for camera/voice/haptic,
                // plus a settings wrapper
                val bridge = mainActivity?.nativeBridge
                if (bridge != null) {
                    addJavascriptInterface(bridge, "LilClaw")
                } else {
                    // Fallback: settings-only bridge
                    addJavascriptInterface(object {
                        @android.webkit.JavascriptInterface
                        fun openSettings() {
                            post { onSettingsClick() }
                        }
                    }, "LilClaw")
                }

                webViewClient = object : WebViewClient() {
                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?
                    ) {
                        if (request?.isForMainFrame == true) {
                            view?.postDelayed({
                                view.loadUrl(buildUrl())
                            }, 2000)
                        }
                    }
                }

                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                        Log.d("LilClawWeb", "${msg?.messageLevel()}: ${msg?.message()} [${msg?.sourceId()}:${msg?.lineNumber()}]")
                        return true
                    }
                }

                clearCache(true)
                loadUrl(buildUrl())

                val appVersion = com.lilclaw.app.BuildConfig.VERSION_NAME
                evaluateJavascript("window.__LILCLAW_VERSION='$appVersion'", null)

                webView = this
            }
        },
        update = {},
    )
}

private fun buildUrl(): String =
    "http://127.0.0.1:3001/?_t=${System.currentTimeMillis()}"
