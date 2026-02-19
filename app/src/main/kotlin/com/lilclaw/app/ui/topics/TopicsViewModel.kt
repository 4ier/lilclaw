package com.lilclaw.app.ui.topics

import androidx.lifecycle.ViewModel
import com.lilclaw.app.data.MessageRepository
import com.lilclaw.app.data.Topic
import kotlinx.coroutines.flow.StateFlow

class TopicsViewModel(
    private val messageRepo: MessageRepository,
) : ViewModel() {

    val topics: StateFlow<List<Topic>> = messageRepo.topics

    fun createTopic(title: String): Topic {
        return messageRepo.createTopic(title)
    }

    fun deleteTopic(topicId: String) {
        messageRepo.deleteTopic(topicId)
    }
}
