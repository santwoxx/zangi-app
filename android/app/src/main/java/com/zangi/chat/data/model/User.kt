package com.zangi.chat.data.model

data class User(
    val id: String,
    val nickname: String,
    val zangiNumber: String,
    val avatarUrl: String? = null,
    val avatarLocalUri: String? = null,
    val createdAt: String? = null
)

data class Conversation(
    val id: String,
    val name: String,
    val zangiNumber: String,
    val avatarUrl: String? = null,
    val isGroup: Boolean = false,
    val memberCount: Int = 2,
    val lastMessage: String = "",
    val lastMessageTime: String = "",
    val unreadCount: Int = 0
)
