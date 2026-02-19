package com.lilclaw.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class ChatMessage(
    val id: String,
    val role: String, // "user" | "assistant" | "system"
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isStreaming: Boolean = false,
)

sealed class GatewayConnectionState {
    data object Disconnected : GatewayConnectionState()
    data object Connecting : GatewayConnectionState()
    data object Connected : GatewayConnectionState()
    data class Error(val message: String) : GatewayConnectionState()
}

class GatewayClient {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // no timeout for WS
        .build()

    private var webSocket: WebSocket? = null
    private var currentPort: Int = 3000

    private val _connectionState = MutableStateFlow<GatewayConnectionState>(GatewayConnectionState.Disconnected)
    val connectionState: StateFlow<GatewayConnectionState> = _connectionState

    private val _messages = MutableSharedFlow<ChatMessage>(extraBufferCapacity = 64)
    val messages: SharedFlow<ChatMessage> = _messages

    private val _streamingContent = MutableStateFlow<String?>(null)
    val streamingContent: StateFlow<String?> = _streamingContent

    fun connect(port: Int = 3000) {
        currentPort = port
        _connectionState.value = GatewayConnectionState.Connecting

        val request = Request.Builder()
            .url("ws://127.0.0.1:$port/ws")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _connectionState.value = GatewayConnectionState.Connected
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                _connectionState.value = GatewayConnectionState.Disconnected
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _connectionState.value = GatewayConnectionState.Error(t.message ?: "Connection failed")
                scheduleReconnect()
            }
        })
    }

    fun disconnect() {
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        _connectionState.value = GatewayConnectionState.Disconnected
    }

    fun sendMessage(content: String, topicId: String? = null) {
        val json = JSONObject().apply {
            put("type", "message")
            put("content", content)
            if (topicId != null) put("topicId", topicId)
        }
        webSocket?.send(json.toString())
    }

    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type", "")
            when (type) {
                "message" -> {
                    val msg = ChatMessage(
                        id = json.optString("id", System.currentTimeMillis().toString()),
                        role = json.optString("role", "assistant"),
                        content = json.optString("content", ""),
                    )
                    scope.launch { _messages.emit(msg) }
                    _streamingContent.value = null
                }
                "stream" -> {
                    val chunk = json.optString("content", "")
                    _streamingContent.value = (_streamingContent.value ?: "") + chunk
                }
                "stream_end" -> {
                    _streamingContent.value = null
                }
            }
        } catch (_: Exception) {
            // Ignore malformed messages
        }
    }

    private fun scheduleReconnect() {
        scope.launch {
            delay(3000)
            if (_connectionState.value is GatewayConnectionState.Error ||
                _connectionState.value is GatewayConnectionState.Disconnected) {
                connect(currentPort)
            }
        }
    }
}
