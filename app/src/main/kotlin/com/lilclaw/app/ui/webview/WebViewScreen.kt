package com.lilclaw.app.ui.webview

import android.annotation.SuppressLint
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewScreen(
    onSettingsClick: () -> Unit,
) {
    var webView by remember { mutableStateOf<WebView?>(null) }

    BackHandler(enabled = webView?.canGoBack() == true) {
        webView?.goBack()
    }

    // Read IME + system bar insets as raw dp values — NO animation.
    // imePadding() animates padding over ~300ms which causes
    // continuous WebView resize → HTML relayout every frame = jank.
    // Reading WindowInsets directly snaps to the final value instantly.
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    val systemBarsInsets = WindowInsets.systemBars

    val imeBottom = with(density) { imeInsets.getBottom(density).toDp() }
    val systemTop = with(density) { systemBarsInsets.getTop(density).toDp() }
    val systemBottom = with(density) { systemBarsInsets.getBottom(density).toDp() }

    // When keyboard is open, use IME bottom; otherwise use system nav bar bottom
    val bottomPadding = if (imeBottom > systemBottom) imeBottom else systemBottom

    AndroidView(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = systemTop, bottom = bottomPadding)
            .consumeWindowInsets(WindowInsets.systemBars)
            .consumeWindowInsets(WindowInsets.ime),
        factory = { context ->
            WebView(context).apply {
                WebView.setWebContentsDebuggingEnabled(true)

                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                overScrollMode = WebView.OVER_SCROLL_NEVER

                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                @Suppress("DEPRECATION")
                settings.databaseEnabled = true
                settings.allowFileAccess = false
                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun openSettings() {
                        post { onSettingsClick() }
                    }
                }, "LilClaw")

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
                webView = this
            }
        },
        update = {},
    )
}

private fun buildUrl(): String =
    "http://127.0.0.1:3001/?_t=${System.currentTimeMillis()}"
