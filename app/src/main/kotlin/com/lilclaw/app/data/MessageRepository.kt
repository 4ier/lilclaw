package com.lilclaw.app.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class Topic(
    val id: String,
    val title: String,
    val lastMessage: String = "",
    val lastMessageTime: Long = System.currentTimeMillis(),
    val isPinned: Boolean = false,
)

class MessageRepository(private val gatewayClient: GatewayClient) {

    private val _topics = MutableStateFlow<List<Topic>>(
        listOf(
            Topic(
                id = "default",
                title = "New Chat",
                lastMessage = "Start a conversation...",
            )
        )
    )
    val topics: StateFlow<List<Topic>> = _topics

    private val _messagesByTopic = MutableStateFlow<Map<String, List<ChatMessage>>>(emptyMap())
    val messagesByTopic: StateFlow<Map<String, List<ChatMessage>>> = _messagesByTopic

    val incomingMessages: Flow<ChatMessage> = gatewayClient.messages
    val streamingContent: StateFlow<String?> = gatewayClient.streamingContent

    fun createTopic(title: String): Topic {
        val topic = Topic(
            id = System.currentTimeMillis().toString(),
            title = title,
        )
        _topics.update { it + topic }
        return topic
    }

    fun addMessage(topicId: String, message: ChatMessage) {
        _messagesByTopic.update { map ->
            val existing = map[topicId] ?: emptyList()
            map + (topicId to (existing + message))
        }
        _topics.update { topics ->
            topics.map { topic ->
                if (topic.id == topicId) {
                    topic.copy(
                        lastMessage = message.content.take(80),
                        lastMessageTime = message.timestamp,
                    )
                } else topic
            }
        }
    }

    fun getMessages(topicId: String): List<ChatMessage> {
        return _messagesByTopic.value[topicId] ?: emptyList()
    }

    fun sendMessage(content: String, topicId: String) {
        val msg = ChatMessage(
            id = System.currentTimeMillis().toString(),
            role = "user",
            content = content,
        )
        addMessage(topicId, msg)
        gatewayClient.sendMessage(content, topicId)
    }

    fun deleteTopic(topicId: String) {
        _topics.update { it.filter { t -> t.id != topicId } }
        _messagesByTopic.update { it - topicId }
    }
}
