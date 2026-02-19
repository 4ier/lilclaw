package com.lilclaw.app.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lilclaw.app.data.ChatMessage
import com.lilclaw.app.data.GatewayClient
import com.lilclaw.app.data.GatewayConnectionState
import com.lilclaw.app.data.MessageRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatState(
    val messages: List<ChatMessage> = emptyList(),
    val inputText: String = "",
    val isConnected: Boolean = false,
    val streamingText: String? = null,
)

class ChatViewModel(
    private val messageRepo: MessageRepository,
    private val gatewayClient: GatewayClient,
) : ViewModel() {

    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state

    private var currentTopicId: String = ""

    fun setTopic(topicId: String) {
        currentTopicId = topicId
        _state.update { it.copy(messages = messageRepo.getMessages(topicId)) }
    }

    init {
        viewModelScope.launch {
            gatewayClient.connectionState.collect { connState ->
                _state.update {
                    it.copy(isConnected = connState is GatewayConnectionState.Connected)
                }
            }
        }
        viewModelScope.launch {
            messageRepo.incomingMessages.collect { msg ->
                messageRepo.addMessage(currentTopicId, msg)
                _state.update { it.copy(messages = messageRepo.getMessages(currentTopicId)) }
            }
        }
        viewModelScope.launch {
            gatewayClient.streamingContent.collect { content ->
                _state.update { it.copy(streamingText = content) }
            }
        }
    }

    fun onSendText(text: String) {
        if (text.isBlank()) return
        messageRepo.sendMessage(text, currentTopicId)
        _state.update {
            it.copy(messages = messageRepo.getMessages(currentTopicId))
        }
    }

    fun onInputChanged(text: String) {
        _state.update { it.copy(inputText = text) }
    }

    fun onSend() {
        val text = _state.value.inputText.trim()
        if (text.isEmpty()) return

        messageRepo.sendMessage(text, currentTopicId)
        _state.update {
            it.copy(
                inputText = "",
                messages = messageRepo.getMessages(currentTopicId),
            )
        }
    }
}
