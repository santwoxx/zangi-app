package com.zangi.chat.data.model

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class MessageType {
    TEXT,
    CAMERA_PHOTO,
    SCREEN_CAPTURE,
    GALLERY_IMAGE
}

enum class MessageStatus {
    PENDING,
    UPLOADING,
    SENT,
    FAILED
}

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val conversationId: String = "default_chat",
    val text: String = "",
    val isSentByMe: Boolean = true,
    val senderId: String = "",
    val senderName: String = "Alexander M.",
    val senderZangiNumber: String = "10-742-9901",
    val type: MessageType = MessageType.TEXT,
    val status: MessageStatus = MessageStatus.SENT,
    val timestamp: Long = System.currentTimeMillis(),
    val localFile: File? = null,
    val remoteFileUrl: String? = null
) {
    val formattedTime: String
        get() {
            val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
            return sdf.format(Date(timestamp))
        }
}
