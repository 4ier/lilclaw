package com.lilclaw.app.data

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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class ChatMessage(
    val id: String,
    val role: String, // "user" | "assistant" | "system" | "tool"
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

/**
 * OpenClaw Gateway WebSocket client.
 *
 * Protocol: connect to ws://host:port (root, not /ws), receive challenge,
 * send connect request with auth token, receive hello-ok.
 * Then use chat.send / chat.history / chat.abort.
 */
class GatewayClient {

    companion object {
        private const val TAG = "GatewayClient"
        private const val PROTOCOL_VERSION = 3
        val CLIENT_VERSION: String get() = com.lilclaw.app.BuildConfig.VERSION_NAME
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var currentPort: Int = 3000
    private var authToken: String = "lilclaw-local"
    private var sessionKey: String = "main"
    private var handshakeComplete = false
    private val reqIdCounter = AtomicInteger(1)

    // Pending request callbacks: id -> callback
    private val pendingRequests = mutableMapOf<String, (JSONObject) -> Unit>()

    private val _connectionState = MutableStateFlow<GatewayConnectionState>(GatewayConnectionState.Disconnected)
    val connectionState: StateFlow<GatewayConnectionState> = _connectionState

    private val _messages = MutableSharedFlow<ChatMessage>(extraBufferCapacity = 64)
    val messages: SharedFlow<ChatMessage> = _messages

    private val _streamingContent = MutableStateFlow<String?>(null)
    val streamingContent: StateFlow<String?> = _streamingContent

    // Current streaming run tracking
    private var activeRunId: String? = null
    private val streamBuffer = StringBuilder()

    fun connect(port: Int = 3000, token: String = "lilclaw-local") {
        currentPort = port
        authToken = token
        handshakeComplete = false
        _connectionState.value = GatewayConnectionState.Connecting

        val request = Request.Builder()
            .url("ws://localhost:$port")
            .header("Origin", "http://localhost:$port")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket opened, waiting for challenge...")
                // Don't set Connected yet — wait for handshake
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleFrame(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closing: $code $reason")
                webSocket.close(1000, null)
                handshakeComplete = false
                _connectionState.value = GatewayConnectionState.Disconnected
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}")
                handshakeComplete = false
                _connectionState.value = GatewayConnectionState.Error(t.message ?: "Connection failed")
                scheduleReconnect()
            }
        })
    }

    fun disconnect() {
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        handshakeComplete = false
        _connectionState.value = GatewayConnectionState.Disconnected
    }

    /**
     * Send a chat message to the gateway.
     * Uses chat.send which is non-blocking — acks immediately,
     * then streams response via chat events.
     */
    fun sendMessage(content: String, topicId: String? = null) {
        if (!handshakeComplete) {
            Log.w(TAG, "Cannot send: handshake not complete")
            return
        }

        val params = JSONObject().apply {
            put("sessionKey", sessionKey)
            put("message", content)
            put("idempotencyKey", UUID.randomUUID().toString())
        }

        sendRequest("chat.send", params) { response ->
            if (response.optBoolean("ok", false)) {
                val payload = response.optJSONObject("payload")
                val runId = payload?.optString("runId", "") ?: ""
                Log.i(TAG, "chat.send acked: runId=$runId")
                activeRunId = runId
                streamBuffer.clear()
            } else {
                val error = response.optJSONObject("error")
                    ?: response.optString("error", "unknown error")
                Log.e(TAG, "chat.send failed: $error")
            }
        }
    }

    /**
     * Fetch chat history for the current session.
     */
    fun fetchHistory(callback: (List<ChatMessage>) -> Unit) {
        if (!handshakeComplete) return

        val params = JSONObject().apply {
            put("sessionKey", sessionKey)
            put("limit", 50)
        }

        sendRequest("chat.history", params) { response ->
            if (response.optBoolean("ok", false)) {
                val payload = response.optJSONObject("payload")
                val messagesArray = payload?.optJSONArray("messages") ?: JSONArray()
                val messages = mutableListOf<ChatMessage>()
                for (i in 0 until messagesArray.length()) {
                    val msg = messagesArray.getJSONObject(i)
                    val role = msg.optString("role", "assistant")
                    // Skip tool messages for now
                    if (role == "tool") continue
                    val contentArray = msg.optJSONArray("content")
                    val text = if (contentArray != null) {
                        // content is [{type:"text", text:"..."}]
                        val parts = mutableListOf<String>()
                        for (j in 0 until contentArray.length()) {
                            val part = contentArray.getJSONObject(j)
                            if (part.optString("type") == "text") {
                                parts.add(part.optString("text", ""))
                            }
                        }
                        parts.joinToString("\n")
                    } else {
                        msg.optString("content", "")
                    }

                    if (text.isNotBlank()) {
                        messages.add(
                            ChatMessage(
                                id = msg.optString("id", UUID.randomUUID().toString()),
                                role = role,
                                content = text,
                            )
                        )
                    }
                }
                callback(messages)
            }
        }
    }

    /**
     * Abort the current agent run.
     */
    fun abortRun() {
        if (!handshakeComplete) return

        val params = JSONObject().apply {
            put("sessionKey", sessionKey)
        }
        sendRequest("chat.abort", params) { response ->
            Log.i(TAG, "chat.abort response: ok=${response.optBoolean("ok")}")
        }
    }

    // --- Protocol internals ---

    private fun handleFrame(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type", "")

            when (type) {
                "event" -> handleEvent(json)
                "res" -> handleResponse(json)
                else -> Log.d(TAG, "Unknown frame type: $type")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse frame: ${e.message}")
        }
    }

    private fun handleEvent(json: JSONObject) {
        val event = json.optString("event", "")

        when {
            event == "connect.challenge" -> handleChallenge(json)

            // Chat events — streamed responses
            event == "chat" -> handleChatEvent(json)

            // Agent events — tool calls, thinking, etc.
            event == "agent" -> handleAgentEvent(json)

            // Tick (keepalive)
            event == "tick" -> { /* ignore */ }

            else -> Log.d(TAG, "Event: $event")
        }
    }

    private fun handleChallenge(json: JSONObject) {
        Log.i(TAG, "Received connect.challenge, sending connect request...")

        val params = JSONObject().apply {
            put("minProtocol", PROTOCOL_VERSION)
            put("maxProtocol", PROTOCOL_VERSION)
            put("client", JSONObject().apply {
                put("id", "openclaw-control-ui")
                put("version", CLIENT_VERSION)
                put("platform", "android")
                put("mode", "webchat")
            })
            put("role", "operator")
            put("scopes", JSONArray().apply {
                put("operator.read")
                put("operator.write")
                put("operator.admin")
            })
            put("caps", JSONArray())
            put("auth", JSONObject().apply {
                put("token", authToken)
            })
            put("locale", "en-US")
            put("userAgent", "lilclaw-android/$CLIENT_VERSION")
        }

        sendRequest("connect", params) { response ->
            if (response.optBoolean("ok", false)) {
                val payload = response.optJSONObject("payload")
                val protocol = payload?.optInt("protocol", 0) ?: 0
                Log.i(TAG, "Connected! Protocol v$protocol")
                handshakeComplete = true
                _connectionState.value = GatewayConnectionState.Connected
            } else {
                val error = response.optString("error", "handshake failed")
                Log.e(TAG, "Connect failed: $error")
                _connectionState.value = GatewayConnectionState.Error(error)
            }
        }
    }

    private fun handleChatEvent(json: JSONObject) {
        val payload = json.optJSONObject("payload") ?: return
        val state = payload.optString("state", "")
        val message = payload.optJSONObject("message") ?: return

        val role = message.optString("role", "assistant")
        // Content is an array: [{type:"text", text:"..."}]
        val contentArray = message.optJSONArray("content")
        val text = if (contentArray != null) {
            val parts = mutableListOf<String>()
            for (i in 0 until contentArray.length()) {
                val part = contentArray.getJSONObject(i)
                if (part.optString("type") == "text") {
                    parts.add(part.optString("text", ""))
                }
            }
            parts.joinToString("")
        } else {
            message.optString("content", "")
        }

        when (state) {
            "delta" -> {
                // Streaming delta — update streaming content
                if (text.isNotEmpty()) {
                    _streamingContent.value = text
                }
            }

            "final" -> {
                // Final complete message
                if (text.isNotBlank()) {
                    scope.launch {
                        _messages.emit(
                            ChatMessage(
                                id = UUID.randomUUID().toString(),
                                role = role,
                                content = text,
                            )
                        )
                    }
                }
                _streamingContent.value = null
                activeRunId = null
            }
        }
    }

    private fun handleAgentEvent(json: JSONObject) {
        val payload = json.optJSONObject("payload") ?: return
        val kind = payload.optString("kind", "")

        when (kind) {
            "thinking" -> {
                // Agent is thinking — could show indicator
                Log.d(TAG, "Agent thinking...")
            }
            "tool_use" -> {
                val toolName = payload.optString("name", "tool")
                Log.d(TAG, "Agent using tool: $toolName")
            }
            "tool_result" -> {
                Log.d(TAG, "Tool result received")
            }
            "done" -> {
                Log.i(TAG, "Agent run complete")
                activeRunId = null
            }
            "error" -> {
                val error = payload.optString("message", "Agent error")
                Log.e(TAG, "Agent error: $error")
                scope.launch {
                    _messages.emit(
                        ChatMessage(
                            id = UUID.randomUUID().toString(),
                            role = "system",
                            content = "Error: $error",
                        )
                    )
                }
                activeRunId = null
                _streamingContent.value = null
            }
        }
    }

    private fun handleResponse(json: JSONObject) {
        val id = json.optString("id", "")
        val callback = pendingRequests.remove(id)
        if (callback != null) {
            callback(json)
        } else {
            Log.d(TAG, "Response for unknown request: $id")
        }
    }

    private fun sendRequest(method: String, params: JSONObject, callback: ((JSONObject) -> Unit)? = null) {
        val id = reqIdCounter.getAndIncrement().toString()
        if (callback != null) {
            pendingRequests[id] = callback
        }

        val frame = JSONObject().apply {
            put("type", "req")
            put("id", id)
            put("method", method)
            put("params", params)
        }

        val text = frame.toString()
        Log.d(TAG, ">>> $method (id=$id)")
        webSocket?.send(text) ?: Log.e(TAG, "WebSocket is null, cannot send $method")
    }

    private fun scheduleReconnect() {
        scope.launch {
            delay(3000)
            if (_connectionState.value is GatewayConnectionState.Error ||
                _connectionState.value is GatewayConnectionState.Disconnected
            ) {
                connect(currentPort, authToken)
            }
        }
    }
}
